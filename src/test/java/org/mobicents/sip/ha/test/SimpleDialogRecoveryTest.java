package org.mobicents.sip.ha.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogState;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionState;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ContentTypeHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.Header;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RecordRouteHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.ViaHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import junit.framework.TestCase;
/**
 * This test aims to test Mobicents Jain Sip failover recovery.
 * Shootist on port 5060 shoots at a stateless proxy on prt 5050 (scaled down version of a balancer)
 * Stateless proxy redirect to Shootme on port 5070
 * on ACK, the Shootme stop itself and start the other shootme node on port 5080 and pass to him its current dialogs
 * on BYE or other in-dialog requests, the stateless proxy forwards to recovery shootme on port 5080
 * Shootme recovery sends OK to BYE.
 *
 * @author <A HREF="mailto:jean.deruelle@gmail.com">Jean Deruelle</A>
 *
 */
public class SimpleDialogRecoveryTest extends TestCase {

	private static String getIpAddressFromProperties(){
		Properties p = new Properties();
		try {
			p.load(SimpleDialogRecoveryTest.class.getClassLoader().getResourceAsStream("test.properties"));
		} catch (IOException e) {
			e.printStackTrace();
		}
		return p.getProperty("IP_ADDRESS","127.0.0.1");
	}
	
    public static final String IP_ADDRESS = getIpAddressFromProperties();
	
    public static final int BALANCER_PORT = 5050;

    private static AddressFactory addressFactory;

    private static MessageFactory messageFactory;

    private static HeaderFactory headerFactory;


    Shootist shootist;

    Shootme shootme;

    Shootme shootmeRecoveryNode;

    Balancer balancer;

    /**
     * Class acting like a stateless proxy sip balancer 
     */
    class Balancer implements SipListener {

        private String myHost;

        private int myPort;

        private SipStack sipStack;

        private SipProvider sipProvider;

        public Balancer(String host, int port) {
            this.myHost = host;
            this.myPort = port;
        }

        public void start() throws IllegalStateException {

            SipFactory sipFactory = null;
            sipStack = null;

            Properties properties = new Properties();
            properties.setProperty("javax.sip.RETRANSMISSION_FILTER", "true");
            properties.setProperty("javax.sip.STACK_NAME", "StatelessForwarder");
            properties.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");            
            properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "ERROR");
            properties.setProperty("gov.nist.javax.sip.LOG_MESSAGE_CONTENT", "false");
            properties.setProperty("gov.nist.javax.sip.DEBUG_LOG",
                    "./logs/statelessforwarderdebug.txt");
            //properties.setProperty("gov.nist.javax.sip.SERVER_LOG", "./logs/statelessforwarderlog.xml");

            try {
                // Create SipStack object
                sipFactory = SipFactory.getInstance();
                sipFactory.setPathName("gov.nist");
                sipStack = sipFactory.createSipStack(properties);

                headerFactory = sipFactory.createHeaderFactory();
                addressFactory = sipFactory.createAddressFactory();
                messageFactory = sipFactory.createMessageFactory();

                ListeningPoint lp = sipStack.createListeningPoint(myHost, myPort, ListeningPoint.UDP);
                sipProvider = sipStack.createSipProvider(lp);
                sipProvider.addSipListener(this);

                sipStack.start();
            } catch (Exception ex) {
                throw new IllegalStateException("Cant create sip objects and lps due to["+ex.getMessage()+"]", ex);
            }
        }

        public void processDialogTerminated(
                DialogTerminatedEvent dialogTerminatedEvent) {}

        public void processIOException(IOExceptionEvent exceptionEvent) {}

        public void processRequest(RequestEvent requestEvent) {
            try {
                Request request = requestEvent.getRequest();

                ViaHeader viaHeader = headerFactory.createViaHeader(
                        this.myHost, this.myPort, ListeningPoint.UDP, "z9hG4bK"+Math.random()*31+""+System.currentTimeMillis());
                //Decreasing the Max Forward Header
                MaxForwardsHeader maxForwardsHeader = (MaxForwardsHeader) request.getHeader(MaxForwardsHeader.NAME);
                if (maxForwardsHeader == null) {
                    maxForwardsHeader = headerFactory.createMaxForwardsHeader(70);
                    request.addHeader(maxForwardsHeader);
                } else {
                    maxForwardsHeader.setMaxForwards(maxForwardsHeader.getMaxForwards() - 1);
                }
                // Add the via header to the top of the header list.
                request.addHeader(viaHeader);
                //Removing first routeHeader if it is for us
                RouteHeader routeHeader = (RouteHeader) request.getHeader(RouteHeader.NAME);
                if(routeHeader != null) {
                    SipURI routeUri = (SipURI)routeHeader.getAddress().getURI();
                    if(routeUri.getHost().equalsIgnoreCase(myHost) && routeUri.getPort() == myPort) {
                        request.removeFirst(RouteHeader.NAME);
                    }
                }

                // Record route the invite so the bye comes to me.
                if (request.getMethod().equals(Request.INVITE) || request.getMethod().equals(Request.SUBSCRIBE)) {
                    SipURI sipUri = addressFactory
                            .createSipURI(null, sipProvider.getListeningPoint(
                                    ListeningPoint.UDP).getIPAddress());
                    sipUri.setPort(sipProvider.getListeningPoint(ListeningPoint.UDP).getPort());
                    //See RFC 3261 19.1.1 for lr parameter
                    sipUri.setLrParam();
                    Address address = addressFactory.createAddress(sipUri);
                    address.setURI(sipUri);
                    RecordRouteHeader recordRoute = headerFactory
                            .createRecordRouteHeader(address);
                    request.addHeader(recordRoute);

                    //Adding Route Header
                    SipURI routeSipUri = addressFactory
                        .createSipURI(null, IP_ADDRESS);
                    routeSipUri.setPort(5070);
                    routeSipUri.setLrParam();
                    RouteHeader route = headerFactory.createRouteHeader(addressFactory.createAddress(routeSipUri));
                    request.addFirst(route);
                }
                else if (!Request.ACK.equals(request.getMethod())) {
                    //Adding Route Header
                    if(((SipURI)request.getRequestURI()).getPort() == 5070) {
                        SipURI routeSipUri = addressFactory
                            .createSipURI(null, IP_ADDRESS);
                        routeSipUri.setPort(5080);
                        routeSipUri.setLrParam();
                        RouteHeader route = headerFactory.createRouteHeader(addressFactory.createAddress(routeSipUri));
                        request.addFirst(route);
                    }
                }
                //sending request
                sipProvider.sendRequest(request);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        public void processResponse(ResponseEvent responseEvent) {
            try {
                Response response = responseEvent.getResponse();
                SipProvider sender=null;

                 // Topmost via header is me. As it is reposne to external reqeust
                response.removeFirst(ViaHeader.NAME);

                sender=this.sipProvider;
                sender.sendResponse(response);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        public void processTimeout(TimeoutEvent timeoutEvent) {}

        public void processTransactionTerminated(
                TransactionTerminatedEvent transactionTerminatedEvent) {}

    }

    /**
     * This class is acting like a UAS (node 1 & 2 in a cluster)
     * Node 1 will receive the INVITE, reply with 180 Ringing, 200 OK and will receive the ACK and shutdown itself
     * Node 2 will receive the BYE and reply with 200 OK
     */
    class Shootme implements SipListener {


        private SipStack sipStack;

        private final String myAddress = IP_ADDRESS;

        private String stackName;

        public int myPort = 5070;

        protected ServerTransaction inviteTid;

        private Response okResponse;

        private Request inviteRequest;

        private Dialog dialog;

        public boolean callerSendsBye = true;

        private  SipProvider sipProvider;

        private boolean byeTaskRunning;

        public Shootme(String stackName, int myPort, boolean callerSendsBye) {
            this.stackName = stackName;
            this.myPort = myPort;
            this.callerSendsBye = callerSendsBye;
            System.setProperty("jgroups.bind_addr", IP_ADDRESS);
            System.setProperty("jgroups.udp.mcast_addr", "FFFF::232.5.5.5");
            System.setProperty("jboss.server.log.threshold", "DEBUG");
            System.setProperty("java.net.preferIPv4Stack", "true");
            System.setProperty("jbosscache.config.validate", "false");
            
        }        

        class MyTimerTask extends TimerTask {
            Shootme shootme;

            public MyTimerTask(Shootme shootme) {
                this.shootme = shootme;

            }

            public void run() {
                shootme.sendInviteOK();
            }

        }

        protected static final String usageString = "java "
                + "examples.shootist.Shootist \n"
                + ">>>> is your class path set to the root?";



        public void processRequest(RequestEvent requestEvent) {
            Request request = requestEvent.getRequest();
            ServerTransaction serverTransactionId = requestEvent
                    .getServerTransaction();            

            if (request.getMethod().equals(Request.INVITE)) {
                processInvite(requestEvent, serverTransactionId);
            } else if (request.getMethod().equals(Request.ACK)) {
                processAck(requestEvent, serverTransactionId);
            } else if (request.getMethod().equals(Request.BYE)) {
                processBye(requestEvent, serverTransactionId);
            } 
        }

        /**
         * Process the bye request.
         */
        public void processBye(RequestEvent requestEvent,
                ServerTransaction serverTransactionId) {
            SipProvider sipProvider = (SipProvider) requestEvent.getSource();
            Request request = requestEvent.getRequest();
            Dialog dialog = requestEvent.getDialog();
            //System.out.println("local party = " + dialog.getLocalParty());
            try {
                System.out.println("Request " + request.getMethod()
                    + " received at " + sipStack.getStackName() +" : sending OK.");
                Response response = messageFactory.createResponse(200, request);
                serverTransactionId.sendResponse(response);
                //System.out.println("Dialog State is "
                //        + serverTransactionId.getDialog().getState());

            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(0);

            }
        }

        public void processResponse(ResponseEvent responseEvent) {
        }

        /**
         * Process the ACK request. Send the bye and complete the call flow.
         */
        public void processAck(RequestEvent requestEvent,
                ServerTransaction serverTransaction) {
            try {
                System.out.println("Request " + requestEvent.getRequest().getMethod()
                    + " received at " + sipStack.getStackName() + " ! Stopping the first node to test dialog recovery on shootme_recovery!");
                //stopping the node 
                stop();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

        }

        /**
         * Process the invite request.
         */
        public void processInvite(RequestEvent requestEvent,
                ServerTransaction serverTransaction) {
            SipProvider sipProvider = (SipProvider) requestEvent.getSource();
            Request request = requestEvent.getRequest();
            try {
                System.out.println("Request " + request.getMethod()
                    + " received at " + sipStack.getStackName() + " sending Trying");
                Response response = messageFactory.createResponse(Response.RINGING,
                        request);
		ToHeader toHeader = (ToHeader) response.getHeader(ToHeader.NAME);
                toHeader.setTag("4321"); // Application is supposed to set.
                ServerTransaction st = requestEvent.getServerTransaction();

                if (st == null) {
                    st = sipProvider.getNewServerTransaction(request);
                }
                dialog = st.getDialog();

                st.sendResponse(response);

                this.okResponse = messageFactory.createResponse(Response.OK,
                        request);
                Address address = addressFactory.createAddress("Shootme <sip:"
                        + myAddress + ":" + myPort + ">");
                ContactHeader contactHeader = headerFactory
                        .createContactHeader(address);
                response.addHeader(contactHeader);
                toHeader = (ToHeader) okResponse.getHeader(ToHeader.NAME);
                toHeader.setTag("4321"); // Application is supposed to set.
                okResponse.addHeader(contactHeader);
                this.inviteTid = st;
                // Defer sending the OK to simulate the phone ringing.
                // Answered in 1 second ( this guy is fast at taking calls)
                this.inviteRequest = request;

                new Timer().schedule(new MyTimerTask(this), 1000);
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(0);
            }
        }

        private void sendInviteOK() {
            try {
                if (inviteTid.getState() != TransactionState.COMPLETED) {
                    //System.out.println("shootme: Dialog state before 200: "
                    //        + inviteTid.getDialog().getState());
                    inviteTid.sendResponse(okResponse);
                    //System.out.println("shootme: Dialog state after 200: "
                    //        + inviteTid.getDialog().getState());
                }
            } catch (SipException ex) {
                ex.printStackTrace();
            } catch (InvalidArgumentException ex) {
                ex.printStackTrace();
            }
        }         

        public void processTimeout(javax.sip.TimeoutEvent timeoutEvent) {}

        public void init() {
            SipFactory sipFactory = null;
            sipStack = null;
            sipFactory = SipFactory.getInstance();
            sipFactory.setPathName("org.mobicents.ha");
            Properties properties = new Properties();
            properties.setProperty("javax.sip.STACK_NAME", stackName);
            properties.setProperty("org.mobicents.ha.javax.sip.cache.MobicentsSipCache.standalone", "true");            
            properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "DEBUG");
            properties.setProperty("gov.nist.javax.sip.LOG_MESSAGE_CONTENT", "false");
            properties.setProperty("gov.nist.javax.sip.DEBUG_LOG", "logs/" +
                    stackName + "debug.txt");
//            properties.setProperty("gov.nist.javax.sip.SERVER_LOG", "logs/" +
  //                  stackName + "log.xml");

            try {
                // Create SipStack object
                sipStack = sipFactory.createSipStack(properties);
                //System.out.println("sipStack = " + sipStack);
            } catch (PeerUnavailableException e) {
                // could not find
                // gov.nist.jain.protocol.ip.sip.SipStackImpl
                // in the classpath
                e.printStackTrace();
                System.err.println(e.getMessage());
                if (e.getCause() != null)
                    e.getCause().printStackTrace();
                System.exit(0);
            }

            try {
                headerFactory = sipFactory.createHeaderFactory();
                addressFactory = sipFactory.createAddressFactory();
                messageFactory = sipFactory.createMessageFactory();
                ListeningPoint lp = sipStack.createListeningPoint(myAddress,
                        myPort, ListeningPoint.UDP);

                Shootme listener = this;

                sipProvider = sipStack.createSipProvider(lp);
                //System.out.println("udp provider " + sipProvider);
                sipProvider.addSipListener(listener);
                sipStack.start();
                if(!callerSendsBye && this.dialog != null) {
                    try {
                       Request byeRequest = this.dialog.createRequest(Request.BYE);
                       ClientTransaction ct = sipProvider.getNewClientTransaction(byeRequest);
                       System.out.println(sipStack.getStackName() + " : sending BYE " + byeRequest);
                       dialog.sendRequest(ct);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        fail("Unexpected exception ");
                    }
                }
            } catch (Exception ex) {
                System.out.println(sipStack.getStackName() + " : " + ex.getMessage());
                ex.printStackTrace();
                fail("Unexpected exception");
            }
        }
       
        public void processIOException(IOExceptionEvent exceptionEvent) {}

        public void processTransactionTerminated(
                TransactionTerminatedEvent transactionTerminatedEvent) {}

        public void processDialogTerminated(
                DialogTerminatedEvent dialogTerminatedEvent) {}

        public void stop() {
            stopSipStack(sipStack, this);
        }

    }

    /**
     * This class will act as UAC and will send the requests to the scaled down SIP balancer that will in turn target the cluster of 2 nodes UAS 
     */
    class Shootist implements SipListener {

        private  SipProvider sipProvider;

        private SipStack sipStack;

        private ContactHeader contactHeader;

        private ListeningPoint udpListeningPoint;

        private ClientTransaction inviteTid;

        private Dialog dialog;

        private boolean byeTaskRunning;

        public boolean callerSendsBye = true;

        class ByeTask  extends TimerTask {
            Dialog dialog;
            public ByeTask(Dialog dialog)  {
                this.dialog = dialog;
            }
            public void run () {
                try {
                   Request byeRequest = this.dialog.createRequest(Request.BYE);
                   ClientTransaction ct = sipProvider.getNewClientTransaction(byeRequest);
                   dialog.sendRequest(ct);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    fail("Unexpected exception ");
                }

            }

        }

        public Shootist(boolean callerSendsBye) {
            this.callerSendsBye = callerSendsBye;
        }

        public void processRequest(RequestEvent requestReceivedEvent) {            
        }       

           // Save the created ACK request, to respond to retransmitted 2xx
           private Request ackRequest;

		public boolean okToByeReceived;

        public void processResponse(ResponseEvent responseReceivedEvent) {
            //System.out.println("Got a response");
            Response response = (Response) responseReceivedEvent.getResponse();
            ClientTransaction tid = responseReceivedEvent.getClientTransaction();
            CSeqHeader cseq = (CSeqHeader) response.getHeader(CSeqHeader.NAME);

            System.out.println("Response : Status Code = " + response.getStatusCode()
                    + " received at " + sipStack.getStackName());


            if (tid == null) {

                // RFC3261: MUST respond to every 2xx
                if (ackRequest!=null && dialog!=null) {
                   System.out.println(sipStack.getStackName() + " re-sending ACK");
                   try {
                      dialog.sendAck(ackRequest);
                   } catch (SipException se) {
                      se.printStackTrace();
                      fail("Unxpected exception ");
                   }
                }
                return;
            }
            // If the caller is supposed to send the bye
            if ( callerSendsBye && !byeTaskRunning) {
                byeTaskRunning = true;
                new Timer().schedule(new ByeTask(dialog), 4000) ;
            }
            //System.out.println("transaction state is " + tid.getState());
            //System.out.println("Dialog = " + tid.getDialog());
            //System.out.println("Dialog State is " + tid.getDialog().getState());

            assertSame("Checking dialog identity",tid.getDialog(), this.dialog);

            try {
                if (response.getStatusCode() == Response.OK) {
                    if (cseq.getMethod().equals(Request.INVITE)) {
                        //System.out.println("Dialog after 200 OK  " + dialog);
                        //System.out.println("Dialog State after 200 OK  " + dialog.getState());
                        Request ackRequest = dialog.createAck(cseq.getSeqNumber());
                        System.out.println(sipStack.getStackName() + " : Sending ACK");
                        dialog.sendAck(ackRequest);

                        // JvB: test REFER, reported bug in tag handling
//                      Request referRequest = dialog.createRequest("REFER");
//                      //simulating a balancer that will forward the request to the recovery node
//                      SipURI referRequestURI = addressFactory.createSipURI(null, "127.0.0.1:5080");
//                      referRequest.setRequestURI(referRequestURI);
//                      dialog.sendRequest(  sipProvider.getNewClientTransaction(referRequest));
//
                    } else if (cseq.getMethod().equals(Request.CANCEL)) {
                        if (dialog.getState() == DialogState.CONFIRMED) {
                            // oops cancel went in too late. Need to hang up the
                            // dialog.
                            System.out
                                    .println("Sending BYE -- cancel went in too late !!");
                            Request byeRequest = dialog.createRequest(Request.BYE);
                            ClientTransaction ct = sipProvider
                                    .getNewClientTransaction(byeRequest);
                            dialog.sendRequest(ct);

                        }

                    } else  if (cseq.getMethod().equals(Request.BYE)) {
                    	okToByeReceived = true;
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                System.exit(0);
            }

        }

        public void processTimeout(javax.sip.TimeoutEvent timeoutEvent) {}
        
        public void init() {
            SipFactory sipFactory = null;
            sipStack = null;
            sipFactory = SipFactory.getInstance();
            sipFactory.setPathName("gov.nist");
            Properties properties = new Properties();
            // If you want to try TCP transport change the following to
            String transport = "udp";
            String peerHostPort = IP_ADDRESS + ":" + BALANCER_PORT;
            properties.setProperty("javax.sip.OUTBOUND_PROXY", peerHostPort + "/"
                    + transport);
            // If you want to use UDP then uncomment this.
            properties.setProperty("javax.sip.STACK_NAME", "shootist");

            // The following properties are specific to nist-sip
            // and are not necessarily part of any other jain-sip
            // implementation.
            // You can set a max message size for tcp transport to
            // guard against denial of service attack.
            properties.setProperty("gov.nist.javax.sip.DEBUG_LOG",
                    "logs/shootistdebug.txt");
            properties.setProperty("gov.nist.javax.sip.SERVER_LOG",
                    "logs/shootistlog.xml");

            // Drop the client connection after we are done with the transaction.
            properties.setProperty("gov.nist.javax.sip.CACHE_CLIENT_CONNECTIONS",
                    "false");
            // Set to 0 (or NONE) in your production code for max speed.
            // You need 16 (or TRACE) for logging traces. 32 (or DEBUG) for debug + traces.
            // Your code will limp at 32 but it is best for debugging.
            properties.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "ERROR");
            //properties.setProperty("gov.nist.javax.sip.LOG_MESSAGE_CONTENT", "false");

            try {
                // Create SipStack object
                sipStack = sipFactory.createSipStack(properties);
//                System.out.println("createSipStack " + sipStack);
            } catch (PeerUnavailableException e) {
                // could not find
                // gov.nist.jain.protocol.ip.sip.SipStackImpl
                // in the classpath
                e.printStackTrace();
                System.err.println(e.getMessage());
                System.exit(0);
            }

            try {
                headerFactory = sipFactory.createHeaderFactory();
                addressFactory = sipFactory.createAddressFactory();
                messageFactory = sipFactory.createMessageFactory();
                udpListeningPoint = sipStack.createListeningPoint(IP_ADDRESS, 5060, "udp");
                sipProvider = sipStack.createSipProvider(udpListeningPoint);
                Shootist listener = this;
                sipProvider.addSipListener(listener);

                String fromName = "BigGuy";
                String fromSipAddress = "here.com";
                String fromDisplayName = "The Master Blaster";

                String toSipAddress = "there.com";
                String toUser = "LittleGuy";
                String toDisplayName = "The Little Blister";

                // create >From Header
                SipURI fromAddress = addressFactory.createSipURI(fromName,
                        fromSipAddress);

                Address fromNameAddress = addressFactory.createAddress(fromAddress);
                fromNameAddress.setDisplayName(fromDisplayName);
                FromHeader fromHeader = headerFactory.createFromHeader(
                        fromNameAddress, "12345");

                // create To Header
                SipURI toAddress = addressFactory
                        .createSipURI(toUser, toSipAddress);
                Address toNameAddress = addressFactory.createAddress(toAddress);
                toNameAddress.setDisplayName(toDisplayName);
                ToHeader toHeader = headerFactory.createToHeader(toNameAddress,
                        null);

                // create Request URI
                SipURI requestURI = addressFactory.createSipURI(toUser,
                        peerHostPort);

                // Create ViaHeaders

                ArrayList viaHeaders = new ArrayList();
                String ipAddress = udpListeningPoint.getIPAddress();
                ViaHeader viaHeader = headerFactory.createViaHeader(ipAddress,
                        sipProvider.getListeningPoint(transport).getPort(),
                        transport, null);

                // add via headers
                viaHeaders.add(viaHeader);

                // Create ContentTypeHeader
                ContentTypeHeader contentTypeHeader = headerFactory
                        .createContentTypeHeader("application", "sdp");

                // Create a new CallId header
                CallIdHeader callIdHeader = sipProvider.getNewCallId();

                // Create a new Cseq header
                CSeqHeader cSeqHeader = headerFactory.createCSeqHeader(1L,
                        Request.INVITE);

                // Create a new MaxForwardsHeader
                MaxForwardsHeader maxForwards = headerFactory
                        .createMaxForwardsHeader(70);

                // Create the request.
                Request request = messageFactory.createRequest(requestURI,
                        Request.INVITE, callIdHeader, cSeqHeader, fromHeader,
                        toHeader, viaHeaders, maxForwards);
                // Create contact headers
                String host = IP_ADDRESS;

                SipURI contactUrl = addressFactory.createSipURI(fromName, host);
                contactUrl.setPort(udpListeningPoint.getPort());
                contactUrl.setLrParam();

                // Create the contact name address.
                SipURI contactURI = addressFactory.createSipURI(fromName, host);
                contactURI.setPort(sipProvider.getListeningPoint(transport)
                        .getPort());

                Address contactAddress = addressFactory.createAddress(contactURI);

                // Add the contact address.
                contactAddress.setDisplayName(fromName);

                contactHeader = headerFactory.createContactHeader(contactAddress);
                request.addHeader(contactHeader);

                // You can add extension headers of your own making
                // to the outgoing SIP request.
                // Add the extension header.
                Header extensionHeader = headerFactory.createHeader("My-Header",
                        "my header value");
                request.addHeader(extensionHeader);

                String sdpData = "v=0\r\n"
                        + "o=4855 13760799956958020 13760799956958020"
                        + " IN IP4  129.6.55.78\r\n" + "s=mysession session\r\n"
                        + "p=+46 8 52018010\r\n" + "c=IN IP4  129.6.55.78\r\n"
                        + "t=0 0\r\n" + "m=audio 6022 RTP/AVP 0 4 18\r\n"
                        + "a=rtpmap:0 PCMU/8000\r\n" + "a=rtpmap:4 G723/8000\r\n"
                        + "a=rtpmap:18 G729A/8000\r\n" + "a=ptime:20\r\n";
                byte[] contents = sdpData.getBytes();

                request.setContent(contents, contentTypeHeader);
                // You can add as many extension headers as you
                // want.

                extensionHeader = headerFactory.createHeader("My-Other-Header",
                        "my new header value ");
                request.addHeader(extensionHeader);

                Header callInfoHeader = headerFactory.createHeader("Call-Info",
                        "<http://www.antd.nist.gov>");
                request.addHeader(callInfoHeader);

                // Create the client transaction.
                inviteTid = sipProvider.getNewClientTransaction(request);

                // send the request out.
                inviteTid.sendRequest();

                dialog = inviteTid.getDialog();

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                ex.printStackTrace();
                fail("Unxpected exception ");
            }
        }
        public void processIOException(IOExceptionEvent exceptionEvent) {}

        public void processTransactionTerminated(
                TransactionTerminatedEvent transactionTerminatedEvent) {}

        public void processDialogTerminated(
                DialogTerminatedEvent dialogTerminatedEvent) {}

        public void stop() {
            stopSipStack(sipStack, this);
        }
    }

    public static void stopSipStack(SipStack sipStack, SipListener listener) {
        Iterator<SipProvider> sipProviderIterator = sipStack.getSipProviders();
        try{
            while (sipProviderIterator.hasNext()) {
                SipProvider sipProvider = sipProviderIterator.next();
                ListeningPoint[] listeningPoints = sipProvider.getListeningPoints();
                for (ListeningPoint listeningPoint : listeningPoints) {
                    sipProvider.removeListeningPoint(listeningPoint);
                    sipStack.deleteListeningPoint(listeningPoint);
                    listeningPoints = sipProvider.getListeningPoints();
                }
                sipProvider.removeSipListener(listener);
                sipStack.deleteSipProvider(sipProvider);
                sipProviderIterator = sipStack.getSipProviders();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cant remove the listening points or sip providers", e);
        }

        sipStack.stop();
        sipStack = null;
    }

    public void testDialogFailover() throws Exception {

        balancer = new Balancer(IP_ADDRESS, BALANCER_PORT);

        balancer.start();

        shootist = new Shootist(true);

        shootme = new Shootme("shootme", 5070, true);

        shootmeRecoveryNode = new Shootme("shootme_recovery", 5080, true);

        shootme.init();
        shootmeRecoveryNode.init();        

        Thread.sleep(5000);

        shootist.init();
        
        Thread.sleep(10000);
        
        shootist.stop();
        shootmeRecoveryNode.stop();
        stopSipStack(balancer.sipStack, balancer);
        
        assertTrue(shootist.okToByeReceived);


    }
}

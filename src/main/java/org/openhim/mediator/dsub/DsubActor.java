package org.openhim.mediator.dsub;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.http.HttpStatus;
import org.oasis_open.docs.wsn.b_2.CreatePullPoint;
import org.oasis_open.docs.wsn.b_2.DestroyPullPoint;
import org.oasis_open.docs.wsn.b_2.GetCurrentMessage;
import org.oasis_open.docs.wsn.b_2.GetMessages;
import org.oasis_open.docs.wsn.b_2.Notify;
import org.oasis_open.docs.wsn.b_2.PauseSubscription;
import org.oasis_open.docs.wsn.b_2.Renew;
import org.oasis_open.docs.wsn.b_2.ResumeSubscription;
import org.oasis_open.docs.wsn.b_2.Subscribe;
import org.oasis_open.docs.wsn.b_2.Unsubscribe;
import org.openhim.mediator.dsub.pull.PullPointFactory;
import org.openhim.mediator.dsub.service.DsubService;
import org.openhim.mediator.dsub.service.DsubServiceImpl;
import org.openhim.mediator.dsub.subscription.MongoSubscriptionRepository;
import org.openhim.mediator.dsub.subscription.SoapSubscriptionNotifier;
import org.openhim.mediator.dsub.subscription.SubscriptionNotifier;
import org.openhim.mediator.dsub.subscription.SubscriptionRepository;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;
import org.openhim.mediator.messages.NotifyNewDocument;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.ws.wsaddressing.W3CEndpointReference;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DsubActor extends UntypedActor {

    private final MediatorConfig config;
    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    private final MongoDatabase mongoDb;

    private DsubService dsubService;
    private ActorRef requestHandler;

    public DsubActor(MediatorConfig config) {
        this.config = config;
        String host = config.getProperty("mediator.mongo.host");
        if (host == null) {
            throw new RuntimeException("The property mediator.mongo.host is not set!");
        }
        Integer port = Integer.parseInt(config.getProperty
                ("mediator.mongo.port"));
        MongoClient mongoClient = new MongoClient(host, port);
        mongoDb = mongoClient.getDatabase("dsub");

        PullPointFactory pullPointFactory = new PullPointFactory(mongoDb);
        SubscriptionRepository subRepo = new MongoSubscriptionRepository(mongoDb, log);
        SubscriptionNotifier subNotifier = new SoapSubscriptionNotifier(config, log);

        dsubService = new DsubServiceImpl(pullPointFactory, subRepo,
                subNotifier, log);
    }

    @Override
    public void onReceive(Object msg) {
        if (msg instanceof MediatorHTTPRequest) {
            handleMessage((MediatorHTTPRequest) msg);
        } else if (msg instanceof NotifyNewDocument) {
            NotifyNewDocument notifyNewDocument = (NotifyNewDocument) msg;
            dsubService.notifyNewDocument(notifyNewDocument.getLabOrderDocumentId(), null);
        }
    }

    private void handleMessage(MediatorHTTPRequest request) {
        requestHandler = request.getRequestHandler();

        Object result = parseMessage(request);

        if (result instanceof Subscribe) {
            Subscribe subscribeRequest = (Subscribe) result;
            try {
                handleSubscriptionMessage(subscribeRequest);
                MediatorHTTPResponse creationSuccess = new MediatorHTTPResponse(request,
                        "Subscription created with success",
                        HttpStatus.SC_CREATED,
                        null);
                requestHandler.tell(creationSuccess.toFinishRequest(), getSelf());
            } catch (RuntimeException ex) {
                MediatorHTTPResponse creationFailure = new MediatorHTTPResponse(request,
                        "Subscription not created: " + ex.getMessage(),
                        HttpStatus.SC_NOT_ACCEPTABLE,
                        null);
                requestHandler.tell(creationFailure.toFinishRequest(), getSelf());
            }
        } else if (result instanceof Unsubscribe) {
            Unsubscribe unsubscribeRequest = (Unsubscribe) result;
            //unsubscribe request handling
        } else if (result instanceof GetCurrentMessage) {
            GetCurrentMessage getCurrentMessageRequest = (GetCurrentMessage) result;
            //get current message request handling
        } else if (result instanceof GetMessages) {
            GetMessages getMessagesRequest = (GetMessages) result;
            //get messages request handling
        } else if (result instanceof DestroyPullPoint) {
            DestroyPullPoint destroyPullPointRequest = (DestroyPullPoint) result;
            //destroy pull point request handling
        } else if (result instanceof CreatePullPoint) {
            CreatePullPoint createPullPointRequest = (CreatePullPoint) result;
            //create pull point request handling
        } else if (result instanceof Renew) {
            Renew renew = (Renew) result;
            //renew request handling
        } else if (result instanceof PauseSubscription) {
            PauseSubscription pauseSubscriptionRequest = (PauseSubscription) result;
            //pause subscription request handling
        } else if (result instanceof ResumeSubscription) {
            ResumeSubscription resumeSubscriptionRequest = (ResumeSubscription) result;
            //resume subscription request handling
        } else if (result instanceof Notify) {
            Notify notifyRequest = (Notify) result;
            //notify request handling
        } else {
            //unknown message type handling
            MediatorHTTPResponse unknownMessageError = new MediatorHTTPResponse(request,
                    "Unknown message type error",
                    HttpStatus.SC_BAD_REQUEST,
                    null);
            requestHandler.tell(unknownMessageError.toFinishRequest(), getSelf());
        }
    }

    private void handleSubscriptionMessage(Subscribe subscribeRequest) throws RuntimeException{
        W3CEndpointReference consumerRef = subscribeRequest.getConsumerReference();
        Object address = getProperty(consumerRef, "address");

        String uri = getProperty(address, "uri");
        JAXBElement<String> termination = subscribeRequest.getInitialTerminationTime();

        Date terminationDate = null;
        if (termination != null && !termination.isNil()) {
            String val = termination.getValue();
            if (StringUtils.isNotBlank(val)) {
                try {
                    terminationDate = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSXXX").parse(val);
                } catch (ParseException e) {
                    throw new RuntimeException("Unable to parse the date", e);
                }
            }
        }
        if (parseUrl(uri) != null) {
            dsubService.createSubscription(uri, null, terminationDate);
        } else {
            log.error("Subscription not registered. Invalid url: " + uri);
        }  
    }

    private Object parseMessage(MediatorHTTPRequest request) {
        Object result;
        try {
            String parsedRequest = DsubUtil.parseRequest(request);
            result = DsubUtil.extractRequestMessage(parsedRequest);
        } catch (ParserConfigurationException | SAXException | IOException | JAXBException | TransformerException e) {
            log.error(e, "Parsing Dsub request failure");
            result = null;
        }
        return result;
    }

    private <T> T getProperty(Object object, String name) {
        try {
            Field field = FieldUtils.getField(object.getClass(), name, true);
            return (T) field.get(object);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Unable to read field: " + name, e);
        }
    }

    private URL parseUrl(String url) {
        try {
            URI uri = new URL(url).toURI();
            return uri.toURL();
        }    
        catch (Exception e) {
            return null;
        }
    }
}

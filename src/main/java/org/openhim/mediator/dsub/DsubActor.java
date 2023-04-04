package org.openhim.mediator.dsub;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.ws.wsaddressing.W3CEndpointReference;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.http.HttpStatus;
import org.oasis_open.docs.wsn.b_2.CreatePullPoint;
import org.oasis_open.docs.wsn.b_2.DestroyPullPoint;
import org.oasis_open.docs.wsn.b_2.GetCurrentMessage;
import org.oasis_open.docs.wsn.b_2.GetMessages;
import org.oasis_open.docs.wsn.b_2.GetMessagesResponse;
import org.oasis_open.docs.wsn.b_2.NotificationMessageHolderType;
import org.oasis_open.docs.wsn.b_2.Notify;
import org.oasis_open.docs.wsn.b_2.PauseSubscription;
import org.oasis_open.docs.wsn.b_2.Renew;
import org.oasis_open.docs.wsn.b_2.ResumeSubscription;
import org.oasis_open.docs.wsn.b_2.Subscribe;
import org.oasis_open.docs.wsn.b_2.Unsubscribe;
import org.openhim.mediator.Util;
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

import com.mongodb.MongoClient;
import com.mongodb.client.MongoDatabase;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

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
		Integer port = Integer.parseInt(config.getProperty("mediator.mongo.port"));
		MongoClient mongoClient = new MongoClient(host, port);
		mongoDb = mongoClient.getDatabase("dsub");
		
		PullPointFactory pullPointFactory = new PullPointFactory(mongoDb);
		SubscriptionRepository subRepo = new MongoSubscriptionRepository(mongoDb, log);
		SubscriptionNotifier subNotifier = new SoapSubscriptionNotifier(config, log);
		
		dsubService = new DsubServiceImpl(pullPointFactory, subRepo, subNotifier, log);
	}
	
	@Override
	public void onReceive(Object msg) throws JAXBException {
		if (msg instanceof MediatorHTTPRequest) {
			handleMessage((MediatorHTTPRequest) msg);
		} else if (msg instanceof NotifyNewDocument) {
			NotifyNewDocument notifyNewDocument = (NotifyNewDocument) msg;
			log.debug("Notifying new document");
			dsubService.notifyNewDocument(notifyNewDocument.getLabOrderDocumentId(), null);
		} else if (msg instanceof CreatePullPoint) {
			CreatePullPoint createPullPointRequest = (CreatePullPoint) msg;
			//create pull point request handling
			handleCreatePullPointRequest(createPullPointRequest);
		}
	}
	
	private void handleMessage(MediatorHTTPRequest request) throws JAXBException {
		requestHandler = request.getRequestHandler();
		
		Object result = parseMessage(request);
		
		if (result instanceof Subscribe) {
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
		} else if (result instanceof Unsubscribe) {} else if (result instanceof GetCurrentMessage) {} else if (result instanceof GetMessages) {
			GetMessages getMessagesRequest = (GetMessages) result;
			//get messages request handling
			String messages = handleGetMessages(getMessagesRequest);
			MediatorHTTPResponse getMessagesSuccess = new MediatorHTTPResponse(request, messages, HttpStatus.SC_ACCEPTED,
			        null);
			requestHandler.tell(getMessagesSuccess.toFinishRequest(), getSelf());
			
		} else if (result instanceof CreatePullPoint) {} else if (result instanceof CreatePullPoint) {
			CreatePullPoint createPullPointRequest = (CreatePullPoint) result;
			//create pull point request handling
			log.debug("CreatePullPoint request handling");
			handleCreatePullPointRequest(createPullPointRequest);
			MediatorHTTPResponse creationSuccess = new MediatorHTTPResponse(request, "Pull point created with success",
			        HttpStatus.SC_CREATED, null);
			requestHandler.tell(creationSuccess.toFinishRequest(), getSelf());
			
		} else if (result instanceof Renew) {} else if (result instanceof PauseSubscription) {} else if (result instanceof ResumeSubscription) {} else if (result instanceof Notify) {} else {
			//unknown message type handling
			MediatorHTTPResponse unknownMessageError = new MediatorHTTPResponse(request, "Unknown message type error",
			        HttpStatus.SC_BAD_REQUEST, null);
			requestHandler.tell(unknownMessageError.toFinishRequest(), getSelf());
		}
	}
	
	private void handleCreatePullPointRequest(CreatePullPoint createPullPointRequest) {
		String docId = createPullPointRequest.getAny().get(0).toString();
		String hl7ORU_01 = createPullPointRequest.getOtherAttributes().get(new QName("hl7ORU_01"));
		String facilityId = createPullPointRequest.getOtherAttributes().get(new QName("facility"));
		log.info("Handling Create PullPoint for Doc {}", docId);
		log.debug("HL7 ORU_01 Message {}", hl7ORU_01);
		log.debug("Facility Id Message {}", facilityId);
		dsubService.newDocumentForPullPoint(createPullPointRequest);
	}
	
	private String handleGetMessages(GetMessages getMessagesRequest) throws JAXBException {
		Integer max = getMessagesRequest.getMaximumNumber().intValue();
		Map<QName, String> otherAttrs = getMessagesRequest.getOtherAttributes();
		String facilityId = otherAttrs.get(new QName("facility"));
		
		// change the null to "facilityId" Update this on the xds sender module
		List<NotificationMessageHolderType> messages = dsubService.getDocumentsForPullPoint(facilityId, max);;
		
		GetMessagesResponse response = new GetMessagesResponse();
		response.getNotificationMessage().addAll(messages);
		
		/*
		 *  To remove commented out code
		 *
		// messages = new Gson().toJson(documentsForPullPoints);
		// JAXBElement<GetMessagesResponse> getMessagesResponse = new ObjectFactory().createGetMessagesResponse();
		//GetMessagesResponse  getMessagesResponse = new ObjectFactory().createGetMessagesResponse();
		/*	for (String documentId : documentsForPullPoints) {
			Message m = new Message();
			m.setAny(documentId);
			NotificationMessageHolderType e = new NotificationMessageHolderType();
			e.setMessage(m);
			
		*
		*/
		
		// log.error("Get response object " + new Gson().toJson(response));
		
		try {
			
			JAXBElement<GetMessagesResponse> jaxbElement = new JAXBElement<GetMessagesResponse>(
			        new QName("GetMessagesResponse"), GetMessagesResponse.class, response);
			
			// log.error("Convert to JAXB element " + new Gson().toJson(jaxbElement));
			
			return Util.marshallJAXBObject("org.oasis_open.docs.wsn.b_2", jaxbElement.getValue(), true);
			
		}
		catch (Exception ex) {
			log.error(ex, "");
			
			throw new RuntimeException("Unable to get messages", ex);
		}
		
	}
	
	private void handleSubscriptionMessage(Subscribe subscribeRequest) {
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
				}
				catch (ParseException e) {
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
		}
		catch (ParserConfigurationException | SAXException | IOException | JAXBException | TransformerException e) {
			log.error(e, "Parsing Dsub request failure");
			result = null;
		}
		return result;
	}
	
	private <T> T getProperty(Object object, String name) {
		try {
			Field field = FieldUtils.getField(object.getClass(), name, true);
			return (T) field.get(object);
		}
		catch (IllegalAccessException e) {
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

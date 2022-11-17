/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.denormalization;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v25.message.QBP_Q21;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.util.Terser;
import org.apache.http.HttpStatus;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.*;
import org.openhim.mediator.messages.RegisterNewPatient;
import org.openhim.mediator.messages.RegisterNewPatientResponse;
import org.openhim.mediator.messages.ResolvePatientIdentifier;
import org.openhim.mediator.messages.ResolvePatientIdentifierResponse;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Actor for processing FHIR messages.
 * <br/><br/>
 * Supports identifier cross-referencing requests (QBP_Q21) and Patient Identity Feed (ADT_A04).
 * <br/><br/>
 * Functions supported:
 * <ul>
 * <li>ResolvePatientIdentifier - responds with ResolvePatientIdentifierResponse. The identifier returned will be null if the id could not be resolved.</li>
 * <li>RegisterNewPatient - responds with RegisterNewPatientResponse</li>
 * </ul>
 */
public class FHIRRequestActor extends UntypedActor {

    private static final String IDENTIFIER_SYSTEM = "http://openclientregistry.org/fhir/sourceid";

    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private MediatorConfig config;

    private Map<String, MediatorRequestMessage> originalRequests = new HashMap<>();

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssZ");
    private static final SimpleDateFormat dateFormatDay = new SimpleDateFormat("yyyyMMdd");

    public FHIRRequestActor(MediatorConfig config) {
        this.config = config;
    }

    private IGenericClient getClient() {
        FhirContext ctx = FhirContext.forR4();
        IGenericClient client = ctx.newRestfulGenericClient(config.getProperty("fhir.mpiUrl"));
        client.setEncoding(EncodingEnum.JSON);
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);

        // Basic Auth
        String clientName = config.getProperty("fhir.mpiClientName");
        String password = config.getProperty("fhir.mpiPassword");
        client.registerInterceptor(new BasicAuthInterceptor(clientName, password));

        return client;
    }

    private void constructBasicMSHSegment(String correlationId, Terser t) throws HL7Exception {
        MSH msh = (MSH) t.getSegment("MSH");
        t.set("MSH-1", "|");
        t.set("MSH-2", "^~\\&");
        t.set("MSH-3-1", config.getProperty("pix.sendingApplication"));
        t.set("MSH-4-1", config.getProperty("pix.sendingFacility"));
        t.set("MSH-5-1", config.getProperty("pix.receivingApplication"));
        t.set("MSH-6-1", config.getProperty("pix.receivingFacility"));
        msh.getDateTimeOfMessage().getTime().setValue(dateFormat.format(new Date()));
        t.set("MSH-10", correlationId);
        t.set("MSH-11-1", "P");
        t.set("MSH-12-1-1", "2.5");
    }

    public String constructQBP_Q21(String correlationId, ResolvePatientIdentifier msg) throws HL7Exception {

        QBP_Q21 qbp_q21 = new QBP_Q21();
        Terser t = new Terser(qbp_q21);

        constructBasicMSHSegment(correlationId, t);
        t.set("MSH-9-1", "QBP");
        t.set("MSH-9-2", "Q23");
        t.set("MSH-9-3", "QBP_Q21");

        t.set("QPD-1-1", "IHE PIX Query");
        t.set("QPD-2", UUID.randomUUID().toString());
        //t.set("QPD-3-1", msg.getIdentifier().getIdentifier());
        t.set("QPD-3-1", "0075e090-0270-11eb-9c24-0242ac12000a");
        //t.set("QPD-3-4", msg.getIdentifier().getAssigningAuthority().getAssigningAuthority());
        t.set("QPD-3-4", "2.16.840.1.113883.4.56");
        //t.set("QPD-3-4-2", msg.getIdentifier().getAssigningAuthority().getAssigningAuthorityId());
        t.set("QPD-3-4-2", "2.16.840.1.113883.4.56");
        t.set("QPD-3-4-3", "NI");
        t.set("QPD-3-5", "PI");

        t.set("RCP-1", "I");

        Parser p = new GenericParser();
        return p.encode(qbp_q21);
    }

    private void sendPIXRequest(ResolvePatientIdentifier msg) {
        try {
            String correlationId = UUID.randomUUID().toString();
            String pixQuery = constructQBP_Q21(correlationId, msg);
            originalRequests.put(correlationId, msg);
            sendPIXRequest(msg.getRequestHandler(), "PIX Resolve Enterprise Identifier", correlationId, pixQuery);
        } catch (HL7Exception ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private void sendPIXRequest(ActorRef requestHandler, String orchestration, String correlationId, String pixRequest) {
        boolean secure = config.getProperty("pix.secure").equalsIgnoreCase("true");

        int port;
        if (secure) {
            port = Integer.parseInt(config.getProperty("pix.manager.securePort"));
        } else {
            port = Integer.parseInt(config.getProperty("pix.manager.port"));
        }

        ActorSelection connector = getContext().actorSelection(config.userPathFor("mllp-connector"));
        MediatorSocketRequest request = new MediatorSocketRequest(
                requestHandler, getSelf(), orchestration, correlationId,
                config.getProperty("pix.manager.host"), port, pixRequest, secure
        );
        connector.tell(request, getSelf());
    }

    private void sendFHIRRequest(ResolvePatientIdentifier msg) {
            String correlationId = UUID.randomUUID().toString();
            ActorRef requestHandler = msg.getRespondTo();
            String orchestration = "FHIR Resolve Patient Identifier";

            Map<String, String> headers = new HashMap<>();
            headers.put("accept", config.getProperty("fhir.mpiHeaderAccept"));
            headers.put("authorization", config.getProperty("fhir.mpiHeaderAuthorization"));

            originalRequests.put(correlationId, msg);
            // TODO: Use mediator properties instead of hard-coded values
            log.info("Setting up http request to FHIR server");
            MediatorHTTPRequest request = new MediatorHTTPRequest(
                    requestHandler, getSelf(), orchestration, "GET", "http",
                    config.getProperty("fhir.mpiBaseUrl"), Integer.parseInt(config.getProperty("fhir.mpiPort")), "fhir/Patient", (String) null, headers,  null
            );

            ActorSelection httpConnector = getContext().actorSelection(config.userPathFor("http-connector"));
            log.info("Sending http request to FHIR server");
            // httpConnector.tell(request, getSelf());

    }

    private void sendFHIRRequest(RegisterNewPatient msg) {
        try {
            String correlationId = UUID.randomUUID().toString();

            org.hl7.fhir.r4.model.Patient admitMessage = msg.getFhirResource();
            IGenericClient client = getClient();
            MethodOutcome result = client.create().resource(admitMessage).execute();
            msg.getRequestHandler()
                    .tell(new RegisterNewPatientResponse(msg, result.getOperationOutcome() == null, ""), getSelf());

        }
        catch (FHIRException ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private void processResponse(MediatorSocketResponse msg) {
        MediatorRequestMessage originalRequest = originalRequests.remove(msg.getOriginalRequest().getCorrelationId());

        if (originalRequest instanceof ResolvePatientIdentifier) {
            processResolveIdentifierResponse(msg, (ResolvePatientIdentifier) originalRequest);
        } else if (originalRequest instanceof RegisterNewPatient) {
           //TODO: Add this method: To register patients
            // processRegisterPatientResponse(msg, (RegisterNewPatient) originalRequest);
        }
    }

    private void processResolveIdentifierResponse(MediatorSocketResponse msg, ResolvePatientIdentifier originalRequest) {
    try {
        // Instantiate a fhir client and send the request
        IGenericClient client = getClient();

        String patientId = originalRequest.getIdentifier().getIdentifier();
        String assigningAuthority = originalRequest.getIdentifier().getAssigningAuthority().getAssigningAuthority();
        String assigningAuthorityId = originalRequest.getIdentifier().getAssigningAuthority().getAssigningAuthorityId();

        Bundle bundle = (Bundle) client.search().forResource(Patient.class)
                .where(new TokenClientParam("identifier").exactly().systemAndCode(assigningAuthority, assigningAuthorityId + "/" + patientId))
                .execute();
        org.hl7.fhir.r4.model.Patient patientResource = null;
        for (Bundle.BundleEntryComponent result : bundle.getEntry()) {
            patientResource = (org.hl7.fhir.r4.model.Patient) result.getResource();
        }


        // TODO: Figure out the how to exclude the unique external identifier from the validation process
        String id = "";
        for (org.hl7.fhir.r4.model.Identifier identifier: patientResource.getIdentifier()) {
            String identifierSystem = identifier.getSystem();
            if ( identifierSystem != null && identifier.getSystem().equals(assigningAuthority)) {
                id = identifier.getValue();
                break;
            }
        }

        Identifier result = new Identifier(id, originalRequest.getIdentifier().getAssigningAuthority());
        // Identifier result = originalRequest.getIdentifier();

        log.info("Sending Patient Identifier Response");
        originalRequest.getRespondTo().tell(new ResolvePatientIdentifierResponse(originalRequest, result), getSelf());
    } catch (FHIRException ex) {
        originalRequest.getRequestHandler().tell(new ExceptError(ex), getSelf());
    } catch (Exception ex) {
        originalRequest.getRequestHandler().tell(new ExceptError(ex), getSelf());
        log.error(ex.getMessage());
        ex.printStackTrace();

    }

    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof ResolvePatientIdentifier) {
            log.info("Received request to resolve patient identifier in the '" + ((ResolvePatientIdentifier) msg).getIdentifier().getAssigningAuthority().getAssigningAuthority() + "' domain(Assigning authority)");
            log.info("Received request to resolve patient identifier in the '" + ((ResolvePatientIdentifier) msg).getIdentifier().getAssigningAuthority().getAssigningAuthorityId() + "' domain(Assigning authority Id)");
            log.info("Received request to resolve patient identifier in the '" + ((ResolvePatientIdentifier) msg).getIdentifier().getAssigningAuthority().getAssigningAuthorityIdType() + "' domain(Assigning authority type)");
            if (log.isDebugEnabled()) {
                log.debug("Patient ID: " + ((ResolvePatientIdentifier) msg).getIdentifier());
            }
            FinishRequest response = new FinishRequest("A message from my new mediator!", "text/plain", HttpStatus.SC_OK);
            ((ResolvePatientIdentifier) msg).getRequestHandler().tell(response, getSelf());
        } else if (msg instanceof RegisterNewPatient) {
            log.info("Received request to register new patient demographic record");
            sendFHIRRequest((RegisterNewPatient) msg);
        } else if (msg instanceof MediatorSocketResponse) {
            log.info("Error: An unhandled response type");
            processResponse((MediatorSocketResponse) msg);
        } else {
            unhandled(msg);
        }
    }
}

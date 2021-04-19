/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.denormalization;

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
import ca.uhn.hl7v2.model.v25.message.ACK;
import ca.uhn.hl7v2.model.v25.message.RSP_K23;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.parser.Parser;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.MediatorRequestMessage;
import org.openhim.mediator.engine.messages.MediatorSocketRequest;
import org.openhim.mediator.engine.messages.MediatorSocketResponse;
import org.openhim.mediator.messages.*;

import java.text.SimpleDateFormat;
import java.util.Collections;
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

    private static final String IDENTIFIER_SYSTEM = "urn:ietf:rfc:3986";

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
        String password = config.getProperty("fhir.mpiClientName");
        String clientName = config.getProperty("fhir.mpiPassword");
        client.registerInterceptor(new BasicAuthInterceptor(clientName, password));

        return client;
    }

    private void sendFHIRRequest(ResolvePatientIdentifier msg) {
        try {
            String correlationId = UUID.randomUUID().toString();

            // TODO: generate FHIR query string
            // Instantiate a fhir client and send the request
            IGenericClient client = getClient();

            String patientId = msg.getIdentifier().getIdentifier(); // extractPatientId();
            String identifierSystem = msg.getTargetAssigningAuthority().getAssigningAuthority(); // "urn:ietf:rfc:3986";

            Bundle bundle = (Bundle) client.search().forResource(Patient.class)
                    .where(new TokenClientParam("identifier").exactly().systemAndCode(identifierSystem, patientId))
                    .execute();
            org.hl7.fhir.r4.model.Patient patientResource = null;
            for (Bundle.BundleEntryComponent result : bundle.getEntry()) {
                patientResource = (org.hl7.fhir.r4.model.Patient) result.getResource();
            }

            String id = "";
            for (org.hl7.fhir.r4.model.Identifier identifier: patientResource.getIdentifier()) {
                if (identifier.getSystem().equals(IDENTIFIER_SYSTEM)) {
                    id = identifier.getValue();
                    break;
                }
            }

            Identifier result = new Identifier(id, new AssigningAuthority());
            log.info("Sending Patient Identifier Response");
            msg.getRequestHandler().tell(new ResolvePatientIdentifierResponse(msg, result), getSelf());

        } catch (FHIRException ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private void sendFHIRRequest(RegisterNewPatient msg) {
        try {
            String correlationId = UUID.randomUUID().toString();

            org.hl7.fhir.r4.model.Patient admitMessage = msg.getFhirResource();
            IGenericClient client = getClient();
            MethodOutcome result = client.create().resource(admitMessage).execute();
            msg.getRequestHandler().tell(new RegisterNewPatientResponse(msg, err == null, err), getSelf());

        } catch (FHIRException ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private Identifier parseRSP_K23(String response) throws HL7Exception {
        Parser parser = new GenericParser();
        Object parsedMsg = parser.parse(response);
        if (!(parsedMsg instanceof RSP_K23)) {
            return null;
        }

        RSP_K23 msg = (RSP_K23)parsedMsg;

        int numIds = msg.getQUERY_RESPONSE().getPID().getPid3_PatientIdentifierListReps();
        if (numIds < 1) {
            return null;
        }

        String id = msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getCx1_IDNumber().getValue();

        String assigningAuthority = null;
        if (msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getAssigningAuthority().getNamespaceID()!=null) {
            assigningAuthority = msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getAssigningAuthority().getNamespaceID().getValue();
        }

        String assigningAuthorityId = null;
        if (msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalID()!=null) {
            assigningAuthorityId = msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalID().getValue();
        }

        String assigningAuthorityIdType = null;
        if (msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalIDType()!=null) {
            assigningAuthorityIdType = msg.getQUERY_RESPONSE().getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalIDType().getValue();
        }

        return new Identifier(id, new AssigningAuthority(assigningAuthority, assigningAuthorityId, assigningAuthorityIdType));
    }

    private void processQBP_Q21Response(MediatorSocketResponse msg, ResolvePatientIdentifier originalRequest) {
        Identifier result = null;
        try {
            result = parseRSP_K23(msg.getBody());
            originalRequest.getRespondTo().tell(new ResolvePatientIdentifierResponse(originalRequest, result), getSelf());
        } catch (HL7Exception ex) {
            msg.getOriginalRequest().getRequestHandler().tell(new ExceptError(ex), getSelf());
        } finally {
            sendAuditMessage(ATNAAudit.TYPE.PIX_REQUEST, result, msg, result!=null);
        }
    }

    private String parseACKError(String response) throws HL7Exception {
        Parser parser = new GenericParser();
        Object parsedMsg = parser.parse(response);
        if (!(parsedMsg instanceof ACK)) {
            return "Message response received in unsupported format: " + parsedMsg.getClass();
        }

        ACK msg = (ACK)parsedMsg;
        if (msg.getMSA()!=null && msg.getMSA().getAcknowledgmentCode()!=null &&
                "AA".equalsIgnoreCase(msg.getMSA().getAcknowledgmentCode().getValue())) {
            return null;
        }

        String err = "Failed to register new patient:\n";

        if (msg.getERR()!=null && msg.getERR().getErr3_HL7ErrorCode()!=null) {
            if (msg.getERR().getErr3_HL7ErrorCode().getCwe1_Identifier()!=null) {
                err += msg.getERR().getErr3_HL7ErrorCode().getCwe1_Identifier().getValue() + "\n";
            }
            if (msg.getERR().getErr3_HL7ErrorCode().getCwe2_Text()!=null) {
                err += msg.getERR().getErr3_HL7ErrorCode().getCwe2_Text().getValue() + "\n";
            }
        }

        return err;
    }

    private void processADT_A04Response(MediatorSocketResponse msg, RegisterNewPatient originalRequest) {
        String err = null;
        try {
            err = parseACKError(msg.getBody());
            originalRequest.getRespondTo().tell(new RegisterNewPatientResponse(originalRequest, err == null, err), getSelf());
        } catch (HL7Exception ex) {
            msg.getOriginalRequest().getRequestHandler().tell(new ExceptError(ex), getSelf());
        } finally {
            Identifier pid = originalRequest.getPatientIdentifiers().get(0);
            sendAuditMessage(ATNAAudit.TYPE.PIX_IDENTITY_FEED, pid, msg, err==null);
        }
    }

    private void processResponse(MediatorSocketResponse msg) {
        MediatorRequestMessage originalRequest = originalRequests.remove(msg.getOriginalRequest().getCorrelationId());

        if (originalRequest instanceof ResolvePatientIdentifier) {
            processQBP_Q21Response(msg, (ResolvePatientIdentifier) originalRequest);
        } else if (originalRequest instanceof RegisterNewPatient) {
            processADT_A04Response(msg, (RegisterNewPatient) originalRequest);
        }
    }

    private void sendAuditMessage(ATNAAudit.TYPE type, Identifier patientID, MediatorSocketResponse msg, boolean outcome) {
        try {
            ATNAAudit audit = new ATNAAudit(type);
            audit.setMessage(((MediatorSocketRequest) msg.getOriginalRequest()).getBody());
            audit.setParticipantIdentifiers(Collections.singletonList(patientID));
            audit.setUniqueId(msg.getOriginalRequest().getCorrelationId());
            audit.setOutcome(outcome);

            getContext().actorSelection(config.userPathFor("atna-auditing")).tell(audit, getSelf());
        } catch (Exception ex) {
            //quiet you!
        }
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof ResolvePatientIdentifier) {
            log.info("Received request to resolve patient identifier in the '" + ((ResolvePatientIdentifier) msg).getTargetAssigningAuthority() + "' domain");
            if (log.isDebugEnabled()) {
                log.debug("Patient ID: " + ((ResolvePatientIdentifier) msg).getIdentifier());
            }
            sendFHIRRequest((ResolvePatientIdentifier) msg);
        } else if (msg instanceof RegisterNewPatient) {
            log.info("Received request to register new patient demographic record");
            sendFHIRRequest((RegisterNewPatient) msg);
        } else if (msg instanceof MediatorSocketResponse) {
            log.info("Error: An unhandled response type");
            // processResponse((MediatorSocketResponse) msg);
        } else {
            unhandled(msg);
        }
    }
}

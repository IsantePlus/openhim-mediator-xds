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
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.MediatorRequestMessage;
import org.openhim.mediator.engine.messages.MediatorSocketResponse;
import org.openhim.mediator.messages.RegisterNewPatient;
import org.openhim.mediator.messages.RegisterNewPatientResponse;
import org.openhim.mediator.messages.ResolvePatientIdentifier;
import org.openhim.mediator.messages.ResolvePatientIdentifierResponse;

import java.text.SimpleDateFormat;
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

            // String id = patientId;

            Identifier result = new Identifier(id, msg.getTargetAssigningAuthority());
            log.info("Sending Patient Identifier Response");
            msg.getRespondTo().tell(new ResolvePatientIdentifierResponse(msg, result), getSelf());
            // msg.getRequestHandler().tell(new ResolvePatientIdentifierResponse(msg, result), getSelf());

        } catch (FHIRException ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
            // msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        } catch (Exception ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
            // msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
            log.error(ex.getMessage());
            ex.printStackTrace();

        }
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

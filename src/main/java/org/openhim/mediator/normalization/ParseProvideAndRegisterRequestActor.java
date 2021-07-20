/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.normalization;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;
import oasis.names.tc.ebxml_regrep.xsd.rim._3.AssociationType1;
import oasis.names.tc.ebxml_regrep.xsd.rim._3.ExtrinsicObjectType;
import oasis.names.tc.ebxml_regrep.xsd.rim._3.IdentifiableType;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.openhim.mediator.engine.CoreResponse;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.AddOrchestrationToCoreResponse;
import org.openhim.mediator.engine.messages.FinishRequest;
import org.openhim.mediator.engine.messages.SimpleMediatorRequest;
import org.openhim.mediator.engine.messages.SimpleMediatorResponse;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.util.Iterator;

/**
 * Parses XDS.b Provide and Register Document Set transactions.
 * <br/><br/>
 * Messages supported:
 * <ul>
 *     <li>SimpleMediatorRequest<String> - responds with SimpleMediatorResponse<ProvideAndRegisterDocumentSetRequestType></li>
 * </ul>
 */
public class ParseProvideAndRegisterRequestActor extends UntypedActor {

    private MediatorConfig config;

    public ParseProvideAndRegisterRequestActor() {
    }

    public ParseProvideAndRegisterRequestActor(MediatorConfig config) {
        this.config = config;
    }


    public static ProvideAndRegisterDocumentSetRequestType parseRequest(String document) throws JAXBException {
        ProvideAndRegisterDocumentSetRequestType provideAndRegisterDocumentSetRequestType = removeFhirMetadata(
                document);

        return provideAndRegisterDocumentSetRequestType;
    }

    private static ProvideAndRegisterDocumentSetRequestType removeFhirMetadata(String document)
            throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance("ihe.iti.xds_b._2007");
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        JAXBElement result = (JAXBElement) (unmarshaller.unmarshal(IOUtils.toInputStream(document)));
        ProvideAndRegisterDocumentSetRequestType provideAndRegisterDocumentSetRequestType = (ProvideAndRegisterDocumentSetRequestType) result
                .getValue();

        String idToRemove = getIdToRemove(provideAndRegisterDocumentSetRequestType);
        for (Iterator<JAXBElement<? extends IdentifiableType>> object = provideAndRegisterDocumentSetRequestType
                .getSubmitObjectsRequest()
                .getRegistryObjectList().getIdentifiable().iterator(); object.hasNext();) {

            JAXBElement jaxbElement = object.next();
            IdentifiableType identifiable = (IdentifiableType) jaxbElement.getValue();

            // Remove ExtrinsicObjects
            if (identifiable instanceof ExtrinsicObjectType && identifiable.getId().equals(idToRemove)) {
                object.remove();
            }

            // Remove associations
            if (identifiable instanceof AssociationType1) {
                AssociationType1 associationType1 = (AssociationType1) identifiable;
                if (associationType1.getTargetObject().equals(idToRemove)) {
                    object.remove();
                }
            }
        }

        // Remove document definitions
        provideAndRegisterDocumentSetRequestType.getDocument().removeIf(d -> d.getId().equals(idToRemove));

        return provideAndRegisterDocumentSetRequestType;
    }

    private static String getIdToRemove(ProvideAndRegisterDocumentSetRequestType provideAndRegisterDocumentSetRequestType1) {
        String idToRemove = "";
        for ( JAXBElement<? extends IdentifiableType> object : provideAndRegisterDocumentSetRequestType1.getSubmitObjectsRequest()
                .getRegistryObjectList().getIdentifiable()) {
            IdentifiableType identifiable = (IdentifiableType)((JAXBElement)object).getValue();

            if (identifiable instanceof ExtrinsicObjectType) {
                ExtrinsicObjectType id = (ExtrinsicObjectType) identifiable;
                if (id.getMimeType().equalsIgnoreCase("TEXT/FHIR")) {
                    idToRemove = identifiable.getId();
                    break;
                }
            }
        }

        return idToRemove;

    }

    private void processMsg(SimpleMediatorRequest<String> msg) {
        ActorRef requestHandler = msg.getRequestHandler();
        
        CoreResponse.Orchestration orch = null;
        boolean sendParseOrchestration = (config==null || config.getProperty("pnr.sendParseOrchestration")==null ||
                "true".equalsIgnoreCase(config.getProperty("pnr.sendOrchestration")));

        try {
            if (sendParseOrchestration) {
                orch = new CoreResponse.Orchestration();
                orch.setName("Parse Provider and Register Document Set.b contents");
                orch.setRequest(new CoreResponse.Request());
            }

            ProvideAndRegisterDocumentSetRequestType result = parseRequest(msg.getRequestObject());
            msg.getRespondTo().tell(new SimpleMediatorResponse<>(msg, result), getSelf());

            if (sendParseOrchestration) {
                orch.setResponse(new CoreResponse.Response());
                requestHandler.tell(new AddOrchestrationToCoreResponse(orch), getSelf());
            }

        } catch (JAXBException ex) {
            FinishRequest fr = new FinishRequest("Failed to parse XDS.b Provide and Register Document Set request: " + ex.getMessage(), "text/plain", HttpStatus.SC_BAD_REQUEST);
            requestHandler.tell(fr, getSelf());
        }
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (SimpleMediatorRequest.isInstanceOf(String.class, msg)) {
            processMsg((SimpleMediatorRequest<String>) msg);
        } else {
            unhandled(msg);
        }
    }
}

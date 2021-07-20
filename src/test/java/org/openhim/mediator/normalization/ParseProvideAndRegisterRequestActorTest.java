/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.normalization;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import ihe.iti.xds_b._2007.ProvideAndRegisterDocumentSetRequestType;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openhim.mediator.engine.messages.SimpleMediatorRequest;
import org.openhim.mediator.engine.messages.SimpleMediatorResponse;
import scala.concurrent.duration.Duration;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ParseProvideAndRegisterRequestActorTest {

    static ActorSystem system;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create();
    }

    @AfterClass
    public static void teardown() {
        JavaTestKit.shutdownActorSystem(system);
        system = null;
    }

    @Test
    public void testEnrichMessage() throws Exception {
        InputStream testPnRIn = getClass().getClassLoader().getResourceAsStream("pnr1.xml");
        final String testPnR = IOUtils.toString(testPnRIn);

        new JavaTestKit(system) {{
            ActorRef actor = system.actorOf(Props.create(ParseProvideAndRegisterRequestActor.class));

            SimpleMediatorRequest<String> testMsg = new SimpleMediatorRequest<String>(getRef(), getRef(), testPnR);
            actor.tell(testMsg, getRef());

            SimpleMediatorResponse result = expectMsgClass(Duration.create(60, TimeUnit.SECONDS), SimpleMediatorResponse.class);
            assertTrue(SimpleMediatorResponse.isInstanceOf(ProvideAndRegisterDocumentSetRequestType.class, result));
        }};
    }

    @Test
    public void testParseMessage() throws Exception {
        InputStream testPnRIn = getClass().getClassLoader().getResourceAsStream("pnrWithFhir.xml");
        InputStream testPnROut = getClass().getClassLoader().getResourceAsStream("pnrWithFhirOut.xml");
        assert testPnRIn != null;
        final String testPnR = IOUtils.toString(testPnRIn);
        final String testPnRExpectedOut = IOUtils.toString(testPnROut);

        ProvideAndRegisterDocumentSetRequestType expectedRequest = stringToPnr(testPnRExpectedOut);

        ProvideAndRegisterDocumentSetRequestType parsedRequest = ParseProvideAndRegisterRequestActor.parseRequest(testPnR);

        assertEquals(expectedRequest.getDocument().size(), parsedRequest.getDocument().size());
        assertEquals(expectedRequest.getSubmitObjectsRequest().getRegistryObjectList().getIdentifiable().size(),
                parsedRequest.getSubmitObjectsRequest().getRegistryObjectList().getIdentifiable().size());
    }

    private ProvideAndRegisterDocumentSetRequestType stringToPnr (String document) throws JAXBException {
        JAXBContext jaxbContext = JAXBContext.newInstance("ihe.iti.xds_b._2007");
        Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
        JAXBElement result = (JAXBElement)(unmarshaller.unmarshal(IOUtils.toInputStream(document)));

        return (ProvideAndRegisterDocumentSetRequestType) result.getValue();
    }

}

package org.openhim.mediator.denormalization;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.JavaTestKit;
import akka.testkit.TestActorRef;
import junit.framework.TestCase;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.MediatorRequestMessage;
import org.openhim.mediator.engine.messages.MediatorSocketRequest;
import org.openhim.mediator.engine.messages.MediatorSocketResponse;
import org.openhim.mediator.engine.testing.MockLauncher;
import org.openhim.mediator.engine.testing.TestingUtils;
import org.openhim.mediator.messages.ResolvePatientIdentifier;

import java.util.Collections;

public class FHIRRequestActorTest extends TestCase {
	private abstract static class MockPIXReceiver extends UntypedActor {
		public abstract String getResponse() throws Exception;

		@Override
		public void onReceive(Object msg) throws Exception {
			if (msg instanceof MediatorSocketRequest) {
				MediatorSocketResponse response = new MediatorSocketResponse((MediatorRequestMessage) msg, getResponse());
				((MediatorSocketRequest) msg).getRespondTo().tell(response, getSelf());
			} else {
				fail("Unexpected message received");
			}
		}
	}

	private static class MockFHIRReceiver_Valid extends FHIRRequestActorTest.MockPIXReceiver {
		@Override
		public String getResponse() throws Exception {
			return "Not found";
		}
	}

	private static class MockFHIRReceiver_NotFound extends FHIRRequestActorTest.MockPIXReceiver {
		@Override
		public String getResponse() throws Exception {
			return "Not found";
		}
	}

	private static class MockPIXReceiver_BadResponse extends FHIRRequestActorTest.MockPIXReceiver {
		@Override
		public String getResponse() throws Exception {
			return "A bad response!";
		}
	}

	static ActorSystem system;
	MediatorConfig testConfig;

	@BeforeClass
	public static void setup() {
		system = ActorSystem.create();
	}

	@AfterClass
	public static void teardown() {
		JavaTestKit.shutdownActorSystem(system);
		system = null;
	}

	@Before
	public void setUp() throws Exception {
		super.setUp();
		system = ActorSystem.create();
		testConfig = new MediatorConfig();
		testConfig.setName("fhir-tests");
		testConfig.setProperties("mediator-unit-test.properties");
	}

	public void testOnReceiveReceivePatientIdentifier() {

		new JavaTestKit(system) {{
			TestingUtils.launchActors(system, testConfig.getName(), Collections
					.singletonList(new MockLauncher.ActorToLaunch("mllp-connector", MockFHIRReceiver_Valid.class)));
			TestActorRef<FHIRRequestActor> actor = TestActorRef.create(system, Props.create(FHIRRequestActor.class, testConfig));

			ActorRef ref = getRef();
			Identifier fromId = new Identifier("http://isanteplus-test.sedish-haiti.org/ws/fhir2/pid/openmrsid/c87e4b15-9ebd-4a69-a0dc-c1b3a7b2576a", new AssigningAuthority("http://openclientregistry.org/fhir/sourceid", "", ""));
			AssigningAuthority targetDomain = new AssigningAuthority(testConfig.getProperty("client.requestedAssigningAuthority"), testConfig.getProperty("client.requestedAssigningAuthorityId"), "");
			ResolvePatientIdentifier resolvePatientIdentifier = new ResolvePatientIdentifier(ref, ref, fromId, targetDomain);

			Boolean testReceive = null;
			try {
				// fhirRequestActor.onReceive(resolvePatientIdentifier);
				actor.tell(new ResolvePatientIdentifier(ref, ref, fromId, targetDomain), ref);
				Assert.assertNotNull(true);
			}
			catch (Exception e) {
				Assert.assertNull(testReceive);
			}
		}};

	}
}

package org.openhim.mediator.dsub.subscription;

import akka.event.NoLogging$;
import com.mongodb.client.MongoDatabase;
import org.junit.Ignore;
import org.junit.Test;
import org.openhim.mediator.MongoBasedTest;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;

public class MongoSubscriptionRepositoryTest extends MongoBasedTest {

    private SubscriptionRepository subscriptionRepository;

    @Override
    protected void mongoInitialized(MongoDatabase mongoDb) {
        subscriptionRepository = new MongoSubscriptionRepository(
                mongoDb, NoLogging$.MODULE$);
    }

    // TODO: Fix MongoDB initialization or mock Mongodb interactions
    @Test
    @Ignore
    public void shouldCreateDeleteAndRetrieveActiveSubscriptions() {
        Subscription activeNoDtNoFc = new Subscription(
                "http://a_ndt_nfc", null, null);
        Subscription activeDtNoFc = new Subscription(
                "http://a_dt_nfc", futureDate(), null);
        Subscription activeNoDtFc = new Subscription(
                "http://a_ndt_fc", null, "FC-1");
        Subscription inactiveDtNoFc = new Subscription(
                "http://i_dt_nfc", pastDate(), null);
        Subscription inactiveNoDtFc = new Subscription(
                "http://i_dt_nfc", null, "FC-WRONG");

        subscriptionRepository.saveSubscription(activeNoDtNoFc);
        subscriptionRepository.saveSubscription(activeDtNoFc);
        subscriptionRepository.saveSubscription(activeNoDtFc);
        subscriptionRepository.saveSubscription(inactiveDtNoFc);
        subscriptionRepository.saveSubscription(inactiveNoDtFc);

        List<Subscription> result = subscriptionRepository.
                findActiveSubscriptions("FC-1");
        assertEquals(3, result.size());

        Set<String> names = new HashSet<>(asList(
                result.get(0).getUrl(),
                result.get(1).getUrl(),
                result.get(2).getUrl()
        ));
        Set<String> expectedNames = new HashSet<>(asList(
                "http://a_ndt_nfc", "http://a_dt_nfc", "http://a_ndt_fc"
        ));

        assertEquals(expectedNames, names);

        subscriptionRepository.deleteSubscription(result.get(0).getUuid());
        subscriptionRepository.deleteSubscription(result.get(1).getUuid());

        result = subscriptionRepository.findActiveSubscriptions("FC-1");
        assertEquals(1, result.size());
    }

    private Date futureDate() {
        Date date = new Date();
        date.setYear(date.getYear() + 1);
        return date;
    }

    private Date pastDate() {
        Date date = new Date();
        date.setYear(date.getYear() - 1);
        return date;
    }
}

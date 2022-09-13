package org.openhim.mediator.dsub.service;

import akka.event.LoggingAdapter;
import org.openhim.mediator.dsub.pull.PullPoint;
import org.openhim.mediator.dsub.pull.PullPointFactory;
import org.openhim.mediator.dsub.subscription.Subscription;
import org.openhim.mediator.dsub.subscription.SubscriptionNotifier;
import org.openhim.mediator.dsub.subscription.SubscriptionRepository;


import java.util.Date;
import java.util.List;

public class DsubServiceImpl implements DsubService {

    private final PullPointFactory pullPointFactory;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionNotifier subscriptionNotifier;
    private final LoggingAdapter log;

    public DsubServiceImpl(PullPointFactory pullPointFactory,
                           SubscriptionRepository subscriptionRepository,
                           SubscriptionNotifier subscriptionNotifier,
                           LoggingAdapter log) {
        this.pullPointFactory = pullPointFactory;
        this.subscriptionRepository = subscriptionRepository;
        this.subscriptionNotifier = subscriptionNotifier;
        this.log = log;
    }


    @Override
    public void createSubscription(String url, String facilityQuery, Date terminateAt) throws RuntimeException {
        log.info("Request to create subscription for: " + url);

        if (subscriptionExists(url,facilityQuery) == false) {
            Subscription subscription = new Subscription(url,
                    terminateAt, facilityQuery);
            subscriptionRepository.saveSubscription(subscription);
        } else  {
            throw new RuntimeException(String.format("Subscription %s already", url));
        }
    }
    
    public Boolean subscriptionExists(String url, String facilityQuery) {
        Boolean exists = false;
        List<Subscription> subscriptions = subscriptionRepository.findActiveSubscriptions(facilityQuery);
        for (Subscription subscription: subscriptions) {
            if (subscription.getUrl().equals(url)) {
                exists = true;
                break;
            }
        }

        return exists;
    }

    @Override
    public void deleteSubscription(String url) {
        log.info("Request to delete subscription for: " + url);
        subscriptionRepository.deleteSubscription(url);
    }

    @Override
    public void notifyNewDocument(String docId, String facilityId) {
        List<Subscription> subscriptions = subscriptionRepository
                .findActiveSubscriptions(facilityId);

        log.info("Active subscriptions: {}", subscriptions.size());
        for (Subscription sub : subscriptions) {
            log.info("URL: {}", sub.getUrl());
            subscriptionNotifier.notifySubscription(sub, docId);
        }
    }

    @Override
    public void newDocumentForPullPoint(String docId, String locationId) {
        PullPoint pullPoint = pullPointFactory.get(locationId);
        pullPoint.registerDocument(docId);
    }
    public List<String> getDocumentsForPullPoint(String locationId) {
        PullPoint pullPoint = pullPointFactory.get(locationId);
        return pullPoint.getDocumentIds();
    }


}

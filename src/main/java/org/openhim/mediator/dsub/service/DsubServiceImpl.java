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

        Subscription subscription = new Subscription(url,
                terminateAt, facilityQuery);

        if (subscriptionExists(url, facilityQuery) == false) {
            subscriptionRepository.saveSubscription(subscription);            
        } else {
            log.error("unable to create subscription. Another one already exists for: " + url);
        }
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

            try {
                subscriptionNotifier.notifySubscription(sub, docId);
            } catch (Exception ex) {
                log.error("Error occured while sending notification. Unable to notify subscriber: " + sub.getUrl());
            }
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

    @Override
    public Boolean subscriptionExists(String url, String facility) {
        Boolean subcriptionFound = false;
        List<Subscription> subscriptions = subscriptionRepository
                .findActiveSubscriptions(facility);

        log.info("Active subscriptions: {}", subscriptions.size());
        for (Subscription sub : subscriptions) {
            log.info("URL: {}", sub.getUrl());
            if (url.equals(sub.getUrl())) {
                subcriptionFound = true;
                break;
            }
        }

        return subcriptionFound;
    }
}

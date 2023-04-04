package org.openhim.mediator.dsub.service;

import java.util.Date;
import java.util.List;

public interface DsubService {

    void createSubscription(String url, String facilityQuery, Date terminateAt) throws RuntimeException;

    void deleteSubscription(String url);

    void notifyNewDocument(String docId, String facilityId);

    void newDocumentForPullPoint(String docId, String facilityId);

    List<String> getDocumentsForPullPoint(String facilityId);

    Boolean subscriptionExists(String url, String facility);
}

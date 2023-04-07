package org.openhim.mediator.dsub.service;

import java.util.Date;
import java.util.List;

import org.oasis_open.docs.wsn.b_2.CreatePullPoint;
import org.oasis_open.docs.wsn.b_2.NotificationMessageHolderType;

public interface DsubService {

    void createSubscription(String url, String facilityQuery, Date terminateAt) throws RuntimeException;

    void deleteSubscription(String url);

    void notifyNewDocument(String docId, String facilityId);

    void newDocumentForPullPoint(String docId, String facilityId);

	void newDocumentForPullPoint(CreatePullPoint createPullPointRequest);

    List<NotificationMessageHolderType> getDocumentsForPullPoint(String locationId, Integer max);
    
    Boolean subscriptionExists(String url, String facility);


}

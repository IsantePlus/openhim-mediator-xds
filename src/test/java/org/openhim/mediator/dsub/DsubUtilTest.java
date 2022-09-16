package org.openhim.mediator.dsub;

import org.junit.Assert;
import org.junit.Test;
import org.oasis_open.docs.wsn.b_2.Subscribe;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;


public class DsubUtilTest {

    private static String subscribeRequestBody = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"\n" +
            " xmlns:a=\"http://www.w3.org/2005/08/addressing\"\n" +
            " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
            " xmlns:wsnt=\"http://docs.oasis-open.org/wsn/b-2\"\n" +
            " xmlns:rim=\"urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0\"\n" +
            " xsi:schemaLocation=\"http://www.w3.org/2003/05/soap-envelope http://www.w3.org/2003/05/soapenvelope\n" +
            "http://www.w3.org/2005/08/addressing http://www.w3.org/2005/08/addressing/ws-addr.xsd\n" +
            "http://docs.oasis-open.org/wsn/b-2 http://docs.oasis-open.org/wsn/b-2.xsd\n" +
            "urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0 ../schema/ebRS/rim.xsd\">\n" +
            " <s:Header>\n" +
            " <a:Action>http://docs.oasis-open.org/wsn/bw2/NotificationProducer/SubscribeRequest</a:Action>\n" +
            " <a:MessageID>382dcdc7-8e84-9fdc-8443-48fd83bca938</a:MessageID>\n" +
            " <a:To s:mustUnderstand=\"1\">http://localhost:8080/services/initiatingGateway/query</a:To>\n" +
            " </s:Header>\n" +
            " <s:Body>\n" +
            " <wsnt:Subscribe>\n" +
            " <!-- The Recipient on whose behalf the subscription is requested " +
            "- the address where the notification is to be sent -->\n" +
            " <wsnt:ConsumerReference>\n" +
            " <a:Address>https://NotificationRecipientServer/xdsBnotification</a:Address>\n" +
            " </wsnt:ConsumerReference>\n" +
            " <wsnt:Filter>\n" +
            " <wsnt:TopicExpression Dialect=\"http://docs.oasis-open.org/wsn/t1/TopicExpression/Simple\">" +
            "ihe:MinimalDocumentEntry</wsnt:TopicExpression>\n" +
            " <rim:AdhocQuery id=\"urn:uuid:aa2332d0-f8fe-11e0-be50-0800200c9a66\">\n" +
            " <rim:Slot name=\"$XDSDocumentEntryPatientId\">\n" +
            " <rim:ValueList>\n" +
            "\n" +
            "<rim:Value>'st3498702^^^&amp;1.3.6.1.4.1.21367.2005.3.7&amp;ISO'</rim:Value>\n" +
            " </rim:ValueList>\n" +
            " </rim:Slot>\n" +
            "<rim:Slot name=\"$XDSDocumentEntryHealthcareFacilityTypeCode\">\n" +
            " <rim:ValueList>\n" +
            " <rim:Value>('EmergencyDepartment^^healthcareFacilityCodingScheme')</rim:Value>\n" +
            " </rim:ValueList>\n" +
            " </rim:Slot>\n" +
            "</rim:AdhocQuery>\n" +
            " </wsnt:Filter>\n" +
            " <wsnt:InitialTerminationTime>2010-05-31T00:00:00.00000Z</wsnt:InitialTerminationTime>\n" +
            " </wsnt:Subscribe>\n" +
            " </s:Body>\n" +
            "</s:Envelope>";

    private static String parsedMessage = "<wsnt:Subscribe xmlns:wsnt=\"http://docs.oasis-open.org/wsn/b-2\" " +
            "xmlns:a=\"http://www.w3.org/2005/08/addressing\" " +
            "xmlns:rim=\"urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0\" " +
            "xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
            "xsi:schemaLocation=\"http://www.w3.org/2003/05/soap-envelope " +
            "http://www.w3.org/2003/05/soapenvelope " +
            "http://www.w3.org/2005/08/addressing " +
            "http://www.w3.org/2005/08/addressing/ws-addr.xsd " +
            "http://docs.oasis-open.org/wsn/b-2 http://docs.oasis-open.org/wsn/b-2.xsd " +
            "urn:oasis:names:tc:ebxml-regrep:xsd:rim:3.0 ../schema/ebRS/rim.xsd\">\n" +
            " <!-- The Recipient on whose behalf the subscription is requested " +
            "- the address where the notification is to be sent -->\n" +
            " <wsnt:ConsumerReference>\n" +
            " <a:Address>https://NotificationRecipientServer/xdsBnotification</a:Address>\n" +
            " </wsnt:ConsumerReference>\n" +
            " <wsnt:Filter>\n" +
            " <wsnt:TopicExpression Dialect=\"http://docs.oasis-open.org/wsn/t1/TopicExpression/Simple\">" +
            "ihe:MinimalDocumentEntry</wsnt:TopicExpression>\n" +
            " <rim:AdhocQuery id=\"urn:uuid:aa2332d0-f8fe-11e0-be50-0800200c9a66\">\n" +
            " <rim:Slot name=\"$XDSDocumentEntryPatientId\">\n" +
            " <rim:ValueList>\n" +
            "\n" +
            "<rim:Value>'st3498702^^^&amp;1.3.6.1.4.1.21367.2005.3.7&amp;ISO'</rim:Value>\n" +
            " </rim:ValueList>\n" +
            " </rim:Slot>\n" +
            "<rim:Slot name=\"$XDSDocumentEntryHealthcareFacilityTypeCode\">\n" +
            " <rim:ValueList>\n" +
            " <rim:Value>('EmergencyDepartment^^healthcareFacilityCodingScheme')</rim:Value>\n" +
            " </rim:ValueList>\n" +
            " </rim:Slot>\n" +
            "</rim:AdhocQuery>\n" +
            " </wsnt:Filter>\n" +
            " <wsnt:InitialTerminationTime>2010-05-31T00:00:00.00000Z</wsnt:InitialTerminationTime>\n" +
            " </wsnt:Subscribe>\n";

    @Test
    public void extractRequestMessage() throws Exception {
        MediatorHTTPRequest request = new MediatorHTTPRequest(null, null, null, null,
                null, null, null, null, subscribeRequestBody, null, null);
        String result = DsubUtil.parseRequest(request);
        Assert.assertEquals(trimXML(parsedMessage), trimXML(result));
    }

    @Test
    public void parseRequest() throws Exception {
        Object result = DsubUtil.extractRequestMessage(parsedMessage);
        Assert.assertTrue(result instanceof Subscribe);
    }
    public static String trimXML(String xml) {
        return xml.replaceAll("\\s","").replaceAll(">\\s*<", "><");
    }
}

package tests.unit.com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.DeviceClientConfig;
import com.microsoft.azure.sdk.iot.device.MessageType;
import com.microsoft.azure.sdk.iot.device.ProductInfo;
import com.microsoft.azure.sdk.iot.device.transport.amqps.*;
import mockit.*;
import org.apache.qpid.proton.amqp.messaging.ApplicationProperties;
import org.apache.qpid.proton.amqp.messaging.Properties;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.message.impl.MessageImpl;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for AmqpsIotHubConnectionAuthenticationCBSTest
 * 100% methods covered
 * 92% lines covered
 */
public class AmqpsIotHubConnectionAuthenticationCBSTest
{
    private final String CBS_TO = "$cbs";
    private final String CBS_REPLY = "cbs";

    private final String OPERATION_KEY = "operation";
    private final String TYPE_KEY = "type";
    private final String NAME_KEY = "name";

    private final String OPERATION_VALUE = "put-token";
    private final String TYPE_VALUE = "servicebus.windows.net:sastoken";

    private final String DEVICES_PATH =  "/devices/";

    @Mocked
    DeviceClientConfig mockDeviceClientConfig;

    @Mocked
    MessageImpl mockMessageImpl;

    @Mocked
    Properties mockProperties;

    @Mocked
    Map<String, String> mockMapStringString;

    @Mocked
    ApplicationProperties mockApplicationProperties;

    @Mocked
    Queue<MessageImpl> mockQueue;

    @Mocked
    Sasl mockSasl;

    @Mocked
    Transport mockTransport;

    @Mocked
    SSLContext mockSSLContext;

    @Mocked
    Sender mockSender;

    @Mocked
    AmqpsMessage mockAmqpsMessage;

    @Mocked
    MessageType mockMessageType;

    @Mocked
    Session mockSession;

    @Mocked
    Receiver mockReceiver;

    @Mocked
    Delivery mockDelivery;

    @Mocked
    UUID mockUUID;

    @Mocked
    Map<String, Integer> mockMapStringInteger;

    @Mocked
    Map.Entry<String, Integer> mockStringIntegerEntry;

    @Mocked
    Section mockSection;

    @Mocked
    ProductInfo mockedProductInfo;

    @Mocked
    AmqpsLinkStateCallback mockAmqpsLinkStateCallback;

    // Tests_SRS_AMQPSDEVICEAUTHENTICATIONCBS_12_011: [The function shall set get the sasl layer from the transport.]
    // Tests_SRS_AMQPSDEVICEAUTHENTICATIONCBS_12_012: [The function shall set the sasl mechanism to PLAIN.]
    // Tests_SRS_AMQPSDEVICEAUTHENTICATIONCBS_12_013: [The function shall set the SslContext on the domain.]
    // Tests_SRS_AMQPSDEVICEAUTHENTICATIONCBS_12_014: [The function shall set the domain on the transport.]
    @Test
    public void setSslDomain()
    {
        // arrange
        final AmqpsCbsLinksHandler amqpsDeviceAuthenticationCBS = new AmqpsCbsLinksHandler(mockAmqpsLinkStateCallback);

        new NonStrictExpectations()
        {
            {
                mockTransport.sasl();
                result = mockSasl;
            }
        };

        Deencapsulation.invoke(amqpsDeviceAuthenticationCBS, "setSslDomain", mockTransport, mockSSLContext);
        // act

        // assert
        new Verifications()
        {
            {
                mockTransport.sasl();
                times = 1;
                mockSasl.setMechanisms("ANONYMOUS");
                times = 1;
                mockTransport.ssl((SslDomain)any);
                times = 1;
            }
        };
    }
}

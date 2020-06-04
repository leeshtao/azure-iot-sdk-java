/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package tests.unit.com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportMessage;
import com.microsoft.azure.sdk.iot.device.transport.TransportUtils;
import com.microsoft.azure.sdk.iot.device.transport.amqps.*;
import mockit.*;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.*;
import org.apache.qpid.proton.amqp.transport.SenderSettleMode;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.engine.impl.ReceiverImpl;
import org.apache.qpid.proton.engine.impl.SessionImpl;
import org.apache.qpid.proton.message.impl.MessageImpl;
import org.junit.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.*;

/**
*  Unit tests for AmqpsLinksHandler
* 100% methods covered
* 89% lines covered
*/
public class AmqpsLinksHandlerTest
{
    @Mocked
    Session mockSession;

    @Mocked
    Sender mockSender;

    @Mocked
    Receiver mockReceiver;

    @Mocked
    Link mockLink;

    @Mocked
    Target mockTarget;

    @Mocked
    Source mockSource;

    @Mocked
    Delivery mockDelivery;

    @Mocked
    HashMap mockHashMap;

    @Mocked
    AmqpsMessage mockAmqpsMessage;

    @Mocked
    DeviceClientConfig mockDeviceClientConfig;

    @Mocked
    MessageImpl mockMessageImpl;

    @Mocked
    Message mockMessage;

    @Mocked
    ProductInfo mockedProductInfo;

    private class AmqpsLinksHandlerMock extends AmqpsLinksHandler
    {
        public AmqpsLinksHandlerMock(DeviceClientConfig config)
        {
            super();
        }

        @Override
        public String getLinkInstanceType()
        {
            return "mock";
        }

        @Override
        public void onLinkRemoteOpen(Event event)
        {

        }
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_001: [**The constructor shall initialize amqpProperties with device client identifier and version.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_002: [**The constructor shall initialize sender and receiver tags with UUID string.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_003: [**The constructor shall initialize sender and receiver endpoint path members to empty string.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_004: [**The constructor shall initialize sender and receiver link address members to empty string.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_005: [**The constructor shall initialize sender and receiver link objects to null.**]**
    */
    @Test
    public void constructorInitializesAllMembers(@Mocked final UUID mockUUID)
    {
        // arrange
        final String uuidStr = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        final String expectedUserAgentString = "some user agent string";
        new NonStrictExpectations()
        {
            {
                UUID.randomUUID();
                result = mockUUID;
                mockUUID.toString();
                result = uuidStr;
                mockDeviceClientConfig.getProductInfo();
                result = mockedProductInfo;
                mockedProductInfo.getUserAgentString();
                result = expectedUserAgentString;
            }
        };

        //act
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);

        //assert
        assertNotNull(amqpsDeviceOperations);

        Sender senderLink = Deencapsulation.getField(amqpsDeviceOperations, "senderLink");
        Receiver receiverLink = Deencapsulation.getField(amqpsDeviceOperations, "receiverLink");


        assertTrue(senderLink == null);
        assertTrue(receiverLink == null);
    }

    //Tests_SRS_AMQPSDEVICEOPERATIONS_34_048: [If the provided deviceClientConfig is null, this function shall throw an IllegalArgumentException.]
    @Test (expected = IllegalArgumentException.class)
    public void constructorThrowsForNullConfig()
    {
        //arrange
        DeviceClientConfig nullConfig = null;

        //act
        AmqpsLinksHandler amqpsLinksHandler = Deencapsulation.newInstance(AmqpsLinksHandlerMock.class, nullConfig);
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_032: [**The class has static members for version identifier and api version keys.**]**
     */
    @Test
    public void apiVersionAndVersionIdFieldsValues()
    {
        //arrange
        String API_VERSION = TransportUtils.IOTHUB_API_VERSION;

        //act
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        String VERSION_IDENTIFIER_KEY = Deencapsulation.getField(amqpsDeviceOperations, "VERSION_IDENTIFIER_KEY");
        String API_VERSION_KEY = Deencapsulation.getField(amqpsDeviceOperations, "API_VERSION_KEY");

        //assert
        assertTrue(VERSION_IDENTIFIER_KEY.equals("com.microsoft:client-version"));
        assertTrue(API_VERSION_KEY.equals("com.microsoft:api-version"));
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_006: [**The function shall throw IllegalArgumentException if the session argument is null.**]**
     */
    @Test (expected = IllegalArgumentException.class)
    public void openLinksThrowsIllegalArgumentException() throws IllegalArgumentException
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);

        //act
        Deencapsulation.invoke(amqpsDeviceOperations, "openLinks", null);
    }

    @Test
    public void onLinkInitSetsAmqpsPropertiesForSender(@Mocked final Event mockEvent)
    {
        //arrange
        final AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);

        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);
        final Map<Symbol, Object> propertiesMap = Deencapsulation.invoke(amqpsDeviceOperations, "getAmqpProperties");

        new Expectations()
        {
            {
                mockEvent.getLink();
                result = mockSender;
            }
        };

        // act
        amqpsDeviceOperations.onLinkInit(mockEvent);

        //assert
        new Verifications()
        {
            {
                mockReceiver.setProperties(propertiesMap);
                times = 0;
                mockSender.setProperties(propertiesMap);
                times = 1;
            }
        };
    }

    @Test
    public void onLinkInitSetsAmqpsPropertiesForReceiver(@Mocked final Event mockEvent)
    {
        //arrange
        final AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);

        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);
        final Map<Symbol, Object> propertiesMap = Deencapsulation.invoke(amqpsDeviceOperations, "getAmqpProperties");

        new Expectations()
        {
            {
                mockEvent.getLink();
                result = mockReceiver;
            }
        };

        // act
        amqpsDeviceOperations.onLinkInit(mockEvent);

        //assert
        new Verifications()
        {
            {
                mockReceiver.setProperties(propertiesMap);
                times = 1;
                mockSender.setProperties(propertiesMap);
                times = 0;
            }
        };
    }


    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_011: [**If the sender link is not null the function shall closeNow it and sets it to null.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_012: [**If the receiver link is not null the function shall closeNow it and sets it to null.**]**
    */
    @Test
    public void closeLinksClosesLinks()
    {
        //arrange
        final AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);

        //act
        Deencapsulation.invoke(amqpsDeviceOperations, "closeLinks");

        //assert
        final Receiver receiverLink = Deencapsulation.getField(amqpsDeviceOperations, "receiverLink");
        final Sender senderLink = Deencapsulation.getField(amqpsDeviceOperations, "senderLink");

        assertTrue(receiverLink == null);
        assertTrue(senderLink == null);

        new Verifications()
        {
            {
                mockReceiver.close();
                times = 1;
                mockSender.close();
                times = 1;
            }
        };
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_013: [**The function shall throw IllegalArgumentException if the link argument is null.**]**
     */
    @Test (expected = IllegalArgumentException.class)
    public void initLinkThrowsIllegalArgumentException()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);

        //act
        Deencapsulation.invoke(amqpsDeviceOperations, "initLink", null);
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_014: [**If the link is the sender link, the function shall create a new Target (Proton) object using the sender link address member variable.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_015: [**If the link is the sender link, the function shall set its target to the created Target (Proton) object.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_016: [**If the link is the sender link, the function shall set the SenderSettleMode to UNSETTLED.**]**
     */
    @Test
    public void initLinkSetsSenderLink(final @Mocked Event mockEvent)
    {
        //arrange
        final AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        final String senderLinkTag = "senderLinkTag";
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);
        final String senderLinkAddress = "senderLinkAddress";
        Deencapsulation.setField(amqpsDeviceOperations, "senderLinkAddress", senderLinkAddress);

        new NonStrictExpectations()
        {
            {
                mockSender.getName();
                result = senderLinkTag;
                new Target();
                result = mockTarget;
                mockEvent.getLink();
                result = mockSender;
            }
        };

        //act
        Deencapsulation.invoke(amqpsDeviceOperations, "onLinkInit", mockEvent);

        //assert
        new Verifications()
        {
            {
                mockTarget.setAddress(senderLinkAddress);
                times = 1;
                mockSender.setTarget(mockTarget);
                times = 1;
                mockSender.setSenderSettleMode(SenderSettleMode.UNSETTLED);
                times = 1;
            }
        };
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_017: [**If the link is the receiver link, the function shall create a new Source (Proton) object using the receiver link address member variable.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_018: [**If the link is the receiver link, the function shall set its source to the created Source (Proton) object.**]**
     */
    @Test
    public void initLinkSetsReceiverLink(final @Mocked Event mockEvent)
    {
        //arrange
        final AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        final String receiverLinkTag = "receiverLinkTag";
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        final String receiverLinkAddress = "receiverLinkAddress";
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLinkAddress", receiverLinkAddress);

        new NonStrictExpectations()
        {
            {
                mockReceiver.getName();
                result = receiverLinkTag;
                new Source();
                result = mockSource;
                mockEvent.getLink();
                result = mockReceiver;
            }
        };

        //act
        Deencapsulation.invoke(amqpsDeviceOperations, "onLinkInit", mockEvent);

        //assert
        new Verifications()
        {
            {
                mockSource.setAddress(receiverLinkAddress);
                times = 1;
                mockReceiver.setSource(mockSource);
                times = 1;
            }
        };
    }

    // Tests_SRS_AMQPSDEVICEOPERATIONS_12_020: [The function shall throw IllegalArgumentException if the deliveryTag length is zero.]
    @Test
    public void sendMessageAndGetDeliveryHashReturnsFailureIfDeliveryTagNull()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        final byte[] msgData = new byte[1];
        final int offset = 0;
        final int length = 1;
        final byte[] deliveryTag = new byte[0];
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);

        //act
        Deencapsulation.invoke(amqpsDeviceOperations, "sendMessageAndGetDeliveryTag", MessageType.DEVICE_TELEMETRY, msgData, offset, length, deliveryTag);
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_036: [**The function shall throw IllegalArgumentException if the linkName is empty.**]**
    */
    @Test (expected = IllegalArgumentException.class)
    public void getMessageFromReceiverLinkThrowIfLinkNameEmpty()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);

        //act
        AmqpsMessage amqpsMessage = Deencapsulation.invoke(amqpsDeviceOperations, "getMessageFromReceiverLink", "");
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_043: [The function shall return null if the linkName does not match with the receiverLink tag.]
    */
    @Test
    public void getMessageFromReceiverLinkReturnsNullIfLinkNotOwned(final @Mocked SessionImpl mockSessionImpl)
    {
        //arrange
        final AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);

        new NonStrictExpectations()
        {
            {
                mockReceiver.current();
                result = mockDelivery;
                mockDelivery.isReadable();
                result = true;
                mockDelivery.isPartial();
                result = false;
            }
        };

        Receiver unknownReceiver = Deencapsulation.newInstance(ReceiverImpl.class, mockSessionImpl, "someUnknownLinkName");

        //act
        AmqpsMessage amqpsMessage = Deencapsulation.invoke(amqpsDeviceOperations, "getMessageFromReceiverLink", unknownReceiver);

        //assert
        assertTrue(amqpsMessage == null);
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_037: [**The function shall create a Delivery object from the link.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_033: [**The function shall try to read the full message from the delivery object and if it fails return null.**]**
    */
    @Test
    public void getMessageFromReceiverLinkReturnsNullIfNotReadable()
    {
        //arrange
        String linkName = "receiver";
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);

        new NonStrictExpectations()
        {
            {
                mockReceiver.current();
                result = mockDelivery;
                mockDelivery.isReadable();
                result = false;
                mockDelivery.isPartial();
                result = false;
            }
        };

        //act
        AmqpsMessage amqpsMessage = Deencapsulation.invoke(amqpsDeviceOperations, "getMessageFromReceiverLink", mockReceiver);

        //assert
        assertTrue(amqpsMessage == null);
        new Verifications()
        {
            {
                mockReceiver.current();
                times = 1;
                mockDelivery.isReadable();
                times = 1;
            }
        };
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_034: [**The function shall read the full message into a buffer.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_035: [**The function shall advance the receiver link.**]**
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_038: [**The function shall create a Proton message from the received buffer and return with it.**]**
    */
    @Test
    public void getMessageFromReceiverLinkSuccess()
    {
        //arrange
        String linkName = "receiver";
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLink", mockReceiver);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLink", mockSender);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLinkTag", linkName);

        new NonStrictExpectations()
        {
            {
                mockReceiver.current();
                result = mockDelivery;
                mockDelivery.isReadable();
                result = true;
                mockDelivery.isPartial();
                result = false;
            }
        };

        //act
        AmqpsMessage amqpsMessage = Deencapsulation.invoke(amqpsDeviceOperations, "getMessageFromReceiverLink", mockReceiver);

        //assert
        assertTrue(amqpsMessage != null);
        new Verifications()
        {
            {
                mockReceiver.current();
                times = 1;
                mockDelivery.isReadable();
                times = 1;
                mockDelivery.isPartial();
                times = 1;
            }
        };
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_027: [**The getter shall return with the value of the amqpProperties.**]**
     */
    @Test
    public void getAmqpPropertiesReturnsAmqpsProperties()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "amqpProperties", mockHashMap);

        //act
        Map<Symbol, Object> amqpsProperties = Deencapsulation.invoke(amqpsDeviceOperations, "getAmqpProperties");

        //assert
        assertTrue(amqpsProperties == mockHashMap);
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_028: [**The getter shall return with the value of the sender link tag.**]**
     */
    @Test
    public void getSenderLinkTagReturnsSenderLinkTag()
    {
        //arrange
        String linkTag = "linkTag";
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLinkTag", linkTag);

        //act
        String senderLinkTag = linkTag;

        //assert
        assertTrue(senderLinkTag.equals(linkTag));
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_029: [**The getter shall return with the value of the receiver link tag.**]**
     */
    @Test
    public void getReceiverLinkTagReturnsReceiverLinkTag()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLinkTag", "xxx");

        //act
        String receiverLinkTag = Deencapsulation.invoke(amqpsDeviceOperations, "getReceiverLinkTag");

        //assert
        assertTrue(receiverLinkTag.equals("xxx"));
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_030: [**The getter shall return with the value of the sender link address.**]**
     */
    @Test
    public void getSenderLinkAddressReturnsSenderLinkAddress()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "senderLinkAddress", "xxx");

        //act
        String senderLinkAddress = Deencapsulation.invoke(amqpsDeviceOperations, "getSenderLinkAddress");

        //assert
        assertTrue(senderLinkAddress.equals("xxx"));
    }

    /*
    **Tests_SRS_AMQPSDEVICEOPERATIONS_12_031: [**The getter shall return with the value of the receiver link address.**]**
     */
    @Test
    public void getReceiverLinkAddressReturnsReceiverLinkAddress()
    {
        //arrange
        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        Deencapsulation.setField(amqpsDeviceOperations, "receiverLinkAddress", "xxx");

        //act
        String receiverLinkAddress = Deencapsulation.invoke(amqpsDeviceOperations, "getReceiverLinkAddress");

        //assert
        assertTrue(receiverLinkAddress.equals("xxx"));
    }

    //Tests_SRS_AMQPSDEVICEOPERATION_34_015: [The function shall create a new Proton message using the IoTHubMessage body.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_016: [The function shall copy the correlationId, messageId, content type and content encoding properties to the Proton message properties.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_017: [The function shall copy the user properties to Proton message application properties excluding the reserved property names.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_051: [This function shall set the message's saved outputname in the application properties of the new proton message.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_055: [This function shall set the message's saved creationTimeUTC in the application properties of the new proton message.]
    @Test
    public void convertToProtonSuccess(
            @Mocked final Message mockMessage,
            @Mocked final MessageImpl mockProtonMessage,
            @Mocked final Proton mockProton
    )
    {
        //arrange
        final String correlationId = "1234";
        final String messageId = "5678";
        final String contentType = "some content type";
        final String contentEncoding = "some content encoding";
        final String connectionDeviceId = "some connection device id";
        final String connectionModuleId = "some connection module id";
        final String creationTimeUTCString = "1969-12-31T16:00:00.0000000";
        final MessageProperty[] iotHubMessageProperties = new MessageProperty[]
                {
                        new MessageProperty("key1", "value1"),
                        new MessageProperty("key2", "value2")
                };
        final Map<String, Object> userProperties = new HashMap<>(2);
        userProperties.put(iotHubMessageProperties[0].getName(), iotHubMessageProperties[0].getValue());
        userProperties.put(iotHubMessageProperties[1].getName(), iotHubMessageProperties[1].getValue());

        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        new NonStrictExpectations()
        {
            {
                Proton.message();
                result = mockProtonMessage;

                mockMessage.getMessageType();
                result = MessageType.DEVICE_TELEMETRY;
                mockMessage.getMessageId();
                result = messageId;
                times = 2;
                mockMessage.getCorrelationId();
                result = correlationId;
                times = 3;
                mockMessage.getContentEncoding();
                result = contentEncoding;
                times = 2;
                mockMessage.getContentType();
                result = contentType;
                times = 2;
                mockMessage.getConnectionModuleId();
                result = connectionModuleId;
                times = 2;
                mockMessage.getConnectionDeviceId();
                result = connectionDeviceId;
                times = 2;
                mockMessage.getProperties();
                result = iotHubMessageProperties;

                mockMessage.getCreationTimeUTC();
                result = new Date(0);
                times = 1;

                mockMessage.getCreationTimeUTCString();
                result = "1969-12-31T16:00:00.0000000";
                times = 1;

                mockMessage.isSecurityMessage();
                result = false;
                times = 1;

                new ApplicationProperties(userProperties);
            }
        };

        //act
        MessageImpl protonMessage = Deencapsulation.invoke(amqpsDeviceOperations, "iotHubMessageToProtonMessage", mockMessage);

        //assert
        assertTrue(protonMessage != null);
    }

    //Tests_SRS_AMQPSDEVICEOPERATION_34_009: [The function shall create a new IoTHubMessage using the Proton message body.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_010: [The function shall copy the correlationId, messageId, To, userId, contenty type, and content encoding properties to the IotHubMessage properties.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_011: [The function shall copy the Proton application properties to IoTHubMessage properties excluding the reserved property names.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_052: [If the amqp message contains an application property of "x-opt-input-name", this function shall assign its value to the IotHub message's input name.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_053: [If the amqp message contains an application property for the connection device id, this function shall assign its value to the IotHub message's connection device id.]
    //Tests_SRS_AMQPSDEVICEOPERATION_34_054: [If the amqp message contains an application property for the connection module id, this function shall assign its value to the IotHub message's connection module id.]
    @Test
    public void convertFromProtonSuccessWithBody(
            @Mocked final MessageImpl mockProtonMessage,
            @Mocked final Properties properties,
            @Mocked final MessageCallback mockMessageCallback,
            @Mocked final AmqpsMessage mockAmqpsMessage,
            @Mocked final Data mockData,
            @Mocked final Symbol mockSymbol,
            @Mocked final Symbol mockSymbol2
    )
    {
        //arrange
        final String correlationId = "1234";
        final String messageId = "5678";
        final Binary userId = new Binary("user1".getBytes());
        final String to = "devices/deviceID/messages/devicebound/";
        final Date absoluteExpiryTime = new Date(Long.MAX_VALUE);
        final String customPropertyKey = "appProp";
        final String customPropertyValue = "appValue";
        final String contentType = "application/json";
        final String contentEncoding = "utf-8";
        final String toKey = "to";
        final String userIdKey = "userId";
        final String inputNameValue = "someInputName";
        final String connectionModuleId = "someModuleId";
        final String connectionDeviceId = "someDeviceId";
        final Map<String, Object> applicationPropertiesMap = new HashMap();
        applicationPropertiesMap.put(customPropertyKey, customPropertyValue);
        applicationPropertiesMap.put(MessageProperty.CONNECTION_MODULE_ID, connectionModuleId);
        applicationPropertiesMap.put(MessageProperty.CONNECTION_DEVICE_ID, connectionDeviceId);

        AmqpsLinksHandlerMock amqpsDeviceOperations = new AmqpsLinksHandlerMock(mockDeviceClientConfig);
        final String AMQPS_APP_PROPERTY_PREFIX = Deencapsulation.getField(amqpsDeviceOperations, "AMQPS_APP_PROPERTY_PREFIX");

        new NonStrictExpectations()
        {
            {
                mockAmqpsMessage.getAmqpsMessageType();
                result = MessageType.DEVICE_TELEMETRY;
                mockAmqpsMessage.getBody();
                result = mockData;
                mockAmqpsMessage.getApplicationProperties().getValue();
                result = applicationPropertiesMap;
                properties.getMessageId();
                result = messageId;
                properties.getCorrelationId();
                result = correlationId;
                properties.getTo();
                result = to;
                properties.getContentEncoding();
                result = mockSymbol;
                mockSymbol.toString();
                result = contentEncoding;
                properties.getContentType();
                result = mockSymbol2;
                mockSymbol2.toString();
                result = contentType;
                properties.getUserId();
                result = userId;
                properties.getAbsoluteExpiryTime();
                result = absoluteExpiryTime;
                mockDeviceClientConfig.getDeviceTelemetryMessageCallback(inputNameValue);
                result = mockMessageCallback;
                mockDeviceClientConfig.getDeviceTelemetryMessageContext(inputNameValue);
                result = "myContext";
            }
        };

        //act
        final IotHubTransportMessage actualMessage = Deencapsulation.invoke(amqpsDeviceOperations, "protonMessageToIoTHubMessage", mockAmqpsMessage);

        //assert
        new Verifications()
        {
            {
                actualMessage.setCorrelationId(correlationId);
                actualMessage.setMessageId(messageId);
                actualMessage.setConnectionModuleId(connectionModuleId);
                actualMessage.setConnectionDeviceId(connectionDeviceId);
                actualMessage.setContentType(contentType);
                actualMessage.setContentEncoding(contentEncoding);
                actualMessage.setProperty(customPropertyKey, customPropertyValue);
                actualMessage.setProperty(AMQPS_APP_PROPERTY_PREFIX + toKey, to);
                actualMessage.setProperty(AMQPS_APP_PROPERTY_PREFIX + userIdKey, userId.toString());
            }
        };
    }
}
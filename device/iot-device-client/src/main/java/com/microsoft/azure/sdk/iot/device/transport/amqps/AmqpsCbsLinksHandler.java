// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.*;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.message.impl.MessageImpl;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defines the authentication session that exists in every SAS based amqp connection. There is one CBS sender link to send
 * SAS tokens over, and one CBS receiver link to receive authentication status from.
 */
@Slf4j
public final class AmqpsCbsLinksHandler extends AmqpsLinksHandler
{
    private final static String APPLICATION_PROPERTY_STATUS_CODE = "status-code";
    private final static String APPLICATION_PROPERTY_STATUS_DESCRIPTION = "status-description";

    public static final String SENDER_LINK_ENDPOINT_PATH = "$cbs";
    public static final String RECEIVER_LINK_ENDPOINT_PATH = "$cbs";

    public static final String SENDER_LINK_TAG_PREFIX = "cbs-sender-";
    public static final String RECEIVER_LINK_TAG_PREFIX = "cbs-receiver-";

    private static final String CBS_TO = "$cbs";
    private static final String CBS_REPLY = "cbs";

    private static final String OPERATION_KEY = "operation";
    private static final String TYPE_KEY = "type";
    private static final String NAME_KEY = "name";

    private static final String OPERATION_VALUE = "put-token";
    private static final String TYPE_VALUE = "servicebus.windows.net:sastoken";

    private static final String DEVICES_PATH =  "/devices/";

    private long nextTag = 0;
    private Map<UUID, AuthenticationMessageCallback> correlationMap = new ConcurrentHashMap<>();

    private AmqpsLinkStateCallback linkStateCallback;

    /**
     * This constructor creates an instance of AmqpsCbsLinksHandler class and initializes member variables
     *
     * @throws IllegalArgumentException if deviceClientConfig is null.
     */
    public AmqpsCbsLinksHandler(AmqpsLinkStateCallback linkStateCallback) throws IllegalArgumentException
    {
        super();

        this.linkStateCallback = linkStateCallback;

        this.senderLinkTag = SENDER_LINK_TAG_PREFIX + senderLinkTag;
        this.receiverLinkTag = RECEIVER_LINK_TAG_PREFIX + receiverLinkTag;

        this.senderLinkAddress = SENDER_LINK_ENDPOINT_PATH;
        this.receiverLinkAddress = RECEIVER_LINK_ENDPOINT_PATH;
    }

    @Override
    public String getLinkInstanceType()
    {
        return "cbs";
    }

    @Override
    public void onDelivery(Event event)
    {
        Link link = event.getLink();
        Delivery delivery = event.getDelivery();
        if (link instanceof Sender)
        {
            //Each delivery to a sender link may contain multiple acknowledgements of sent messages. Loop until
            // all acknowledgements have been processed
            while (delivery != null && !delivery.isSettled() && delivery.getRemoteState() != null) {
                log.trace("Received acknowledgement for a sent CBS authentication message");

                int deliveryTag = Integer.valueOf(new String(event.getDelivery().getTag()));
                this.amqpsLinkStateCallback.onMessageAcknowledged(deliveryTag);
                delivery.free();
                delivery = link.head();
            }
        }
        else if (link instanceof Receiver)
        {
            log.trace("Received a message on the CBS receiver link");
            handleCBSResponseMessage((Receiver) link);

            // Flow 1 credit back to the service since 1 credit was just used to deliver this message
            ((Receiver) link).flow(1);

            delivery.free();
        }
    }

    protected void handleCBSResponseMessage(Receiver receiver)
    {
        AmqpsMessage amqpsMessage = super.getMessageFromReceiverLink(receiver);
        if (amqpsMessage != null)
        {
            amqpsMessage.setAmqpsMessageType(MessageType.CBS_AUTHENTICATION);

            if (amqpsMessage.getApplicationProperties() != null && amqpsMessage.getProperties() != null)
            {
                Properties properties = amqpsMessage.getProperties();
                Object correlationId = properties.getCorrelationId();
                Map<String, Object> applicationProperties = amqpsMessage.getApplicationProperties().getValue();

                if (!this.correlationMap.containsKey(correlationId))
                {
                    log.error("Received cbs authentication message with no correlation id. Ignoring it...");
                    amqpsMessage.acknowledge(Released.getInstance());
                    return;
                }

                AuthenticationMessageCallback authenticationMessageCallback = this.correlationMap.get(correlationId);
                for (Map.Entry<String, Object> entry : applicationProperties.entrySet())
                {
                    String propertyKey = entry.getKey();
                    if (propertyKey.equals(APPLICATION_PROPERTY_STATUS_CODE) && entry.getValue() instanceof Integer)
                    {
                        int authenticationResponseCode = (int) entry.getValue();

                        String statusDescription = "";
                        if (applicationProperties.containsKey(APPLICATION_PROPERTY_STATUS_DESCRIPTION))
                        {
                            statusDescription = (String) applicationProperties.get(APPLICATION_PROPERTY_STATUS_DESCRIPTION);
                        }

                        DeliveryState ackType = authenticationMessageCallback.handleAuthenticationResponseMessage(authenticationResponseCode, statusDescription);
                        amqpsMessage.acknowledge(ackType);
                        return;
                    }
                }
            }
            else
            {
                log.warn("Could not handle authentication message because it had no application properties or had no system properties");
            }
        }

        // By default, we can't process the message
        amqpsMessage.acknowledge(Released.getInstance());
    }

    protected void setSslDomain(Transport transport, SSLContext sslContext)
    {
        Sasl sasl = transport.sasl();
        sasl.setMechanisms("ANONYMOUS");

        SslDomain domain = Proton.sslDomain();
        domain.setSslContext(sslContext);
        domain.setPeerAuthentication(SslDomain.VerifyMode.VERIFY_PEER);
        domain.init(SslDomain.Mode.CLIENT);
        transport.ssl(domain);
    }

    /**
     * Start CBS authentication process by creating an adding 
     * authentication message to the send queue 
     * 
     * @param deviceClientConfig device configuration to use for 
     *                           authentication
     * @throws TransportException when CBS Authentication Message failed to be created
     */
    protected void sendAuthenticationMessage(DeviceClientConfig deviceClientConfig, AuthenticationMessageCallback authenticationMessageCallback) throws TransportException
    {
        this.log.trace("sendAuthenticationMessage called in AmqpsCbsLinksHandler");
        UUID correlationId = UUID.randomUUID();
        correlationMap.put(correlationId, authenticationMessageCallback);
        MessageImpl outgoingMessage = createCBSAuthenticationMessage(deviceClientConfig, correlationId);
        byte[] msgData = new byte[1024];
        int length;

        while (true)
        {
            try
            {
                length = outgoingMessage.encode(msgData, 0, msgData.length);
                break;
            }
            catch (BufferOverflowException e)
            {
                msgData = new byte[msgData.length * 2];
            }
        }
        byte[] deliveryTag = String.valueOf(this.nextTag).getBytes();

        if (this.nextTag == Integer.MAX_VALUE || this.nextTag < 0)
        {
            this.nextTag = 0;
        }
        else
        {
            this.nextTag++;
        }

        this.sendMessageAndGetDeliveryTag(MessageType.CBS_AUTHENTICATION, msgData, 0, length, deliveryTag);
    }

    /**
     * Create a CBS authentication message for the given device 
     * client 
     * 
     * @param deviceClientConfig device client configuration
     * @throws TransportException when failed to get renewed SAS token
     * @return MessageImpl the Proton-j message to send
     */
    private MessageImpl createCBSAuthenticationMessage(DeviceClientConfig deviceClientConfig, UUID correlationId) throws TransportException
    {
        MessageImpl outgoingMessage = (MessageImpl) Proton.message();

        Properties properties = new Properties();

        // Note that this is intentional. For some reason, iothub only responds correctly if this correlation id is set as the messageId, not the correlationId
        properties.setMessageId(correlationId);

        properties.setTo(CBS_TO);
        properties.setReplyTo(CBS_REPLY);
        outgoingMessage.setProperties(properties);

        Map<String, Object> userProperties = new HashMap<>(3);
        userProperties.put(OPERATION_KEY, OPERATION_VALUE);
        userProperties.put(TYPE_KEY, TYPE_VALUE);

        String host = deviceClientConfig.getGatewayHostname();
        if (host == null || host.isEmpty())
        {
            host = deviceClientConfig.getIotHubHostname();
        }

        userProperties.put(NAME_KEY, host + DEVICES_PATH + deviceClientConfig.getDeviceId());
        ApplicationProperties applicationProperties = new ApplicationProperties(userProperties);
        outgoingMessage.setApplicationProperties(applicationProperties);

        Section section;
        try
        {
            section = new AmqpValue(deviceClientConfig.getSasTokenAuthentication().getRenewedSasToken(true, true));
            outgoingMessage.setBody(section);
        }
        catch (IOException e)
        {
            log.error("getRenewedSasToken has thrown exception while building new cbs authentication message", e);
            throw new TransportException(e);
        }

        return outgoingMessage;
    }
}

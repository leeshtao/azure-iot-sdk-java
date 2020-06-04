// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.exceptions.ProtocolException;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransport;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportMessage;
import com.microsoft.azure.sdk.iot.device.transport.TransportUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.*;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode;
import org.apache.qpid.proton.amqp.transport.SenderSettleMode;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.message.impl.MessageImpl;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class AmqpsLinksHandler extends BaseHandler
{
    protected static final String VERSION_IDENTIFIER_KEY = "com.microsoft:client-version";
    protected static final String API_VERSION_KEY = "com.microsoft:api-version";

    protected static final String TO_KEY = "to";
    protected static final String USER_ID_KEY = "userId";
    protected static final String AMQPS_APP_PROPERTY_PREFIX = "iothub-app-";

    Map<Symbol, Object> amqpProperties;

    protected String senderLinkTag;
    protected String receiverLinkTag;

    protected String linkCorrelationId;

    protected String senderLinkAddress;
    protected String receiverLinkAddress;

    protected Sender senderLink;
    protected Receiver receiverLink;

    protected AmqpsLinkStateCallback amqpsLinkStateCallback;

    // Note that proton-j has getters for these states on the links themselves. However, when opening two links,
    // when the first onLinkRemote callback is fired, the proton-j state of both is usually ACTIVE.
    // In order to only notify the session layer when both links have had their onLinkRemoteOpen callback fired,
    // these booleans are used. In general, they should be ignored though. They are not necessarily up to date on state
    // after the links are opened.
    private boolean senderLinkOpened;
    private boolean receiverLinkOpened;

    private final Map<Message, AmqpsMessage> receivedMessagesMap = new ConcurrentHashMap<>();

    /**
     * This constructor creates an instance of device operation class and initializes member variables
     */
    public AmqpsLinksHandler()
    {
        this.amqpProperties = new HashMap<>();
        this.amqpProperties.put(Symbol.getSymbol(API_VERSION_KEY), TransportUtils.IOTHUB_API_VERSION);

        this.linkCorrelationId = UUID.randomUUID().toString();

        this.senderLink = null;
        this.receiverLink = null;

        this.senderLinkOpened = false;
        this.receiverLinkOpened = false;
    }

    protected synchronized void assignLinks(Sender sender, Receiver receiver, AmqpsLinkStateCallback amqpsLinkStateCallback)
    {
        this.senderLink = sender;
        this.receiverLink = receiver;
        this.amqpsLinkStateCallback = amqpsLinkStateCallback;
    }

    /**
     * Closes receiver and sender link if they are not null
     */
    protected void closeLinks()
    {
        if (this.senderLink != null)
        {
            this.log.debug("Closing {} sender link with link correlation id {}", getLinkInstanceType(), this.linkCorrelationId);
            this.senderLink.close();
            this.senderLink = null;
        }
        else
        {
            this.log.trace("Sender link was already closed, so nothing was done to the link");
        }

        if (this.receiverLink != null)
        {
            this.log.debug("Closing {} receiver link with link correlation id {}", getLinkInstanceType(), this.linkCorrelationId);
            this.receiverLink.close();
            this.receiverLink = null;
        }
        else
        {
            this.log.trace("Receiver link was already closed, so nothing was done to the link");
        }
    }

    @Override
    public void onLinkRemoteOpen(Event event)
    {
        Link link = event.getLink();
        if (link.equals(this.senderLink))
        {
            this.log.debug("{} sender link with link correlation id {} was successfully opened", getLinkInstanceType(), this.linkCorrelationId);
            this.senderLinkOpened = true;
        }

        if (link.equals(this.receiverLink))
        {
            this.log.debug("{} receiver link with link correlation id {} was successfully opened", getLinkInstanceType(), this.linkCorrelationId);
            this.receiverLinkOpened = true;

            // Initial flow, only extended once. Each onDelivery event should extend flow back to the service at 1 credit per message received
            ((Receiver)link).flow(1024);
        }

        if (this.senderLinkOpened && this.receiverLinkOpened)
        {
            amqpsLinkStateCallback.onLinksOpened(this);
        }
    }

    @Override
    public void onLinkLocalOpen(Event event)
    {
        // TODO check with eventhub SDK, add logs
    }

    @Override
    public void onDelivery(Event event)
    {
        Link link = event.getLink();
        if (link instanceof Sender)
        {
            Delivery delivery = event.getDelivery();

            //Each delivery to a sender link may contain multiple acknowledgements of sent messages. Loop until
            // all acknowledgements have been processed
            while (delivery != null && !delivery.isSettled() && delivery.getRemoteState() != null) {
                int deliveryTag = Integer.valueOf(new String(event.getDelivery().getTag()));
                this.amqpsLinkStateCallback.onMessageAcknowledged(deliveryTag);
                delivery.free();
                delivery = link.head();
            }

        }
        else if (link instanceof Receiver)
        {
            AmqpsMessage amqpsMessage = this.getMessageFromReceiverLink(this.receiverLink);
            IotHubTransportMessage iotHubMessage = this.protonMessageToIoTHubMessage(amqpsMessage);
            this.receivedMessagesMap.put(iotHubMessage, amqpsMessage);
            this.amqpsLinkStateCallback.onMessageReceived(iotHubMessage);

            // Flow 1 credit back to the service since 1 credit was just used to deliver this message
            ((Receiver) link).flow(1);
        }
    }

    @Override
    public void onLinkInit(Event event)
    {
        Link link = event.getLink();

        String linkName = link.getName();

        if (link.equals(this.senderLink))
        {
            Target target = new Target();
            target.setAddress(this.getSenderLinkAddress());

            link.setTarget(target);

            link.setSenderSettleMode(SenderSettleMode.UNSETTLED);
            link.setProperties(this.getAmqpProperties());
            link.open();
            this.log.trace("Opening sender link with correlation id {}", this.linkCorrelationId);
        }
        else if (link.equals(this.receiverLink))
        {
            Source source = new Source();
            source.setAddress(this.getReceiverLinkAddress());

            link.setSource(source);

            link.setReceiverSettleMode(ReceiverSettleMode.FIRST);
            link.setProperties(this.getAmqpProperties());
            link.open();
            this.log.trace("Opening receiver link with correlation id {}", this.linkCorrelationId);
        }
        else
        {
            this.log.trace("InitLink called, but no link names matched {}", linkName);
        }
    }

    @Override
    public void onLinkRemoteClose(Event event)
    {
        // TODO double check that link, session and connection level all have roughly the same logic here

        Link link = event.getLink();

        // Set the local condition of the connection so that the connection layer can retrieve it when building the
        // transport exception
        link.getSession().getConnection().setCondition(link.getRemoteCondition());

        if (link.equals(this.senderLink))
        {
            this.log.debug("{} sender link with link correlation id {} was closed", getLinkInstanceType(), this.linkCorrelationId);
            this.senderLink.close();
            this.receiverLink.getSession().close();

            //TODO set link to null?
        }

        if (link.equals(this.receiverLink))
        {
            this.log.debug("{} receiver link with link correlation id {} was closed", getLinkInstanceType(), this.linkCorrelationId);

            this.receiverLink.close();
            this.receiverLink.getSession().close();

            //TODO set link to null?
        }
    }

    @Override
    public void onLinkLocalClose(Event event)
    {
        // TODO check with eventhub SDK
        // TODO maybe this can be simplified to just event.getSession()?
        event.getLink().getSession().close();
    }

    protected synchronized AmqpsSendReturnValue sendMessageAndGetDeliveryTag(MessageType messageType, byte[] msgData, int offset, int length, byte[] deliveryTag)
    {
        if (this.senderLink == null)
        {
            return new AmqpsSendReturnValue(false);
        }

        if (deliveryTag.length == 0)
        {
            return new AmqpsSendReturnValue(false);
        }

        if (this.senderLink.getLocalState() != EndpointState.ACTIVE || this.senderLink.getRemoteState() != EndpointState.ACTIVE)
        {
            return new AmqpsSendReturnValue(false);
        }

        Delivery delivery = this.senderLink.delivery(deliveryTag);
        try
        {
            this.log.trace("Sending {} bytes over the amqp {} sender link with link correlation id {}", length, getLinkInstanceType(), this.linkCorrelationId);
            int bytesSent = this.senderLink.send(msgData, offset, length);
            this.log.trace("{} bytes sent over the amqp {} sender link with link correlation id {}", bytesSent, getLinkInstanceType(), this.linkCorrelationId);

            if (bytesSent != length)
            {
                ProtocolException amqpSendFailedException = new ProtocolException(String.format("Amqp send operation did not send all of the expected bytes for %s sender link with link correlation id %s, retrying to send the message", getLinkInstanceType(), this.linkCorrelationId));
                throw amqpSendFailedException;
            }

            boolean canAdvance = this.senderLink.advance();

            if (!canAdvance)
            {
                ProtocolException amqpSendFailedException = new ProtocolException(String.format("Failed to advance the senderLink after sending a message on %s sender link with link correlation id %s, retrying to send the message", getLinkInstanceType(), this.linkCorrelationId));
                throw amqpSendFailedException;
            }

            this.log.trace("Message was sent over {} sender link with delivery tag {} and hash {}", getLinkInstanceType(), new String(deliveryTag), delivery.hashCode());
            return new AmqpsSendReturnValue(true, deliveryTag);
        }
        catch (Exception e)
        {
            this.log.warn("Encountered a problem while sending a message on {} sender link with link correlation id {}", getLinkInstanceType(), this.linkCorrelationId, e);
            this.senderLink.advance();
            delivery.free();
            return new AmqpsSendReturnValue(false);
        }
    }

    public abstract String getLinkInstanceType();

    protected AmqpsMessage getMessageFromReceiverLink(Receiver receiver)
    {
        Delivery delivery = receiver.current();

        if ((delivery != null) && delivery.isReadable() && !delivery.isPartial())
        {
            int size = delivery.pending();
            byte[] buffer = new byte[size];
            int bytesRead = receiver.recv(buffer, 0, buffer.length);

            this.log.trace("read {} bytes from receiver link {}", bytesRead, receiver.getName());

            boolean receiverLinkAdvanced = receiver.advance();

            if (!receiverLinkAdvanced)
            {
                this.log.warn("{} receiver link with link correlation id {} did not advance after bytes were read from it", getLinkInstanceType(), this.linkCorrelationId);
            }

            if (size != bytesRead)
            {
                log.warn("Amqp read from {} receiver link with link correlation id {} did not read the expected amount of bytes. Read {} but expected {}", getLinkInstanceType(), this.linkCorrelationId, bytesRead, size);
            }

            AmqpsMessage amqpsMessage = new AmqpsMessage();
            amqpsMessage.setDelivery(delivery);
            amqpsMessage.decode(buffer, 0, bytesRead);

            return amqpsMessage;
        }

        return null;
    }

    public boolean acknowledgeReceivedMessage(IotHubTransportMessage message, DeliveryState ackType)
    {
        if (this.receivedMessagesMap.containsKey(message))
        {
            this.receivedMessagesMap.remove(message).acknowledge(ackType);
            return true;
        }

        return false;
    }

    protected IotHubTransportMessage protonMessageToIoTHubMessage(AmqpsMessage protonMsg)
    {
        this.log.trace("Converting proton message to iot hub message for {} receiver link with link correlation id {}. Proton message correlation id {}", getLinkInstanceType(), this.linkCorrelationId, protonMsg.getCorrelationId());
        byte[] msgBody;
        Data d = (Data) protonMsg.getBody();
        if (d != null)
        {
            Binary b = d.getValue();
            msgBody = new byte[b.getLength()];
            ByteBuffer buffer = b.asByteBuffer();
            buffer.get(msgBody);
        }
        else
        {
            msgBody = new byte[0];
        }

        IotHubTransportMessage iotHubTransportMessage = new IotHubTransportMessage(msgBody, MessageType.UNKNOWN);

        Properties properties = protonMsg.getProperties();
        if (properties != null)
        {
            if (properties.getCorrelationId() != null)
            {
                iotHubTransportMessage.setCorrelationId(properties.getCorrelationId().toString());
            }

            if (properties.getMessageId() != null)
            {
                iotHubTransportMessage.setMessageId(properties.getMessageId().toString());
            }

            if (properties.getTo() != null)
            {
                iotHubTransportMessage.setProperty(AMQPS_APP_PROPERTY_PREFIX + TO_KEY, properties.getTo());
            }

            if (properties.getUserId() != null)
            {
                iotHubTransportMessage.setProperty(AMQPS_APP_PROPERTY_PREFIX + USER_ID_KEY, properties.getUserId().toString());
            }

            if (properties.getContentEncoding() != null)
            {
                iotHubTransportMessage.setContentEncoding(properties.getContentEncoding().toString());
            }

            if (properties.getContentType() != null)
            {
                iotHubTransportMessage.setContentType(properties.getContentType().toString());
            }
        }

        if (protonMsg.getApplicationProperties() != null)
        {
            Map<String, Object> applicationProperties = protonMsg.getApplicationProperties().getValue();
            for (Map.Entry<String, Object> entry : applicationProperties.entrySet())
            {
                String propertyKey = entry.getKey();
                if (propertyKey.equalsIgnoreCase(MessageProperty.CONNECTION_DEVICE_ID))
                {
                    iotHubTransportMessage.setConnectionDeviceId(entry.getValue().toString());
                }
                else if (propertyKey.equalsIgnoreCase(MessageProperty.CONNECTION_MODULE_ID))
                {
                    iotHubTransportMessage.setConnectionModuleId(entry.getValue().toString());
                }
                else if (!MessageProperty.RESERVED_PROPERTY_NAMES.contains(propertyKey))
                {
                    iotHubTransportMessage.setProperty(entry.getKey(), entry.getValue().toString());
                }
            }
        }

        return iotHubTransportMessage;
    }

    protected MessageImpl iotHubMessageToProtonMessage(Message message)
    {
        this.log.trace("Converting IoT Hub message to proton message for {} sender link with link correlation id {}. IoT Hub message correlationId {}", getLinkInstanceType(), this.linkCorrelationId, message.getCorrelationId());
        MessageImpl outgoingMessage = (MessageImpl) Proton.message();

        Properties properties = new Properties();
        if (message.getMessageId() != null)
        {
            properties.setMessageId(message.getMessageId());
        }

        if (message.getCorrelationId() != null)
        {
            properties.setCorrelationId(message.getCorrelationId());
        }

        if (message.getContentType() != null)
        {
            properties.setContentType(Symbol.valueOf(message.getContentType()));
        }

        if (message.getContentEncoding() != null)
        {
            properties.setContentEncoding(Symbol.valueOf(message.getContentEncoding()));
        }

        outgoingMessage.setProperties(properties);

        Map<String, Object> userProperties = new HashMap<>();
        if (message.getProperties().length > 0)
        {
            for(MessageProperty messageProperty : message.getProperties())
            {
                if (!MessageProperty.RESERVED_PROPERTY_NAMES.contains(messageProperty.getName()))
                {
                    userProperties.put(messageProperty.getName(), messageProperty.getValue());
                }
            }
        }

        if (message.getConnectionDeviceId() != null)
        {
            userProperties.put(MessageProperty.CONNECTION_DEVICE_ID, message.getConnectionDeviceId());
        }

        if (message.getConnectionModuleId() != null)
        {
            userProperties.put(MessageProperty.CONNECTION_MODULE_ID, message.getConnectionModuleId());
        }

        if (message.getCreationTimeUTC() != null)
        {
            userProperties.put(MessageProperty.IOTHUB_CREATION_TIME_UTC, message.getCreationTimeUTCString());
        }

        if (message.isSecurityMessage())
        {
            userProperties.put(MessageProperty.IOTHUB_SECURITY_INTERFACE_ID, MessageProperty.IOTHUB_SECURITY_INTERFACE_ID_VALUE);
        }

        ApplicationProperties applicationProperties = new ApplicationProperties(userProperties);
        outgoingMessage.setApplicationProperties(applicationProperties);

        Binary binary = new Binary(message.getBytes());
        Section section = new Data(binary);
        outgoingMessage.setBody(section);
        return outgoingMessage;
    }

    Map<Symbol, Object> getAmqpProperties()
    {
        return this.amqpProperties;
    }

    String getSenderLinkTag()
    {
        return this.senderLinkTag;
    }

    String getReceiverLinkTag()
    {
        return this.receiverLinkTag;
    }

    String getSenderLinkAddress()
    {
        return this.senderLinkAddress;
    }

    String getReceiverLinkAddress()
    {
        return this.receiverLinkAddress;
    }

    public boolean isOpen()
    {
        return this.senderLink != null && this.senderLink.getRemoteState() == EndpointState.ACTIVE && this.senderLink.getLocalState() == EndpointState.ACTIVE
                && this.receiverLink != null && this.receiverLink.getRemoteState() == EndpointState.ACTIVE && this.receiverLink.getLocalState() == EndpointState.ACTIVE;
    }
}

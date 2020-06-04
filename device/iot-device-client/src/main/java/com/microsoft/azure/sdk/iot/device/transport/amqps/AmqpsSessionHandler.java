package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.DeviceClientConfig;
import com.microsoft.azure.sdk.iot.device.Message;
import com.microsoft.azure.sdk.iot.device.MessageType;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransport;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportMessage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.message.impl.MessageImpl;

import java.nio.BufferOverflowException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.microsoft.azure.sdk.iot.device.MessageType.*;

@Slf4j
public class AmqpsSessionHandler extends BaseHandler implements AmqpsLinkStateCallback
{
    @Getter
    private final DeviceClientConfig deviceClientConfig;

    private Map<MessageType, AmqpsLinksHandler> amqpsLinkMap = new HashMap<MessageType, AmqpsLinksHandler>();

    private static long nextTag = 0;

    public Session session;

    private final Map<Integer, SubscriptionType> inProgressSubscriptionMessages = new ConcurrentHashMap<>();

    private AmqpsSessionStateCallback amqpsSessionStateCallback;

    protected AmqpsSessionHandler(final DeviceClientConfig deviceClientConfig, AmqpsSessionStateCallback amqpsSessionStateCallback) throws IllegalArgumentException
    {
        if (deviceClientConfig == null)
        {
            throw new IllegalArgumentException("deviceClientConfig cannot be null.");
        }

        this.deviceClientConfig = deviceClientConfig;

        this.amqpsLinkMap.put(DEVICE_TELEMETRY, new AmqpsTelemetryLinksHandler(this.deviceClientConfig));

        this.amqpsSessionStateCallback = amqpsSessionStateCallback;
    }

    /**
     * Release all resources and close all links.
     */
    public void close()
    {
        this.log.debug("Closing amqp session for device {}", this.getDeviceId());

        this.closeLinks();
    }

    void openLinks()
    {
        if (session != null)
        {
            for (AmqpsLinksHandler amqpsLinksHandler : this.amqpsLinkMap.values())
            {
                Sender sender = session.sender(amqpsLinksHandler.getSenderLinkTag());
                Receiver receiver = session.receiver(amqpsLinksHandler.getReceiverLinkTag());

                amqpsLinksHandler.assignLinks(sender, receiver, this);
                BaseHandler.setHandler(sender, amqpsLinksHandler);
                BaseHandler.setHandler(receiver, amqpsLinksHandler);
            }
        }
    }

    /**
     * Delegate the close link call to device operation objects.
     */
    void closeLinks()
    {
        Iterator iterator = amqpsLinkMap.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<MessageType, AmqpsLinksHandler> pair = (Map.Entry<MessageType, AmqpsLinksHandler>)iterator.next();
            pair.getValue().closeLinks();
        }
    }

    Integer sendMessage(Message message) throws IllegalStateException, IllegalArgumentException
    {
        if (this.deviceClientConfig.getDeviceId().equals(message.getConnectionDeviceId())) {
            MessageImpl protonMessage = this.convertToProton(message);
            return this.sendMessage(protonMessage, message.getMessageType());
        }

        return -1;
    }


    private Integer sendMessage(MessageImpl protonMessage, MessageType messageType) throws IllegalStateException, IllegalArgumentException
    {
        byte[] msgData = new byte[1024];
        int length;

        while (true)
        {
            try
            {
                length = protonMessage.encode(msgData, 0, msgData.length);
                break;
            }
            catch (BufferOverflowException e)
            {
                msgData = new byte[msgData.length * 2];
            }
        }

        byte[] deliveryTag = String.valueOf(this.nextTag).getBytes();

        //want to avoid negative delivery tags since -1 is the designated failure value
        if (this.nextTag == Integer.MAX_VALUE || this.nextTag < 0)
        {
            this.nextTag = 0;
        }
        else
        {
            this.nextTag++;
        }

        if (amqpsLinkMap.get(messageType) != null)
        {
            AmqpsSendReturnValue amqpsSendReturnValue = amqpsLinkMap.get(messageType).sendMessageAndGetDeliveryTag(messageType, msgData, 0, length, deliveryTag);
            if (amqpsSendReturnValue.isDeliverySuccessful())
            {
                return Integer.parseInt(new String(amqpsSendReturnValue.getDeliveryTag()));
            }
        }

        return -1;
    }

    MessageImpl convertToProton(Message message)
    {
        if (message.getMessageType() == null)
        {
            message.setMessageType(DEVICE_TELEMETRY);
        }

        MessageType messageType = message.getMessageType();
        if (amqpsLinkMap.get(messageType) != null)
        {
            return this.amqpsLinkMap.get(messageType).iotHubMessageToProtonMessage(message);
        }
        else
        {
            return null;
        }
    }

    public String getDeviceId()
    {
        return this.deviceClientConfig.getDeviceId();
    }

    public void subscribeToMessageType(MessageType messageType)
    {
        if (messageType == DEVICE_METHODS && !this.amqpsLinkMap.keySet().contains(DEVICE_METHODS))
        {
            this.amqpsLinkMap.put(DEVICE_METHODS, new AmqpsMethodsLinksHandler(this.deviceClientConfig));
            this.openLinks();
        }
        if (messageType == DEVICE_TWIN && !this.amqpsLinkMap.keySet().contains(DEVICE_TWIN))
        {
            this.amqpsLinkMap.put(DEVICE_TWIN, new AmqpsTwinLinksHandler(this.deviceClientConfig));
            this.openLinks();
        }
    }

    @Override
    public void onSessionRemoteOpen(Event e)
    {
        if (this.deviceClientConfig.getAuthenticationType() == DeviceClientConfig.AuthType.X509_CERTIFICATE)
        {
            this.openLinks();
        }
        else
        {
            // do nothing, can't open links until cbs links are open, and authentication messages have been sent
        }
    }

    @Override
    public void onSessionLocalOpen(Event e)
    {
        //TODO check out what the eventhub folks did here
    }

    @Override
    public void onSessionRemoteClose(Event e)
    {
        //TODO check out what the eventhub folks did here
        Session session = e.getSession();
        log.debug("Amqp session closed remotely for device {}", this.getDeviceId());
        if (session.getLocalState() == EndpointState.ACTIVE)
        {
            // Service initiated this session close
            session.close();
            for (AmqpsLinksHandler linksHandler : amqpsLinkMap.values()) {
                linksHandler.closeLinks();
            }

            if (session.getConnection().getCondition() == null)
            {
                //If the connection local condition hasn't been populated yet, then the error was on the session level.
                // Set the local condition of the connection so that the connection layer can retrieve it when building the
                // transport exception
                session.getConnection().setCondition(session.getRemoteCondition());
            }

        }
        else
        {
            // Client initiated this session close
        }

        session.getConnection().close();
    }

    @Override
    public void onSessionLocalClose(Event e)
    {
        log.debug("Amqp session closed locally for device {}", this.getDeviceId());
//TODO check out what the eventhub folks did here
        for (AmqpsLinksHandler linksHandler : amqpsLinkMap.values()) {
            linksHandler.closeLinks();
        }
    }

    @Override
    public void onLinksOpened(AmqpsLinksHandler linksHandler) {
        this.amqpsSessionStateCallback.onDeviceSessionOpened(this.getDeviceId());

        if (linksHandler instanceof AmqpsTwinLinksHandler)
        {
            int deliveryTag = this.sendMessage(AmqpsTwinLinksHandler.buildSubscribeToDesiredPropertiesProtonMessage(), DEVICE_TWIN);

            if (deliveryTag == -1)
            {
                log.warn("Failed to send desired properties subscription message");
            }
            else
            {
                this.inProgressSubscriptionMessages.put(deliveryTag, SubscriptionType.DESIRED_PROPERTIES_SUBSCRIPTION);
            }
        }

    }

    @Override
    public void onMessageAcknowledged(int deliveryTag) {
        if (this.inProgressSubscriptionMessages.containsKey(deliveryTag))
        {
            this.inProgressSubscriptionMessages.remove(deliveryTag);
            log.trace("The acknowledged message was the desired properties subscription message");
        }
        else
        {
            this.amqpsSessionStateCallback.onMessageAcknowledged(deliveryTag);
        }
    }

    @Override
    public void onMessageReceived(IotHubTransportMessage message) {
        this.amqpsSessionStateCallback.onMessageReceived(message);
    }

    public boolean acknowledgeReceivedMessage(IotHubTransportMessage message, DeliveryState ackType)
    {
        for (AmqpsLinksHandler linksHandler : amqpsLinkMap.values())
        {
            if (linksHandler.acknowledgeReceivedMessage(message, ackType))
            {
                return true;
            }
        }

        return false;
    }
}

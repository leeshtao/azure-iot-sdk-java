/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.DeviceClientConfig;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.engine.*;

import javax.net.ssl.SSLContext;

@Slf4j
public class CbsSessionHandler extends BaseHandler implements AmqpsLinkStateCallback
{
    //TODO how much of this class can just extend from the AmqpsSessionHandler? Looks like you just need the client config
    // in the base class to be set after constructor time. Otherwise looks the same
    private Session session;
    private AmqpsCbsLinksHandler cbsLinkHandler;
    private AmqpsSessionStateCallback connectionStateCallback;

    public CbsSessionHandler(Session session, AmqpsSessionStateCallback connectionStateCallback)
    {
        this.session = session;
        this.connectionStateCallback = connectionStateCallback;
    }

    @Override
    public void onSessionLocalOpen(Event event)
    {
        this.session = event.getSession();
        cbsLinkHandler = new AmqpsCbsLinksHandler(this);

        Sender cbsSender = this.session.sender(cbsLinkHandler.getSenderLinkTag());
        Receiver cbsReceiver = this.session.receiver(cbsLinkHandler.getReceiverLinkTag());

        cbsLinkHandler.assignLinks(cbsSender, cbsReceiver, this);

        BaseHandler.setHandler(cbsSender, cbsLinkHandler);
        BaseHandler.setHandler(cbsReceiver, cbsLinkHandler);
    }

    @Override
    public void onSessionRemoteOpen(Event e)
    {
        //TODO check out what the eventhub folks did here
    }

    @Override
    public void onSessionLocalClose(Event e)
    {
        //TODO check out what the eventhub folks did here
    }

    @Override
    public void onSessionRemoteClose(Event e)
    {
        Session session = e.getSession();
        log.debug("Amqp CBS session closed remotely");
        if (session.getLocalState() == EndpointState.ACTIVE)
        {
            //Service initiated this session close

            //TODO is the session error propagated up to the connection level through proton?

            if (session.getConnection().getCondition() == null)
            {
                //If the connection local condition hasn't been populated yet, then the error was on the session level.
                // Set the local condition of the connection so that the connection layer can retrieve it when building the
                // transport exception
                session.getConnection().setCondition(session.getRemoteCondition());
            }

            this.session.close();
        }
        else
        {
            //Client initiated this session close, nothing to do here
        }

        // Regardless of who initiated the session close, close this sessions links if they are not already closed
        this.cbsLinkHandler.closeLinks();

        session.getConnection().close();
    }

    public void sendAuthenticationMessage(DeviceClientConfig deviceClientConfig, AuthenticationMessageCallback authenticationMessageCallback) throws TransportException
    {
        cbsLinkHandler.sendAuthenticationMessage(deviceClientConfig, authenticationMessageCallback);
    }

    public void setSslDomain(Transport transport, SSLContext sslContext)
    {
        cbsLinkHandler.setSslDomain(transport, sslContext);
    }

    @Override
    public void onLinksOpened(AmqpsLinksHandler amqpsLinksHandler) {
        // No need to worry about the amqpsLinksHandler parameter. That is provided at the interface level so that
        // sessions with multiple pairs of links can identify which pair of links opened. For this CBS session,
        // there will only ever be the one pair of links.

        this.connectionStateCallback.onAuthenticationSessionOpened();
    }

    @Override
    public void onMessageAcknowledged(int deliveryTag) {
        // Do nothing. Users of this SDK don't care about this ack, and the SDK doesn't open any links or sessions
        // upon receiving this ack. The CBS receiver link receives a message with the actual status of the authentication.
    }

    @Override
    public void onMessageReceived(IotHubTransportMessage message) {
        this.connectionStateCallback.onMessageReceived(message);
    }

    public void onAuthenticationFailed(TransportException transportException)
    {
        this.connectionStateCallback.onAuthenticationFailed(transportException);
    }

    public void close() {
        this.session.close();
    }
}

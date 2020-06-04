/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.proton.transport.proxy.ProxyAuthenticationType;
import com.microsoft.azure.proton.transport.proxy.ProxyConfiguration;
import com.microsoft.azure.proton.transport.proxy.ProxyHandler;
import com.microsoft.azure.proton.transport.proxy.impl.ProxyHandlerImpl;
import com.microsoft.azure.proton.transport.proxy.impl.ProxyImpl;
import com.microsoft.azure.proton.transport.ws.impl.WebSocketImpl;
import com.microsoft.azure.sdk.iot.deps.transport.amqp.ErrorLoggingBaseHandler;
import com.microsoft.azure.sdk.iot.device.*;
import com.microsoft.azure.sdk.iot.device.auth.IotHubSasTokenAuthenticationProvider;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.Proton;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Released;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.engine.*;
import org.apache.qpid.proton.engine.impl.TransportInternal;
import org.apache.qpid.proton.reactor.FlowController;
import org.apache.qpid.proton.reactor.Handshaker;
import org.apache.qpid.proton.reactor.Reactor;
import org.apache.qpid.proton.reactor.ReactorOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.*;

import static com.microsoft.azure.sdk.iot.device.DeviceTwin.DeviceOperations.*;
import static com.microsoft.azure.sdk.iot.device.MessageType.DEVICE_METHODS;
import static com.microsoft.azure.sdk.iot.device.MessageType.DEVICE_TWIN;

/**
 * An AMQPS IotHub connection between a device and an IoTHub. This class contains functionality for sending/receiving
 * a message, and logic to re-establish the connection with the IoTHub in case it gets lost.
 */
@Slf4j
public final class AmqpsIotHubConnection extends ErrorLoggingBaseHandler implements IotHubTransportConnection, AmqpsSessionStateCallback
{
    // Timeouts
    private static final int MAX_WAIT_TO_CLOSE_CONNECTION = 60 * 1000; // 60 second timeout
    private static final int MAX_WAIT_TO_OPEN_AUTHENTICATION_SESSION = 20 * 1000; // 20 second timeout
    private static final int MAX_WAIT_TO_OPEN_WORKER_SESSIONS = 60 * 1000; // 60 second timeout
    private static final int MAX_WAIT_TO_TERMINATE_EXECUTOR = 30;

    // Web socket constants
    private static final String WEB_SOCKET_PATH = "/$iothub/websocket";
    private static final String WEB_SOCKET_SUB_PROTOCOL = "AMQPWSB10";
    private static final String WEB_SOCKET_QUERY = "iothub-no-client-cert=true";
    private static final int MAX_MESSAGE_PAYLOAD_SIZE = 256*1024; //max IoT Hub message size is 256 kb, so amqp websocket layer should buffer at most that much space
    private static final int WEB_SOCKET_PORT = 443;

    private static final int AMQP_PORT = 5671;
    private static final int REACTOR_COUNT = 1;
    private static final int CBS_SESSION_COUNT = 1; //even for multiplex scenarios

    // Message send constants
    private static final int SEND_MESSAGES_PERIOD_MILLIS = 50; //every 50 milliseconds, the method onTimerTask will fire to send, at most, MAX_MESSAGES_TO_SEND_PER_CALLBACK queued messages
    private static final int MAX_MESSAGES_TO_SEND_PER_CALLBACK = 1000; //Max number of queued messages to send per periodic sending task

    public String connectionId;
    private IotHubConnectionStatus state;
    private String hostName;
    private DeviceClientConfig deviceClientConfig;
    private IotHubListener listener;
    private TransportException savedException;
    private boolean reconnectionScheduled = false;
    private ExecutorService executorService;

    // State latches are used for asynchronous open and close operations
    private CountDownLatch authenticationSessionOpenedLatch; // tracks if the authentication session has opened yet or not
    private CountDownLatch deviceSessionsOpenedLatch; // tracks if all expected device sessions have opened yet or not
    private CountDownLatch closeReactorLatch; // tracks if the reactor has been closed yet or not

    // States of outgoing messages, incoming messages, and outgoing subscriptions
    private final Queue<com.microsoft.azure.sdk.iot.device.Message> messagesToSend = new ConcurrentLinkedQueue<>();
    private final Map<Integer, com.microsoft.azure.sdk.iot.device.Message> inProgressMessages = new ConcurrentHashMap<>();

    // Proton-j primitives and wrappers for the device and authentication sessions
    private Reactor reactor;
    private Connection connection;
    private ArrayList<AmqpsSessionHandler> sessionHandlerList = new ArrayList<>();
    private ArrayList<SasTokenRenewalHandler> sasTokenRenwalHandlerList = new ArrayList<>();
    private CbsSessionHandler cbsSessionHandler;

    /**
     * Constructor to set up connection parameters using the {@link DeviceClientConfig}.
     *
     * @param config The {@link DeviceClientConfig} corresponding to the device associated with this {@link com.microsoft.azure.sdk.iot.device.DeviceClient}.
     */
    public AmqpsIotHubConnection(DeviceClientConfig config)
    {
        if (config == null)
        {
            throw new IllegalArgumentException("The DeviceClientConfig cannot be null.");
        }
        if (config.getIotHubHostname() == null || config.getIotHubHostname().length() == 0)
        {
            throw new IllegalArgumentException("hostName cannot be null or empty.");
        }
        if (config.getDeviceId() == null || config.getDeviceId().length() == 0)
        {
            throw new IllegalArgumentException("deviceID cannot be null or empty.");
        }
        if (config.getIotHubName() == null || config.getIotHubName().length() == 0)
        {
            throw new IllegalArgumentException("hubName cannot be null or empty.");
        }

        this.deviceClientConfig = config;

        this.hostName = this.chooseHostname();

        add(new Handshaker());
        add(new FlowController());

        this.state = IotHubConnectionStatus.DISCONNECTED;
        log.trace("AmqpsIotHubConnection object is created successfully and will use port {}", this.deviceClientConfig.isUseWebsocket() ? WEB_SOCKET_PORT : AMQP_PORT);
    }

    /**
     * Opens the {@link AmqpsIotHubConnection}.
     * <p>
     * This method will start the {@link Reactor}, set the connection to open and make it ready for sending.
     * </p>
     *
     * <p>
     * Do not call this method after calling close on this object, instead, create a whole new AmqpsIotHubConnection
     * object and open that instead.
     * </p>
     *
     * @throws TransportException If the reactor could not be initialized.
     */
    public void open(Queue<DeviceClientConfig> deviceClientConfigs, ScheduledExecutorService scheduledExecutorService) throws TransportException
    {
        this.log.debug("Opening amqp layer...");
        reconnectionScheduled = false;
        connectionId = UUID.randomUUID().toString();

        this.savedException = null;

        if (this.state == IotHubConnectionStatus.DISCONNECTED)
        {
            for (DeviceClientConfig clientConfig : deviceClientConfigs)
            {
                this.addDeviceSession(clientConfig, false);
            }

            initializeStateLatches();

            try
            {
                this.openAsync();

                this.log.trace("Waiting for authentication links to open...");
                boolean authenticationSessionOpenTimedOut = !this.authenticationSessionOpenedLatch.await(MAX_WAIT_TO_OPEN_AUTHENTICATION_SESSION, TimeUnit.MILLISECONDS);

                if (this.savedException != null)
                {
                    throw this.savedException;
                }

                if (authenticationSessionOpenTimedOut)
                {
                    closeConnectionWithException("Timed out waiting for authentication session to open", true);
                }

                this.log.trace("Waiting for device sessions to open...");
                boolean deviceSessionsOpenTimedOut = !this.deviceSessionsOpenedLatch.await(MAX_WAIT_TO_OPEN_WORKER_SESSIONS, TimeUnit.MILLISECONDS);

                if (this.savedException != null)
                {
                    throw this.savedException;
                }

                if (deviceSessionsOpenTimedOut)
                {
                    closeConnectionWithException("Timed out waiting for worker links to open", true);
                }
            }
            catch (InterruptedException e)
            {
                executorServicesCleanup();
                log.error("Interrupted while waiting for links to open for AMQP connection", e);
                throw new TransportException("Interrupted while waiting for links to open for AMQP connection", e);
            }
        }

        this.state = IotHubConnectionStatus.CONNECTED;
        this.listener.onConnectionEstablished(this.connectionId);

        this.log.debug("Amqp connection opened successfully");
    }

    private void initializeStateLatches()
    {
        this.closeReactorLatch = new CountDownLatch(REACTOR_COUNT);

        if (deviceClientConfig.getAuthenticationProvider() instanceof IotHubSasTokenAuthenticationProvider)
        {
            this.log.trace("Initializing authentication link latch count to {}", CBS_SESSION_COUNT);
            this.authenticationSessionOpenedLatch = new CountDownLatch(CBS_SESSION_COUNT);
        }
        else
        {
            this.log.trace("Initializing authentication link latch count to 0 because x509 connections don't have authentication links");
            this.authenticationSessionOpenedLatch = new CountDownLatch(0);
        }

        int expectedDeviceSessionCount = sessionHandlerList.size();
        this.deviceSessionsOpenedLatch = new CountDownLatch(expectedDeviceSessionCount);
        this.log.trace("Initializing device session latch count to {}", expectedDeviceSessionCount);
    }

    private void closeConnectionWithException(String errorMessage, boolean isRetryable) throws TransportException
    {
        TransportException transportException = new TransportException(errorMessage);
        transportException.setRetryable(isRetryable);
        this.log.error(errorMessage, transportException);

        this.close();
        throw transportException;
    }

    /**
     * Private helper for open.
     * Starts the Proton reactor.
     */
    private void openAsync() throws TransportException
    {
        this.log.trace("OpenAsnyc called for amqp connection");
        if (this.reactor == null)
        {
            this.reactor = createReactor();
        }

        if (executorService == null)
        {
            executorService = Executors.newFixedThreadPool(1);
        }

        ReactorRunner reactorRunner = new ReactorRunner(new IotHubReactor(reactor), this.listener, this.connectionId);
        executorService.submit(reactorRunner);
    }

    /**
     * Closes the {@link AmqpsIotHubConnection}.
     * <p>
     * If the current connection is not closed, this function
     * will set the current state to closed and invalidate all connection related variables.
     * </p>
     *
     * @throws TransportException if it failed closing the iothub connection.
     */
    public void close() throws TransportException
    {
        this.log.debug("Shutting down amqp layer...");
        closeAsync();

        try
        {
            closeReactorLatch.await(MAX_WAIT_TO_CLOSE_CONNECTION, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            log.warn("Interrupted while closing proton reactor", e);
            throw new TransportException("Waited too long for the connection to close.", e);
        }

        this.executorServicesCleanup();

        this.log.trace("Amqp connection closed successfully");
        this.state = IotHubConnectionStatus.DISCONNECTED;
    }

    private void executorServicesCleanup() throws TransportException
    {
        if (this.executorService != null)
        {
            log.trace("Shutdown of executor service has started");
            this.executorService.shutdown();
            try
            {
                // Wait a while for existing tasks to terminate
                if (!this.executorService.awaitTermination(MAX_WAIT_TO_TERMINATE_EXECUTOR, TimeUnit.SECONDS))
                {
                    this.executorService.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!this.executorService.awaitTermination(MAX_WAIT_TO_TERMINATE_EXECUTOR, TimeUnit.SECONDS))
                    {
                        log.trace("Pool did not terminate");
                    }
                }

                this.executorService = null;
            }
            catch (InterruptedException e)
            {
                log.warn("Interrupted while cleaning up executor services", e);
                // (Re-)Cancel if current thread also interrupted
                this.executorService.shutdownNow();
                this.executorService = null;
                throw new TransportException("Waited too long for the connection to close.", e);
            }
            log.trace("Shutdown of executor service completed");
        }
    }

    /**
     * Private helper for close.
     * Closes the AmqpsIotHubConnection, the connection and stops the Proton reactor.
     */
    private void closeAsync()
    {
        this.log.debug("Closing amqp connection...");
        this.connection.close();

        for (AmqpsSessionHandler sessionHandler : this.sessionHandlerList)
        {
            sessionHandler.close();
        }

        this.log.trace("Closing amqp authentication links");

        if (this.reactor != null)
        {
            this.reactor.stop();
        }

        log.trace("Proton reactor has been stopped");
    }

    @Override
    public void onReactorInit(Event event)
    {
        Reactor reactor = event.getReactor();

        String hostName = this.hostName;
        int port = AMQP_PORT;

        if (this.deviceClientConfig.isUseWebsocket())
        {
            ProxySettings proxySettings = this.deviceClientConfig.getProxySettings();
            if (proxySettings != null)
            {
                hostName = proxySettings.getHostname();
                port = proxySettings.getPort();
            }
            else
            {
                port = WEB_SOCKET_PORT;
            }
        }

        reactor.connectionToHost(hostName, port, this);
        reactor.schedule(SEND_MESSAGES_PERIOD_MILLIS, this);
    }

    @Override
    public void onReactorFinal(Event event)
    {
        releaseLatch(authenticationSessionOpenedLatch);
        releaseLatch(deviceSessionsOpenedLatch);
        releaseLatch(closeReactorLatch);

        this.reactor = null;
    }

    @Override
    public void onConnectionInit(Event event)
    {
        this.connection = event.getConnection();
        this.connection.setHostname(hostName);
        this.connection.open();

        //Create one session per multiplexed device, or just one session if not multiplexing
        if (this.deviceClientConfig.getAuthenticationType() == DeviceClientConfig.AuthType.SAS_TOKEN)
        {
            Session cbsSession = connection.session();
            cbsSession.open();

            cbsSessionHandler = new CbsSessionHandler(cbsSession, this);
            BaseHandler.setHandler(cbsSession, cbsSessionHandler);

            // Open a device session per device, and create a sas token renewal handler for each device session
            for (AmqpsSessionHandler amqpsSessionHandler : this.sessionHandlerList)
            {
                amqpsSessionHandler.session = connection.session();
                amqpsSessionHandler.session.open();
                BaseHandler.setHandler(amqpsSessionHandler.session, amqpsSessionHandler);


                // Sas token renewal handler list lives between reconnection attempts, so it may not be necessary to create
                // a new one for this device session.
                SasTokenRenewalHandler sasTokenRenewalHandler = null;
                for (SasTokenRenewalHandler existingSasTokenRenewalHandler : sasTokenRenwalHandlerList) {
                    if (existingSasTokenRenewalHandler.amqpsSessionHandler.equals(amqpsSessionHandler))
                    {
                        sasTokenRenewalHandler = existingSasTokenRenewalHandler;
                        break;
                    }
                }

                if (sasTokenRenewalHandler == null)
                {
                    sasTokenRenewalHandler = new SasTokenRenewalHandler(cbsSessionHandler, amqpsSessionHandler);
                    sasTokenRenwalHandlerList.add(sasTokenRenewalHandler);
                }

                sasTokenRenewalHandler.scheduleRenewal(event.getReactor());
            }
        }
        else
        {
            // should only be one session since x509 doesn't support multiplexing
            AmqpsSessionHandler amqpsSessionHandler = this.sessionHandlerList.get(0);
            amqpsSessionHandler.session = connection.session();
            amqpsSessionHandler.session.open();
            BaseHandler.setHandler(amqpsSessionHandler.session, amqpsSessionHandler);
        }
    }

    @Override
    public void onConnectionBound(Event event)
    {
        Transport transport = event.getTransport();

        if (this.deviceClientConfig.isUseWebsocket())
        {
            addWebSocketLayer(transport);
        }

        try
        {
            if (this.deviceClientConfig.getAuthenticationType() == DeviceClientConfig.AuthType.SAS_TOKEN)
            {
                this.cbsSessionHandler.setSslDomain(transport, this.deviceClientConfig.getAuthenticationProvider().getSSLContext());
            }
            else
            {
                SslDomain domain = Proton.sslDomain();
                domain.setSslContext(this.deviceClientConfig.getAuthenticationProvider().getSSLContext());
                domain.setPeerAuthentication(SslDomain.VerifyMode.VERIFY_PEER);
                domain.init(SslDomain.Mode.CLIENT);
                transport.ssl(domain);
            }
        }
        catch (IOException e)
        {
            this.savedException = new TransportException(e);
            log.error("Encountered an exception while setting ssl domain for the amqp connection", this.savedException);
        }

        // Adding proxy layer needs to be done after sending SSL message
        if (this.deviceClientConfig.getProxySettings() != null)
        {
            this.log.debug("Proxy settings present, adding proxy layer to amqp connection");
            addProxyLayer(transport, event.getConnection().getHostname() + ":" + WEB_SOCKET_PORT);
        }
    }

    @Override
    public void onConnectionLocalOpen(Event event)
    {
        //TODO
    }

    @Override
    public void onConnectionRemoteOpen(Event event)
    {
        //TODO
    }

    @Override
    public void onConnectionLocalClose(Event event)
    {
        Connection connection = event.getConnection();
        if (connection.getRemoteState() == EndpointState.CLOSED)
        {
            //Service initiated this connection close

            //Only stop the reactor once the connection is closed locally and remotely
            log.trace("Stopping reactor now that amqp connection is closed locally and remotely");
            event.getReactor().stop();
        }
        else
        {
            // TODO nothing to do here? probably double check that
        }

        log.debug("Amqp connection closed locally, shutting down any active sessions...");

        for (AmqpsSessionHandler amqpSessionHandler : sessionHandlerList) {
            amqpSessionHandler.close();
        }

        // cbs session handler is only null if using x509 auth
        if (cbsSessionHandler != null)
        {
            cbsSessionHandler.close();
        }
    }

    @Override
    public void onConnectionRemoteClose(Event event)
    {
        if (this.connection.getLocalState() == EndpointState.ACTIVE)
        {
            // If local state is currently active, then the service initiated the connection close
            this.savedException = AmqpsExceptionTranslator.convertToAmqpException(event.getConnection().getRemoteCondition());
            log.error("Amqp connection was closed remotely", this.savedException);
            this.connection.close();
            this.scheduleReconnection(this.savedException);
        }
        else
        {
            //The local condition should hold any errorCondition that happened in the link or session level
            // but the remote error condition may not be empty, too? Maybe prefer remote over local, if remote is present
            ErrorCondition remoteErrorCondition = event.getConnection().getRemoteCondition();
            ErrorCondition errorCondition = event.getConnection().getCondition();

            if (remoteErrorCondition != null)
            {
                errorCondition = remoteErrorCondition;
            }

            TransportException transportException = AmqpsExceptionTranslator.convertToAmqpException(errorCondition);
            this.scheduleReconnection(transportException);

            // Only stop the reactor once the connection is closed locally and remotely
            log.trace("Stopping reactor now that amqp connection is closed locally and remotely");
            event.getReactor().stop();
        }
    }

    @Override
    public void onTransportError(Event event)
    {
        super.onTransportError(event);
        this.state = IotHubConnectionStatus.DISCONNECTED;

        this.savedException = AmqpsExceptionTranslator.convertToAmqpException(event.getTransport().getRemoteCondition());

        log.error("Amqp transport error", this.savedException);

        this.scheduleReconnection(this.savedException);
    }

    @Override
    public void onTimerTask(Event event)
    {
        sendQueuedMessages();

        event.getReactor().schedule(SEND_MESSAGES_PERIOD_MILLIS, this);
    }

    @Override
    public void setListener(IotHubListener listener) throws IllegalArgumentException
    {
        if (listener == null)
        {
            throw new IllegalArgumentException("listener cannot be null");
        }

        this.listener = listener;
    }

    @Override
    public IotHubStatusCode sendMessage(com.microsoft.azure.sdk.iot.device.Message message)
    {
        // Note that you cannot just send this message from this thread. Proton-j's reactor is not thread safe. As such,
        // all message sending must be done from the proton-j thread that is exposed to this SDK through callbacks
        // such as onLinkFlow(), or onTimerTask()
        this.log.trace("Adding message to amqp message queue to be sent later ({})", message);
        messagesToSend.add(message);
        return IotHubStatusCode.OK;
    }

    /**
     * Sends the Ack for the provided message with the result
     *
     * @param message the message to acknowledge
     * @param result  the result to attach to the ack (COMPLETE, ABANDON, or REJECT)
     * @return true if the ack was sent successfully, and false otherwise
     */
    @Override
    public boolean sendMessageResult(IotHubTransportMessage message, IotHubMessageResult result)
    {
        DeliveryState ackType;

        // Complete/Abandon/Reject is an IoTHub specific concept. For AMQP, they map to Accepted/Released/Rejected.
        if (result == IotHubMessageResult.ABANDON)
        {
            ackType = Released.getInstance();
        }
        else if (result == IotHubMessageResult.REJECT)
        {
            ackType = new Rejected();
        }
        else if (result == IotHubMessageResult.COMPLETE)
        {
            ackType = Accepted.getInstance();
        }
        else
        {
            log.warn("Invalid IoT Hub message result {}", result.name());
            return false;
        }

        //Check each session handler to see who is responsible for sending this acknowledgement
        for (AmqpsSessionHandler sessionHandler : sessionHandlerList)
        {
            if (sessionHandler.acknowledgeReceivedMessage(message, ackType))
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public String getConnectionId()
    {
        return this.connectionId;
    }

    @Override
    public void onDeviceSessionOpened(String deviceId)
    {
        this.deviceSessionsOpenedLatch.countDown();
    }

    @Override
    public void onAuthenticationSessionOpened() {
        this.authenticationSessionOpenedLatch.countDown();

        if (this.deviceClientConfig.getAuthenticationType() == DeviceClientConfig.AuthType.SAS_TOKEN)
        {
            for (SasTokenRenewalHandler sasTokenRenewalHandler : sasTokenRenwalHandlerList)
            {
                try
                {
                    sasTokenRenewalHandler.sendAuthenticationMessage();
                }
                catch (TransportException e)
                {
                    log.error("Failed to send CBS authentication message", e);
                    this.savedException = e;
                }
            }
        }
    }

    @Override
    public void onMessageAcknowledged(int deliveryTag) {
        // This is a poll operation. If the delivery tag isn't present in the map, then .remove() will return null, not throw
        Message acknowledgedMessage = inProgressMessages.remove(deliveryTag);

        if (acknowledgedMessage == null)
        {
            log.warn("Received acknowledgement for a message that this client did not send");
        }
        else
        {
            this.listener.onMessageSent(acknowledgedMessage, null);
        }
    }

    @Override
    public void onMessageReceived(IotHubTransportMessage message) {
        this.listener.onMessageReceived(message, null);
    }

    @Override
    public void onAuthenticationFailed(TransportException transportException) {
        this.savedException = transportException;
        releaseLatch(authenticationSessionOpenedLatch);
        releaseLatch(deviceSessionsOpenedLatch);
    }

    private void addWebSocketLayer(Transport transport)
    {
        WebSocketImpl webSocket = new WebSocketImpl(MAX_MESSAGE_PAYLOAD_SIZE);
        webSocket.configure(this.hostName, WEB_SOCKET_PATH, WEB_SOCKET_QUERY, WEB_SOCKET_PORT, WEB_SOCKET_SUB_PROTOCOL, null, null);
        ((TransportInternal) transport).addTransportLayer(webSocket);
    }

    private void addProxyLayer(Transport transport, String hostName)
    {
        ProxySettings proxySettings = this.deviceClientConfig.getProxySettings();

        ProxyImpl proxy;

        if (proxySettings.getUsername() != null && proxySettings.getPassword() != null)
        {
            log.trace("Adding proxy username and password to amqp proxy configuration");
            ProxyConfiguration proxyConfiguration = new ProxyConfiguration(ProxyAuthenticationType.BASIC, proxySettings.getProxy(), proxySettings.getUsername(), new String(proxySettings.getPassword()));
            proxy = new ProxyImpl(proxyConfiguration);
        }
        else
        {
            log.trace("No proxy username and password will be used amqp proxy configuration");
            proxy = new ProxyImpl();
        }

        final ProxyHandler proxyHandler = new ProxyHandlerImpl();
        proxy.configure(hostName, null, proxyHandler, transport);
        ((TransportInternal) transport).addTransportLayer(proxy);
    }

    private void sendQueuedMessages()
    {
        int messagesAttemptedToBeProcessed = 0;
        int lastDeliveryTag = 0;
        Message message = messagesToSend.poll();
        while (message != null && messagesAttemptedToBeProcessed < MAX_MESSAGES_TO_SEND_PER_CALLBACK && lastDeliveryTag >= 0)
        {
            if (!subscriptionChangeHandler(message))
            {
                messagesAttemptedToBeProcessed++;
                lastDeliveryTag = sendQueuedMessage(message);

                if (lastDeliveryTag == -1)
                {
                    //message failed to send, likely due to lack of link credit available. Re-queue and try again later
                    this.log.trace("Amqp message failed to send, adding it back to messages to send queue ({})", message);
                    messagesToSend.add(message);
                }
            }

            message = messagesToSend.poll();
        }

        if (message != null)
        {
            //message was polled out of list, but loop exited from processing too many messages before it could process this message, so re-queue it for later
            messagesToSend.add(message);
        }
    }

    private int sendQueuedMessage(Message message)
    {
        int lastDeliveryTag = -1;

        this.log.trace("Sending message over amqp ({})", message);

        for (AmqpsSessionHandler sessionHandler : this.sessionHandlerList)
        {
            lastDeliveryTag = sessionHandler.sendMessage(message);

            if (lastDeliveryTag != -1)
            {
                //Message was sent by its correct session, no need to keep looping to find the right session
                this.inProgressMessages.put(lastDeliveryTag, message);
                this.log.trace("Amqp message was sent, adding amqp delivery tag {} to in progress messages ({})", lastDeliveryTag, message);
                break;
            }
        }

        return lastDeliveryTag;
    }

    /**
     * Create a Proton reactor
     *
     * @return the Proton reactor
     * @throws TransportException if Proton throws
     */
    private Reactor createReactor() throws TransportException
    {
        try
        {
            if (this.deviceClientConfig.getAuthenticationType() == DeviceClientConfig.AuthType.X509_CERTIFICATE)
            {
                //Codes_SRS_AMQPSIOTHUBCONNECTION_34_053: [If the config is using x509 Authentication, the created Proton reactor shall not have SASL enabled by default.]
                ReactorOptions options = new ReactorOptions();
                options.setEnableSaslByDefault(false);
                return Proton.reactor(options, this);
            }
            else
            {
                return Proton.reactor(this);
            }
        }
        catch (IOException e)
        {
            throw new TransportException("Could not create Proton reactor", e);
        }
    }

    /**
     * Schedules a thread to start the reconnection process for AMQP
     *
     * @param throwable the reason why the reconnection needs to take place, for reporting purposes
     */
    private void scheduleReconnection(Throwable throwable)
    {
        this.log.warn("Amqp connection was closed, creating a thread to notify transport layer", throwable);
        if (!reconnectionScheduled)
        {
            reconnectionScheduled = true;
            ReconnectionNotifier.notifyDisconnectAsync(throwable, this.listener, this.connectionId);
        }
    }

    private String chooseHostname()
    {
        String gatewayHostname = this.deviceClientConfig.getGatewayHostname();
        if (gatewayHostname != null && !gatewayHostname.isEmpty())
        {
            log.debug("Gateway hostname was present in config, connecting to gateway rather than directly to hub");
            return gatewayHostname;
        }

        log.debug("No gateway hostname was present in config, connecting directly to hub");
        return this.deviceClientConfig.getIotHubHostname();
    }

    private boolean subscriptionChangeHandler(com.microsoft.azure.sdk.iot.device.Message message)
    {
        boolean handled = false;
        if (message.getMessageType() != null)
        {
            switch (message.getMessageType())
            {
                case DEVICE_METHODS:
                    if (((IotHubTransportMessage) message).getDeviceOperationType() == DEVICE_OPERATION_METHOD_SUBSCRIBE_REQUEST)
                    {
                        this.subscribeDeviceToMessageType(DEVICE_METHODS, message.getConnectionDeviceId());
                        this.listener.onMessageSent(message, null);
                        handled = true;
                    }

                    break;
                case DEVICE_TWIN:
                    if (((IotHubTransportMessage) message).getDeviceOperationType() == DEVICE_OPERATION_TWIN_UNSUBSCRIBE_DESIRED_PROPERTIES_REQUEST)
                    {
                        //TODO: unsubscribe desired property from application
                        //this.amqpSessionManager to sever the connection
                        //twinSubscribed = false;
                    }
                    else if (((IotHubTransportMessage) message).getDeviceOperationType() == DEVICE_OPERATION_TWIN_SUBSCRIBE_DESIRED_PROPERTIES_REQUEST)
                    {
                        this.subscribeDeviceToMessageType(DEVICE_TWIN, message.getConnectionDeviceId());
                        this.listener.onMessageSent(message, null);
                        handled = true;
                    }
                    break;
                default:
                    break;
            }
        }

        return handled;
    }

    private void releaseLatch(CountDownLatch latch)
    {
        for (int i = 0; i < latch.getCount(); i++)
        {
            latch.countDown();
        }
    }

    final void addDeviceSession(DeviceClientConfig deviceClientConfig, boolean afterOpen) {
        if (deviceClientConfig == null)
        {
            throw new IllegalArgumentException("deviceClientConfig cannot be null.");
        }

        // Check if the device session still exists from a previous connection
        AmqpsSessionHandler amqpsSessionHandler = null;
        for (AmqpsSessionHandler existingAmqpsSessionHandler : this.sessionHandlerList)
        {
            if (existingAmqpsSessionHandler.getDeviceId().equals(deviceClientConfig.getDeviceId()))
            {
                amqpsSessionHandler = existingAmqpsSessionHandler;
                break;
            }
        }

        // If the device session did not exist in the previous connection, or if there was no previous connection,
        // create a new session
        if (amqpsSessionHandler == null)
        {
            amqpsSessionHandler = new AmqpsSessionHandler(deviceClientConfig, this);
            this.sessionHandlerList.add(amqpsSessionHandler);
        }

        if (afterOpen)
        {
            amqpsSessionHandler.session = this.connection.session();
            amqpsSessionHandler.session.open();
            BaseHandler.setHandler(amqpsSessionHandler.session, amqpsSessionHandler);
        }
    }

    protected void subscribeDeviceToMessageType(MessageType messageType, String deviceId)
    {
        this.log.trace("Subscribing to {}", messageType);
        for (AmqpsSessionHandler sessionHandler : this.sessionHandlerList)
        {
            if (sessionHandler.getDeviceId().equals(deviceId))
            {
                sessionHandler.subscribeToMessageType(messageType);
                return;
            }
        }
    }

    /**
     * Class which runs the reactor.
     */
    private class ReactorRunner implements Callable
    {
        private static final String THREAD_NAME = "azure-iot-sdk-ReactorRunner";
        private final IotHubReactor iotHubReactor;
        private final IotHubListener listener;
        private String connectionId;

        ReactorRunner(IotHubReactor iotHubReactor, IotHubListener listener, String connectionId)
        {
            this.listener = listener;
            this.iotHubReactor = iotHubReactor;
            this.connectionId = connectionId;
        }

        @Override
        public Object call()
        {
            try
            {
                Thread.currentThread().setName(THREAD_NAME);
                iotHubReactor.run();
            }
            catch (HandlerException e)
            {
                this.listener.onConnectionLost(new TransportException(e), connectionId);
            }

            return null;
        }
    }
}

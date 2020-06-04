package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportMessage;

/**
 * Callback interface for the session handler to callback up to the connection level with status updates
 */
public interface AmqpsSessionStateCallback
{
    /**
     * Called when a device's amqp session has successfully been opened. For multiplexing scenarios, this callback will
     * be fired once per device.
     * @param deviceId the id of the device that had its session opened successfully
     */
    public void onDeviceSessionOpened(String deviceId);

    /**
     * Called when the authentication session has successfully been opened. Never called for x509 auth.
     */
    public void onAuthenticationSessionOpened();

    public void onMessageAcknowledged(int deliveryTag);

    public void onMessageReceived(IotHubTransportMessage message);

    public void onAuthenticationFailed(TransportException transportException);
}

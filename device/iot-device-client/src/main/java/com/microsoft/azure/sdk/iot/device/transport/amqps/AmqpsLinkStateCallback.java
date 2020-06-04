package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.transport.IotHubTransportMessage;

public interface AmqpsLinkStateCallback {
    public void onLinksOpened(AmqpsLinksHandler linksHandler);
    public void onMessageAcknowledged(int deliveryTag);
    public void onMessageReceived(IotHubTransportMessage message);
}

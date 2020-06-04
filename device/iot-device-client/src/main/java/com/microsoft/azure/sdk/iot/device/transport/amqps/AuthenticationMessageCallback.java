package com.microsoft.azure.sdk.iot.device.transport.amqps;

import org.apache.qpid.proton.amqp.transport.DeliveryState;

public interface AuthenticationMessageCallback {
    public DeliveryState handleAuthenticationResponseMessage(int status, String description);
}

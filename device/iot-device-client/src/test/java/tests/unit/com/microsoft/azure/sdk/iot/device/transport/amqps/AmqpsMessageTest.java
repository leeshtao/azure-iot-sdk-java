/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package tests.unit.com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.DeviceClientConfig;
import com.microsoft.azure.sdk.iot.device.MessageType;
import com.microsoft.azure.sdk.iot.device.transport.amqps.AmqpsMessage;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import mockit.Verifications;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Released;
import org.apache.qpid.proton.engine.Delivery;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
*  Unit tests for AmqpsMessage
* 100% methods covered
* 95% lines covered
*/
public class AmqpsMessageTest
{
    @Mocked
    protected Delivery mockDelivery;

    // Tests_SRS_AMQPSMESSAGE_12_001: [Getter for the MessageType.]
    // Tests_SRS_AMQPSMESSAGE_12_002: [Setter for the MessageType.]
    @Test
    public void getSetMessageType()
    {
        //arrange
        MessageType messageType = MessageType.DEVICE_METHODS;
        //act
        AmqpsMessage message = new AmqpsMessage();
        message.setAmqpsMessageType(MessageType.DEVICE_METHODS);

        //assert
        assertTrue(message.getAmqpsMessageType() == messageType);
    }
}

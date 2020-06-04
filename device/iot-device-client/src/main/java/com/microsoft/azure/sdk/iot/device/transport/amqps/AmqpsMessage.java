/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.DeviceClientConfig;
import com.microsoft.azure.sdk.iot.device.MessageType;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.messaging.Rejected;
import org.apache.qpid.proton.amqp.messaging.Released;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.engine.Delivery;
import org.apache.qpid.proton.message.impl.MessageImpl;

/**
 * Extension of the QPID-Proton-J MessageImpl class which implements the Message interface. Adds a Delivery object as a
 * private member variable and adds a new ACK_TYPE enum. Adds the ability to easily acknowledge a single message.
 */
public class AmqpsMessage extends MessageImpl
{
    private Delivery _delivery;

    private MessageType amqpsMessageType;

    /**
     * Sends acknowledgement of this message using the provided ACK_TYPE.
     * @param ackType acknowledgement type to send
     */
    public void acknowledge(DeliveryState ackType)
    {
        _delivery.disposition(ackType);
        _delivery.settle();
    }

    /**
     * Set this AmqpsMessage Delivery Object
     * @param _delivery the new Delivery
     */
    public void setDelivery(Delivery _delivery)
    {
        this._delivery = _delivery;
    }

    /**
     * Get the AmqpsMessageMessageType
     * @return The type of the message
     */
    public MessageType getAmqpsMessageType()
    {
        // Codes_SRS_AMQPSMESSAGE_12_001: [Getter for the MessageType.]
        return amqpsMessageType;
    }

    /**
     * Set the AmqpsMessageMessageType
     *
     * @param amqpsMessageType the new AmqpsMessageMessageType
     */
    public void setAmqpsMessageType(MessageType amqpsMessageType)
    {
        // Codes_SRS_AMQPSMESSAGE_12_002: [Setter for the MessageType.]
        this.amqpsMessageType = amqpsMessageType;
    }
}

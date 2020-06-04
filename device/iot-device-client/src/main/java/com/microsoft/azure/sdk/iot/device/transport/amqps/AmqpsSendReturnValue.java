package com.microsoft.azure.sdk.iot.device.transport.amqps;

public class AmqpsSendReturnValue
{
    private boolean deliverySuccessful;
    private byte[] deliveryTag;

    private byte[] failedDeliveryTag = "-1".getBytes();

    /**
     * Create a return value object containing the delivery status and the delivery hash
     *
     * @param deliverySuccessful the delivery state
     */
    AmqpsSendReturnValue(boolean deliverySuccessful)
    {
        this.deliverySuccessful = deliverySuccessful;
        this.deliveryTag = failedDeliveryTag;
    }

    public AmqpsSendReturnValue(boolean deliverySuccessful, byte[] deliveryTag)
    {
        this.deliverySuccessful = deliverySuccessful;
        this.deliveryTag = deliveryTag;
    }

    public byte[] getDeliveryTag()
    {
        return this.deliveryTag;
    }

    boolean isDeliverySuccessful()
    {
        return deliverySuccessful;
    }
}

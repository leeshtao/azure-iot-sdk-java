package com.microsoft.azure.sdk.iot.device.transport.amqps;

import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.device.exceptions.TransportException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.apache.qpid.proton.amqp.transport.DeliveryState;
import org.apache.qpid.proton.engine.BaseHandler;
import org.apache.qpid.proton.engine.Event;
import org.apache.qpid.proton.reactor.Reactor;

@Slf4j
@AllArgsConstructor
public class AmqpsSasTokenRenewalHandler extends BaseHandler implements AuthenticationMessageCallback
{
    private static final int RETRY_INTERVAL_MILLISECONDS = 5000;

    CbsSessionHandler cbsSessionHandler;
    AmqpsSessionHandler amqpsSessionHandler;

    @Override
    public void onTimerTask(Event event)
    {
        log.trace("onTimerTask fired for sas token renewal handler");
        try
        {
            sendAuthenticationMessage();
        }
        catch (TransportException e)
        {
            log.error("Failed to send the CBS authentication message to authenticate device {}, trying to send again in {} milliseconds", this.amqpsSessionHandler.getDeviceId(), RETRY_INTERVAL_MILLISECONDS);
            scheduleRenewalRetry(event.getReactor());
        }

        scheduleRenewal(event.getReactor());

    }

    public void sendAuthenticationMessage() throws TransportException
    {
        log.debug("Sending authentication message for device {}", amqpsSessionHandler.getDeviceId());
        cbsSessionHandler.sendAuthenticationMessage(amqpsSessionHandler.getDeviceClientConfig(), this);
    }

    @Override
    public DeliveryState handleAuthenticationResponseMessage(int status, String description) {
        if (status == 200)
        {
            log.debug("CBS message authentication succeeded for device {}", this.amqpsSessionHandler.getDeviceId());
            amqpsSessionHandler.openLinks();
            return Accepted.getInstance();
        }
        else
        {
            this.cbsSessionHandler.onAuthenticationFailed(IotHubStatusCode.getConnectionStatusException(IotHubStatusCode.getIotHubStatusCode(status), description));
            return Accepted.getInstance();
        }
    }

    public void scheduleRenewal(Reactor reactor)
    {
        int sasTokenRenewalPeriod = this.amqpsSessionHandler.getDeviceClientConfig().getSasTokenAuthentication().getMillisecondsBeforeProactiveRenewal();
        reactor.schedule(sasTokenRenewalPeriod, this);
    }

    private void scheduleRenewalRetry(Reactor reactor)
    {
        reactor.schedule(RETRY_INTERVAL_MILLISECONDS, this);
    }
}

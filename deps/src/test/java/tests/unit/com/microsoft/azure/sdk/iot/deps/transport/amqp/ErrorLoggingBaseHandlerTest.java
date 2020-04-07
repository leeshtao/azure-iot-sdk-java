/*
*  Copyright (c) Microsoft. All rights reserved.
*  Licensed under the MIT license. See LICENSE file in the project root for full license information.
*/

package tests.unit.com.microsoft.azure.sdk.iot.deps.transport.amqp;

import com.microsoft.azure.sdk.iot.deps.transport.amqp.ErrorLoggingBaseHandler;
import com.microsoft.azure.sdk.iot.deps.transport.amqp.ProtonJExceptionParser;
import mockit.Expectations;
import mockit.Mocked;
import org.apache.qpid.proton.engine.Event;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import com.microsoft.azure.sdk.iot.testcategories.UnitTestCategory;
import com.microsoft.azure.sdk.iot.testcategories.DeviceProvisioningServiceTestCategory;
import com.microsoft.azure.sdk.iot.testcategories.IoTHubTestCategory;

@Category({UnitTestCategory.class, IoTHubTestCategory.class, DeviceProvisioningServiceTestCategory.class})
public class ErrorLoggingBaseHandlerTest
{
    @Mocked Event mockEvent;

    @Mocked
    ProtonJExceptionParser mockProtonJExceptionParser;

    @Test
    public void onTransportErrorParsesError()
    {
        new Expectations()
        {
            {
                new ProtonJExceptionParser(mockEvent);
                result = mockProtonJExceptionParser;

                mockProtonJExceptionParser.getError();
                result = "amqp:io";
            }
        };

        ErrorLoggingBaseHandler errorLoggingBaseHandler = new ErrorLoggingBaseHandler();
        errorLoggingBaseHandler.onTransportError(mockEvent);
    }
}

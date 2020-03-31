/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.android.iothub.twin;

import com.microsoft.appcenter.espresso.Factory;
import com.microsoft.appcenter.espresso.ReportHelper;
import com.microsoft.azure.sdk.iot.android.BuildConfig;
import com.microsoft.azure.sdk.iot.android.helper.TestGroup24;
import com.microsoft.azure.sdk.iot.common.helpers.ClientType;
import com.microsoft.azure.sdk.iot.common.helpers.Rerun;
import com.microsoft.azure.sdk.iot.common.tests.iothub.twin.UpdateTwinTests;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.service.auth.AuthenticationType;

import org.junit.After;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collection;

@TestGroup40
@RunWith(Parameterized.class)
public class UpdateTwinDeviceAndroidRunner extends UpdateTwinTests
{
    @Rule
    public ReportHelper reportHelper = Factory.getReportHelper();

    //This function is run before even the @BeforeClass annotation, so it is used as the @BeforeClass method
    @Parameterized.Parameters(name = "{0}")
    public static Collection inputsCommons() throws IOException, GeneralSecurityException
    {
        iotHubConnectionString = BuildConfig.IotHubConnectionString;
        return UpdateTwinTests.inputsCommon();
    }

    public UpdateTwinDeviceAndroidRunner(IotHubClientProtocol protocol)
    {
        super(protocol);
    }

    @After
    public void labelSnapshot()
    {
        reportHelper.label("Stopping App");
    }
}

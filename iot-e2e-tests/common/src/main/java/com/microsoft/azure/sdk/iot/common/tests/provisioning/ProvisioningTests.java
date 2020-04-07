/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.common.tests.provisioning;

import com.microsoft.azure.sdk.iot.common.helpers.*;
import com.microsoft.azure.sdk.iot.common.setup.provisioning.ProvisioningCommon;
import com.microsoft.azure.sdk.iot.deps.twin.DeviceCapabilities;
import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.Property;
import com.microsoft.azure.sdk.iot.device.DeviceTwin.PropertyCallBack;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.device.IotHubEventCallback;
import com.microsoft.azure.sdk.iot.device.IotHubStatusCode;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientTransportProtocol;
import com.microsoft.azure.sdk.iot.provisioning.security.hsm.SecurityProviderTPMEmulator;
import com.microsoft.azure.sdk.iot.provisioning.service.configs.AllocationPolicy;
import com.microsoft.azure.sdk.iot.provisioning.service.configs.CustomAllocationDefinition;
import com.microsoft.azure.sdk.iot.provisioning.service.configs.ReprovisionPolicy;
import com.microsoft.azure.sdk.iot.provisioning.service.exceptions.ProvisioningServiceClientException;
import com.microsoft.azure.sdk.iot.service.IotHubConnectionString;
import com.microsoft.azure.sdk.iot.service.RegistryManager;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceTwin;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceTwinDevice;
import com.microsoft.azure.sdk.iot.service.devicetwin.Pair;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubNotFoundException;
import com.microsoft.azure.sdk.iot.testcategories.DeviceProvisioningServiceTestCategory;
import com.microsoft.azure.sdk.iot.testcategories.InvalidCertificateTestCategory;
import com.microsoft.azure.sdk.iot.testcategories.IoTHubTestCategory;
import com.microsoft.azure.sdk.iot.testcategories.LongRunningTestCategory;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.microsoft.azure.sdk.iot.common.helpers.CorrelationDetailsLoggingAssert.buildExceptionMessageDpsIndividualOrGroup;
import static com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientTransportProtocol.*;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.*;

public class ProvisioningTests extends ProvisioningCommon
{
    @Test
    public void ProvisioningWithCustomPayloadFlow() throws Exception
    {
    }

    @Test
    @Category(LongRunningTestCategory.class)
    public void groupEnrollmentCanBlockReprovisioning() throws Exception
    {
        throw new Exception("You shouldn't have run this!");
    }

}

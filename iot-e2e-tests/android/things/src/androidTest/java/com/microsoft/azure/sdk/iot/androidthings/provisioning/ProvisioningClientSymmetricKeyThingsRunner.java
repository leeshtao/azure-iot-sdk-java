/*
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */

package com.microsoft.azure.sdk.iot.androidthings.provisioning;

import com.microsoft.azure.sdk.iot.androidthings.BuildConfig;
import com.microsoft.azure.sdk.iot.androidthings.helper.TestGroupA;
import com.microsoft.azure.sdk.iot.common.helpers.Rerun;
import com.microsoft.azure.sdk.iot.common.setup.ProvisioningCommon;
import com.microsoft.azure.sdk.iot.common.tests.provisioning.ProvisioningTests;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientTransportProtocol;

import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

@TestGroupA
public class ProvisioningClientSymmetricKeyThingsRunner
{
    @Rule
    public Rerun count = new Rerun(3);

}
/*
 *
 *  Copyright (c) Microsoft. All rights reserved.
 *  Licensed under the MIT license. See LICENSE file in the project root for full license information.
 *
 */

package tests.integration.com.microsoft.azure.sdk.iot.provisioning;

import com.microsoft.azure.sdk.iot.common.helpers.TestConstants;
import com.microsoft.azure.sdk.iot.common.helpers.Tools;
import com.microsoft.azure.sdk.iot.common.setup.provisioning.ProvisioningCommon;
import com.microsoft.azure.sdk.iot.common.tests.provisioning.ProvisioningTests;
import com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientTransportProtocol;
import com.microsoft.azure.sdk.iot.testcategories.FlakyTestCategory;
import net.jcip.annotations.NotThreadSafe;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Collection;

@NotThreadSafe
@Category(FlakyTestCategory.class) //TPM gets into unusual states sometimes causing later tpm tests to fail
public class ProvisioningClientTPMJVMRunner extends ProvisioningTests
{
}

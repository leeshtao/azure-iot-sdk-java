/*
*  Copyright (c) Microsoft. All rights reserved.
*  Licensed under the MIT license. See LICENSE file in the project root for full license information.
*/

package com.microsoft.azure.sdk.iot.common.setup.provisioning;

import com.microsoft.azure.sdk.iot.common.helpers.*;
import com.microsoft.azure.sdk.iot.deps.twin.DeviceCapabilities;
import com.microsoft.azure.sdk.iot.device.DeviceClient;
import com.microsoft.azure.sdk.iot.device.IotHubClientProtocol;
import com.microsoft.azure.sdk.iot.provisioning.device.*;
import com.microsoft.azure.sdk.iot.provisioning.device.internal.exceptions.ProvisioningDeviceClientException;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProvider;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProviderSymmetricKey;
import com.microsoft.azure.sdk.iot.provisioning.security.SecurityProviderTpm;
import com.microsoft.azure.sdk.iot.provisioning.security.exceptions.SecurityProviderException;
import com.microsoft.azure.sdk.iot.provisioning.security.hsm.SecurityProviderTPMEmulator;
import com.microsoft.azure.sdk.iot.provisioning.security.hsm.SecurityProviderX509Cert;
import com.microsoft.azure.sdk.iot.provisioning.service.ProvisioningServiceClient;
import com.microsoft.azure.sdk.iot.provisioning.service.configs.*;
import com.microsoft.azure.sdk.iot.provisioning.service.exceptions.ProvisioningServiceClientException;
import com.microsoft.azure.sdk.iot.service.RegistryManager;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceTwin;
import com.microsoft.azure.sdk.iot.service.devicetwin.DeviceTwinDevice;
import com.microsoft.azure.sdk.iot.service.devicetwin.Query;
import com.microsoft.azure.sdk.iot.service.exceptions.IotHubException;
import com.microsoft.azure.sdk.iot.testcategories.DeviceProvisioningServiceTestCategory;
import com.microsoft.azure.sdk.iot.testcategories.IoTHubTestCategory;
import junit.framework.AssertionFailedError;
import org.junit.After;
import org.junit.Before;
import org.junit.experimental.categories.Category;
import org.junit.runners.Parameterized;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.microsoft.azure.sdk.iot.common.helpers.CorrelationDetailsLoggingAssert.buildExceptionMessageDpsIndividualOrGroup;
import static com.microsoft.azure.sdk.iot.provisioning.device.ProvisioningDeviceClientStatus.PROVISIONING_DEVICE_STATUS_ASSIGNED;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.*;

@Category(DeviceProvisioningServiceTestCategory.class)
public class ProvisioningCommon extends IntegrationTest
{
    public enum AttestationType
    {
        X509,
        TPM,
        SYMMETRIC_KEY
    }

    public enum EnrollmentType
    {
        INDIVIDUAL,
        GROUP
    }

    public static final String IOT_HUB_CONNECTION_STRING_ENV_VAR_NAME = "IOTHUB_CONNECTION_STRING";
    public static String iotHubConnectionString = "";

    public static final String FAR_AWAY_IOT_HUB_CONNECTION_STRING_ENV_VAR_NAME = "FAR_AWAY_IOTHUB_CONNECTION_STRING";
    public static String farAwayIotHubConnectionString = "";

    public static final String CUSTOM_ALLOCATION_WEBHOOK_URL_VAR_NAME = "CUSTOM_ALLOCATION_POLICY_WEBHOOK";
    public static String customAllocationWebhookUrl = "";

    public static final String DPS_CONNECTION_STRING_ENV_VAR_NAME = "IOT_DPS_CONNECTION_STRING";
    public static String provisioningServiceConnectionString = "";

    public static final String DPS_CONNECTION_STRING_WITH_INVALID_CERT_ENV_VAR_NAME = "PROVISIONING_CONNECTION_STRING_INVALIDCERT";
    public static String provisioningServiceWithInvalidCertConnectionString = "";

    public static String provisioningServiceGlobalEndpoint = "global.azure-devices-provisioning.net";

    public static final String DPS_GLOBAL_ENDPOINT_WITH_INVALID_CERT_ENV_VAR_NAME = "DPS_GLOBALDEVICEENDPOINT_INVALIDCERT";
    public static String provisioningServiceGlobalEndpointWithInvalidCert = "";

    public static final String DPS_ID_SCOPE_ENV_VAR_NAME = "IOT_DPS_ID_SCOPE";
    public static String provisioningServiceIdScope = "";

    public static final long MAX_TIME_TO_WAIT_FOR_REGISTRATION = 60 * 1000;

    public static final String HMAC_SHA256 = "HmacSHA256";

    public static final int MAX_TPM_CONNECT_RETRY_ATTEMPTS = 10;

    protected static final String CUSTOM_ALLOCATION_WEBHOOK_API_VERSION = "2019-03-31";

    public ProvisioningServiceClient provisioningServiceClient = null;
    public RegistryManager registryManager = null;

    //sending reported properties for twin operations takes some time to get the appropriate callback
    public static final int MAX_TWIN_PROPAGATION_WAIT_SECONDS = 60;

    //@Parameterized.Parameters(name = "{0} using {1}")
    /*public static Collection inputs(AttestationType attestationType) throws Exception
    {
        if (attestationType == AttestationType.SYMMETRIC_KEY || attestationType == AttestationType.X509)
        {
            return Arrays.asList(
                    new Object[][]
                            {
                                    {ProvisioningDeviceClientTransportProtocol.HTTPS, attestationType},
                            });
        }
        else if (attestationType == AttestationType.TPM)
        {
            return Arrays.asList(
                    new Object[][]
                            {
                                    {ProvisioningDeviceClientTransportProtocol.HTTPS, attestationType},

                                    //MQTT/MQTT_WS does not support tpm attestation
                                    //{ProvisioningDeviceClientTransportProtocol.MQTT, attestationType},
                                    //{ProvisioningDeviceClientTransportProtocol.MQTT_WS, attestationType},
                            });
        }
        else
        {
            throw new IllegalArgumentException("Unknown attestation type provided");
        }

    }*/

}

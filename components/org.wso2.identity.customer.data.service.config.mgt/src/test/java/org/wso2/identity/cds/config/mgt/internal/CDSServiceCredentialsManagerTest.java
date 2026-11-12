/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.identity.cds.config.mgt.internal;

import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ApplicationBasicInfo;
import org.wso2.carbon.identity.application.common.model.AuthorizedAPI;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.ServiceProviderProperty;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.application.mgt.AuthorizedAPIManagementService;
import org.wso2.carbon.identity.application.mgt.inbound.dto.ApplicationDTO;
import org.wso2.carbon.identity.application.mgt.inbound.dto.InboundProtocolConfigurationDTO;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.oauth.OAuthAdminServiceImpl;
import org.wso2.carbon.identity.oauth.dto.OAuthConsumerAppDTO;
import org.wso2.carbon.identity.organization.management.application.OrgApplicationManager;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.client.exception.CDSClientException;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtServerException;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Unit tests for {@link CDSServiceCredentialsManager}.
 */
public class CDSServiceCredentialsManagerTest {

    private static final String TENANT = "acme";
    private static final String USER = "admin";
    private static final String RESOURCE_ID = "app-resource-id";
    private static final String CLIENT_ID = "acme-client";
    private static final String RENEWED_SECRET = "renewed-secret";
    private static final String ORGANIZATION_ID = "acme-organization-id";

    private MockedStatic<PrivilegedCarbonContext> carbonContextStatic;
    private MockedStatic<IdentityTenantUtil> identityTenantUtil;
    private MockedStatic<CDSClient> cdsClient;
    private ApplicationManagementService applicationManagementService;
    private AuthorizedAPIManagementService authorizedAPIManagementService;
    private OAuthAdminServiceImpl oAuthAdminService;
    private OrgApplicationManager orgApplicationManager;

    @BeforeClass
    public void setUpCarbonHome() throws Exception {

        // PrivilegedCarbonContext reads the Carbon home when it is loaded.
        System.setProperty("carbon.home", Files.createTempDirectory("carbon-home").toString());
    }

    @BeforeMethod
    public void setUp() throws Exception {

        applicationManagementService = mock(ApplicationManagementService.class);
        authorizedAPIManagementService = mock(AuthorizedAPIManagementService.class);
        oAuthAdminService = mock(OAuthAdminServiceImpl.class);
        orgApplicationManager = mock(OrgApplicationManager.class);
        OrganizationManager organizationManager = mock(OrganizationManager.class);
        when(organizationManager.resolveOrganizationId(TENANT)).thenReturn(ORGANIZATION_ID);

        PrivilegedCarbonContext carbonContext = mock(PrivilegedCarbonContext.class);
        when(carbonContext.getUsername()).thenReturn(USER);
        when(carbonContext.getOSGiService(ApplicationManagementService.class, null))
                .thenReturn(applicationManagementService);
        when(carbonContext.getOSGiService(AuthorizedAPIManagementService.class, null))
                .thenReturn(authorizedAPIManagementService);
        when(carbonContext.getOSGiService(OAuthAdminServiceImpl.class, null)).thenReturn(oAuthAdminService);
        when(carbonContext.getOSGiService(OrgApplicationManager.class, null)).thenReturn(orgApplicationManager);
        when(carbonContext.getOSGiService(OrganizationManager.class, null)).thenReturn(organizationManager);
        carbonContextStatic = mockStatic(PrivilegedCarbonContext.class);
        carbonContextStatic.when(PrivilegedCarbonContext::getThreadLocalCarbonContext).thenReturn(carbonContext);

        identityTenantUtil = mockStatic(IdentityTenantUtil.class);
        identityTenantUtil.when(() -> IdentityTenantUtil.getTenantId(TENANT)).thenReturn(1);

        // CDS takes the credentials unless a test stubs otherwise.
        cdsClient = mockStatic(CDSClient.class);
        cdsClient.when(() -> CDSClient.syncServiceCredentials(anyString(), anyString(), anyString(),
                anyString())).thenReturn(true);

        OAuthConsumerAppDTO renewed = new OAuthConsumerAppDTO();
        renewed.setOauthConsumerSecret(RENEWED_SECRET);
        when(oAuthAdminService.updateAndRetrieveOauthSecretKey(CLIENT_ID)).thenReturn(renewed);
    }

    @AfterMethod
    public void tearDown() {

        cdsClient.close();
        identityTenantUtil.close();
        carbonContextStatic.close();
    }

    private static ServiceProvider applicationWithClient() {

        InboundAuthenticationRequestConfig oAuthConfig = new InboundAuthenticationRequestConfig();
        oAuthConfig.setInboundAuthType("oauth2");
        oAuthConfig.setInboundAuthKey(CLIENT_ID);
        InboundAuthenticationConfig inboundConfig = new InboundAuthenticationConfig();
        inboundConfig.setInboundAuthenticationRequestConfigs(new InboundAuthenticationRequestConfig[]{oAuthConfig});
        ServiceProvider application = new ServiceProvider();
        application.setInboundAuthenticationConfig(inboundConfig);
        return application;
    }

    private void applicationExists() throws Exception {

        ApplicationBasicInfo basicInfo = new ApplicationBasicInfo();
        basicInfo.setApplicationResourceId(RESOURCE_ID);
        when(applicationManagementService.getApplicationBasicInfoByName(
                CDSServiceCredentialsManager.APPLICATION_NAME, TENANT)).thenReturn(basicInfo);
        when(applicationManagementService.getApplicationByResourceId(RESOURCE_ID, TENANT))
                .thenReturn(applicationWithClient());
    }

    private void applicationIsCreated() throws Exception {

        when(applicationManagementService.createApplication(any(ApplicationDTO.class), eq(TENANT), eq(USER)))
                .thenReturn(RESOURCE_ID);
        when(applicationManagementService.getApplicationByResourceId(RESOURCE_ID, TENANT))
                .thenReturn(applicationWithClient());
    }

    private void verifyNeverDeleted() throws Exception {

        verify(applicationManagementService, never()).deleteApplicationByResourceId(anyString(), anyString(),
                anyString());
        verify(oAuthAdminService, never()).removeOAuthApplicationData(anyString());
    }

    @Test
    public void testCreatesTheApplicationAndSendsItsCredentials() throws Exception {

        applicationIsCreated();

        new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);

        ArgumentCaptor<ApplicationDTO> created = ArgumentCaptor.forClass(ApplicationDTO.class);
        verify(applicationManagementService).createApplication(created.capture(), eq(TENANT), eq(USER));
        ServiceProvider application = created.getValue().getServiceProvider();
        assertEquals(application.getApplicationName(), "Customer Data Service");
        assertEquals(application.getTemplateId(), "m2m-application");
        List<InboundProtocolConfigurationDTO> inbounds = new ArrayList<>(
                created.getValue().getInboundProtocolConfigurationDto().getInboundProtocolConfigurationMap()
                        .values());
        assertEquals(inbounds.size(), 1);
        assertEquals(((OAuthConsumerAppDTO) inbounds.get(0)).getGrantTypes(), "client_credentials");
        verify(applicationManagementService, never()).updateApplication(any(ServiceProvider.class), anyString(),
                anyString());

        verify(authorizedAPIManagementService, never()).addAuthorizedAPI(anyString(), any(AuthorizedAPI.class),
                anyString());

        cdsClient.verify(() -> CDSClient.syncServiceCredentials(TENANT, ORGANIZATION_ID, CLIENT_ID, RENEWED_SECRET));
    }

    // CDS may have lost the credentials it held, so each enable renews the secret and sends it.
    @Test
    public void testRenewsAndSendsTheSecretOfAnExistingApplication() throws Exception {

        applicationExists();

        new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);

        verify(applicationManagementService, never()).createApplication(any(ApplicationDTO.class), anyString(),
                anyString());
        verify(oAuthAdminService).updateAndRetrieveOauthSecretKey(CLIENT_ID);
        cdsClient.verify(() -> CDSClient.syncServiceCredentials(TENANT, ORGANIZATION_ID, CLIENT_ID, RENEWED_SECRET));
    }

    @Test
    public void testFailsWhenTheApplicationCannotBeCreated() throws Exception {

        when(applicationManagementService.createApplication(any(ApplicationDTO.class), eq(TENANT), eq(USER)))
                .thenThrow(new IdentityApplicationManagementException("creation failed"));

        try {
            new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);
            fail("Expected a CDSConfigMgtServerException.");
        } catch (CDSConfigMgtServerException e) {
            assertNotNull(e.getErrorCode());
        }
        cdsClient.verify(() -> CDSClient.syncServiceCredentials(anyString(), anyString(), anyString(),
                anyString()), never());
    }

    // A CDS without an encryption key uses its configured client. The application stays for a later enable.
    @Test
    public void testKeepsTheApplicationWhenCDSStoresNoServiceCredentials() throws Exception {

        applicationIsCreated();
        cdsClient.when(() -> CDSClient.syncServiceCredentials(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);

        verifyNeverDeleted();
    }

    @Test
    public void testFailsWithoutTheSecretWhenCDSRejectsTheCredentials() throws Exception {

        applicationIsCreated();
        cdsClient.when(() -> CDSClient.syncServiceCredentials(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CDSClientException("rejected", "The token endpoint refused the client.",
                        "CDS-16003"));

        try {
            new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);
            fail("Expected a CDSConfigMgtServerException.");
        } catch (CDSConfigMgtServerException e) {
            assertTrue(e.getDescription().contains("refused"));
            assertTrue(!e.getDescription().contains(RENEWED_SECRET));
            assertTrue(!e.getMessage().contains(RENEWED_SECRET));
        }
        verifyNeverDeleted();
    }

    @Test
    public void testSharesTheApplicationWithAllSubOrganizations() throws Exception {

        applicationIsCreated();
        // Sharing updates the application. The listener allows that update of the reserved application.
        boolean[] allowedWhileSharing = new boolean[1];
        org.mockito.Mockito.doAnswer(invocation -> {
            ServiceProvider application = new ServiceProvider();
            application.setApplicationName(CDSServiceCredentialsManager.APPLICATION_NAME);
            new CDSApplicationShareListener().doPreUpdateApplication(application, TENANT, USER);
            allowedWhileSharing[0] = IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal();
            IdentityApplicationManagementUtil.removeAllowUpdateSystemApplicationThreadLocal();
            return null;
        }).when(orgApplicationManager).shareOrganizationApplication(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());

        new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);

        verify(orgApplicationManager).shareOrganizationApplication(ORGANIZATION_ID, RESOURCE_ID, true,
                Collections.emptyList());
        assertTrue(allowedWhileSharing[0], "The reserved application cannot be updated while it is shared.");
        assertTrue(!IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal(),
                "Updating reserved applications is still allowed after sharing.");
    }

    @DataProvider
    public Object[][] sharedProperties() {

        return new Object[][]{{"isAppShared"}, {"shareWithAllChildren"}};
    }

    @Test(dataProvider = "sharedProperties")
    public void testDoesNotShareAnApplicationSharedWithAllSubOrganizations(String sharedProperty) throws Exception {

        applicationExists();
        ServiceProviderProperty shared = new ServiceProviderProperty();
        shared.setName(sharedProperty);
        shared.setValue("true");
        ServiceProvider application = applicationWithClient();
        application.setSpProperties(new ServiceProviderProperty[]{shared});
        when(applicationManagementService.getApplicationByResourceId(RESOURCE_ID, TENANT)).thenReturn(application);

        new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);

        verify(orgApplicationManager, never()).shareOrganizationApplication(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
        cdsClient.verify(() -> CDSClient.syncServiceCredentials(TENANT, ORGANIZATION_ID, CLIENT_ID, RENEWED_SECRET));
    }

    @Test
    public void testStopsAllowingUpdatesWhenSharingFails() throws Exception {

        applicationIsCreated();
        org.mockito.Mockito.doThrow(new OrganizationManagementException("sharing failed"))
                .when(orgApplicationManager).shareOrganizationApplication(anyString(), anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean(), any());

        try {
            new CDSServiceCredentialsManager().ensureServiceCredentials(TENANT);
            fail("Expected a CDSConfigMgtServerException.");
        } catch (CDSConfigMgtServerException e) {
            assertNotNull(e.getErrorCode());
        }
        assertTrue(!IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal(),
                "Updating reserved applications is still allowed after a failed share.");
        cdsClient.verify(() -> CDSClient.syncServiceCredentials(anyString(), anyString(), anyString(),
                anyString()), never());
    }

    @Test
    public void testListenerAllowsOnlyTheApplicationOfCDSWhileSharing() throws Exception {

        CDSApplicationShareListener listener = new CDSApplicationShareListener();
        ServiceProvider ours = new ServiceProvider();
        ours.setApplicationName(CDSServiceCredentialsManager.APPLICATION_NAME);
        ServiceProvider other = new ServiceProvider();
        other.setApplicationName("Console");

        listener.doPreUpdateApplication(ours, TENANT, USER);
        assertTrue(!IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal(),
                "An update of the application of CDS is allowed outside sharing.");

        CDSApplicationShareListener.startSharing();
        try {
            listener.doPreUpdateApplication(other, TENANT, USER);
            assertTrue(!IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal(),
                    "An update of another reserved application is allowed while sharing.");
            listener.doPreUpdateApplication(ours, TENANT, USER);
            assertTrue(IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal(),
                    "An update of the application of CDS is blocked while sharing.");
        } finally {
            CDSApplicationShareListener.endSharing();
        }
        assertTrue(!IdentityApplicationManagementUtil.getAllowUpdateSystemApplicationThreadLocal(),
                "Updates are still allowed after sharing.");
        assertTrue(listener.getDefaultOrderId() < 30, "The listener does not run before the one that blocks updates.");
    }
}

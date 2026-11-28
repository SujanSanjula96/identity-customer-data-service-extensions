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

package org.wso2.identity.cds.event.handler;

import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.context.IdentityContext;
import org.wso2.carbon.identity.core.context.model.AnonymousUser;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.event.IdentityEventConstants;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;
import org.wso2.carbon.user.core.common.AbstractUserStoreManager;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.event.handler.internal.EventHandlerDataHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class IdentityEventHandlerTest {

    private static final String TENANT_DOMAIN = "carbon.super";
    private static final String USER_ID = "8a6e3c1f-6c1b-4d5e-9a3e-0e5c2b7d9f10";
    private static final String CONTEXT_TRACKER = "c0ntext-tracker";
    private static final String CLAIM_TRACKER = "cl4im-tracker";
    private static final String TRACKER_CLAIM = "http://wso2.org/claims/cdsProfile";
    private static final String SUB_ORG_TENANT_DOMAIN = "sub-org";
    private static final String ROOT_ORG_ID = "10084a8d-113f-4211-a0d5-efe36b082211";

    private IdentityEventHandler handler;
    private CDSConfigService cdsConfigService;
    private MockedStatic<CDSClient> cdsClient;
    private MockedStatic<OrganizationManagementUtil> organizationManagementUtil;
    private MockedStatic<IdentityContext> identityContextStatic;
    private IdentityContext identityContext;

    @BeforeClass
    public void setUpCarbonHome() throws Exception {

        // CarbonContext, the parent of IdentityContext, reads the carbon home when the class loads.
        Path carbonHome = Paths.get("target", "carbon-home").toAbsolutePath();
        Files.createDirectories(carbonHome.resolve(Paths.get("repository", "conf")));
        System.setProperty("carbon.home", carbonHome.toString());
    }

    @BeforeMethod
    public void setUp() throws Exception {

        handler = new IdentityEventHandler();
        cdsConfigService = mock(CDSConfigService.class);
        when(cdsConfigService.isCDSEnabled(TENANT_DOMAIN)).thenReturn(true);
        EventHandlerDataHolder.setCDSConfigService(cdsConfigService);
        cdsClient = mockStatic(CDSClient.class);
        identityContext = mock(IdentityContext.class);
        identityContextStatic = mockStatic(IdentityContext.class);
        identityContextStatic.when(IdentityContext::getThreadLocalIdentityContext).thenReturn(identityContext);
        when(cdsConfigService.isCDSEnabled(SUB_ORG_TENANT_DOMAIN)).thenReturn(true);
        organizationManagementUtil = mockStatic(OrganizationManagementUtil.class);
        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(SUB_ORG_TENANT_DOMAIN))
                .thenReturn(true);
        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(TENANT_DOMAIN))
                .thenReturn(false);
    }

    @AfterMethod
    public void tearDown() {

        IdentityUtil.threadLocalProperties.get().remove(Constants.PROCESS_ADD_SHARED_USER);
        organizationManagementUtil.close();
        identityContextStatic.close();
        cdsClient.close();
        EventHandlerDataHolder.setCDSConfigService(null);
    }

    @Test
    public void testTrackerFromTheIdentityContext() throws Exception {

        setAnonymousUser(CONTEXT_TRACKER);

        handler.handleEvent(postAddUserEvent(CLAIM_TRACKER));

        assertEquals(capturedIdentityDataSyncPayload().get(Constants.PROFILE_COOKIE), CONTEXT_TRACKER);
    }

    @Test
    public void testTrackerClaimIsIgnoredWhenTheAnonymousUserHasNoTracker() throws Exception {

        setAnonymousUser(null);

        handler.handleEvent(postAddUserEvent(CLAIM_TRACKER));

        assertProfileSyncWithoutTracker();
    }

    @Test
    public void testTrackerClaimIsIgnoredWhenThereIsNoAnonymousUser() throws Exception {

        handler.handleEvent(postAddUserEvent(CLAIM_TRACKER));

        assertProfileSyncWithoutTracker();
    }

    @Test
    public void testProfileSyncWhenThereIsNoTracker() throws Exception {

        setAnonymousUser(null);

        handler.handleEvent(postAddUserEvent(null));

        assertProfileSyncWithoutTracker();
    }

    @Test
    public void testAddOfSharedUserIsNotSynced() throws Exception {

        IdentityUtil.threadLocalProperties.get().put(Constants.PROCESS_ADD_SHARED_USER, true);

        handler.handleEvent(postAddUserEvent(null));

        assertNoSync();
    }

    @Test
    public void testClaimUpdateOfSharedUserIsNotSynced() throws Exception {

        AbstractUserStoreManager userStoreManager = userStoreManagerWithManagedOrganization(ROOT_ORG_ID);

        handler.handleEvent(claimUpdateEvent(SUB_ORG_TENANT_DOMAIN, userStoreManager));

        assertNoSync();
    }

    @Test
    public void testClaimUpdateOfOrganizationUserIsSynced() throws Exception {

        AbstractUserStoreManager userStoreManager = userStoreManagerWithManagedOrganization(null);

        handler.handleEvent(claimUpdateEvent(SUB_ORG_TENANT_DOMAIN, userStoreManager));

        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(eq("POST_SET_USER_CLAIM_VALUES_WITH_ID"), anyMap(),
                eq(SUB_ORG_TENANT_DOMAIN)));
    }

    @Test
    public void testClaimUpdateInRootDoesNotReadTheManagedOrganization() throws Exception {

        AbstractUserStoreManager userStoreManager = userStoreManagerWithManagedOrganization(ROOT_ORG_ID);

        handler.handleEvent(claimUpdateEvent(TENANT_DOMAIN, userStoreManager));

        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(eq("POST_SET_USER_CLAIM_VALUES_WITH_ID"), anyMap(),
                eq(TENANT_DOMAIN)));
        verify(userStoreManager, never()).getUserClaimValuesWithID(anyString(), any(String[].class), any());
    }

    private AbstractUserStoreManager userStoreManagerWithManagedOrganization(String managedOrganization)
            throws Exception {

        AbstractUserStoreManager userStoreManager = mock(AbstractUserStoreManager.class);
        Map<String, String> claims = new HashMap<>();
        if (managedOrganization != null) {
            claims.put(Constants.MANAGED_ORGANIZATION_CLAIM, managedOrganization);
        }
        when(userStoreManager.getUserClaimValuesWithID(eq(USER_ID),
                eq(new String[]{Constants.MANAGED_ORGANIZATION_CLAIM}), any())).thenReturn(claims);
        return userStoreManager;
    }

    private Event claimUpdateEvent(String tenantDomain, AbstractUserStoreManager userStoreManager) {

        Map<String, Object> claims = new HashMap<>();
        claims.put("http://wso2.org/claims/lastname", "Doe");
        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.TENANT_DOMAIN, tenantDomain);
        properties.put(Constants.USER_ID_PROPERTY, USER_ID);
        properties.put(Constants.USER_CLAIMS_PROPERTY, claims);
        properties.put(IdentityEventConstants.EventProperty.USER_STORE_MANAGER, userStoreManager);
        return new Event("POST_SET_USER_CLAIM_VALUES_WITH_ID", properties);
    }

    private void assertNoSync() {

        cdsClient.verify(() -> CDSClient.triggerProfileSync(anyString(), anyMap(), anyString()), never());
        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(anyString(), anyMap(), anyString()), never());
    }

    private void setAnonymousUser(String anonymousProfileTracker) {

        when(identityContext.getAnonymousUser()).thenReturn(new AnonymousUser.Builder()
                .anonymousProfileTracker(anonymousProfileTracker)
                .build());
    }

    private Event postAddUserEvent(String trackerClaimValue) {

        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.USER_ID_CLAIM, USER_ID);
        claims.put("http://wso2.org/claims/emailaddress", "alex@example.com");
        if (trackerClaimValue != null) {
            claims.put(TRACKER_CLAIM, trackerClaimValue);
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.TENANT_DOMAIN, TENANT_DOMAIN);
        properties.put(Constants.USER_CLAIMS_PROPERTY, claims);
        return new Event("POST_ADD_USER", properties);
    }

    private void assertProfileSyncWithoutTracker() {

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        cdsClient.verify(() -> CDSClient.triggerProfileSync(eq("POST_ADD_USER"), payload.capture(),
                eq(TENANT_DOMAIN)));
        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(anyString(), anyMap(), anyString()), never());
        assertNull(payload.getValue().get(Constants.PROFILE_COOKIE));
    }

    private Map<String, Object> capturedIdentityDataSyncPayload() {

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(eq("POST_ADD_USER"), payload.capture(),
                eq(TENANT_DOMAIN)));
        return payload.getValue();
    }
}

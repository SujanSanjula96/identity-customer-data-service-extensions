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

import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.api.resource.mgt.APIResourceManager;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementClientException;
import org.wso2.carbon.identity.application.common.model.APIResource;
import org.wso2.carbon.identity.application.common.model.ApplicationBasicInfo;
import org.wso2.carbon.identity.application.common.model.AuthorizedAPI;
import org.wso2.carbon.identity.application.common.model.AuthorizedScopes;
import org.wso2.carbon.identity.application.common.model.Scope;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Unit tests for {@link CDSAuthorizedAPIListener}.
 */
public class CDSAuthorizedAPIListenerTest {

    private static final String TENANT = "acme";
    private static final String CDS_APP = "cds-app-id";
    private static final String OTHER_APP = "other-app-id";

    private MockedStatic<PrivilegedCarbonContext> carbonContextStatic;
    private ApplicationManagementService applicationManagementService;
    private APIResourceManager apiResourceManager;
    private CDSAuthorizedAPIListener listener;

    @BeforeClass
    public void setUpCarbonHome() throws Exception {

        // PrivilegedCarbonContext reads the Carbon home when it is loaded.
        System.setProperty("carbon.home", Files.createTempDirectory("carbon-home").toString());
    }

    @BeforeMethod
    public void setUp() throws Exception {

        applicationManagementService = mock(ApplicationManagementService.class);
        // The deployment reserves the name. The Identity Server compares these names ignoring case.
        when(applicationManagementService.getSystemApplications())
                .thenReturn(new HashSet<>(Arrays.asList("Console", "customer data service")));
        when(applicationManagementService.getApplicationBasicInfoByResourceId(CDS_APP, TENANT))
                .thenReturn(application(CDSServiceCredentialsManager.APPLICATION_NAME));
        when(applicationManagementService.getApplicationBasicInfoByResourceId(OTHER_APP, TENANT))
                .thenReturn(application("Other Application"));
        apiResourceManager = mock(APIResourceManager.class);

        PrivilegedCarbonContext carbonContext = mock(PrivilegedCarbonContext.class);
        when(carbonContext.getOSGiService(ApplicationManagementService.class, null))
                .thenReturn(applicationManagementService);
        when(carbonContext.getOSGiService(APIResourceManager.class, null)).thenReturn(apiResourceManager);
        carbonContextStatic = mockStatic(PrivilegedCarbonContext.class);
        carbonContextStatic.when(PrivilegedCarbonContext::getThreadLocalCarbonContext).thenReturn(carbonContext);

        // Every management API exists, with the scopes CDS asks for and one it does not.
        for (Map.Entry<String, List<String>> api : CDSAuthorizedAPIListener.MANAGEMENT_API_SCOPES.entrySet()) {
            List<Scope> scopes = new ArrayList<>();
            for (String scope : api.getValue()) {
                scopes.add(new Scope.ScopeBuilder().name(scope).build());
            }
            scopes.add(new Scope.ScopeBuilder().name("internal_unrelated_scope").build());
            APIResource resource = new APIResource.APIResourceBuilder().id("id:" + api.getKey())
                    .identifier(api.getKey()).name(api.getKey()).scopes(scopes).build();
            when(apiResourceManager.getAPIResourceByIdentifier(api.getKey(), TENANT)).thenReturn(resource);
            when(apiResourceManager.getAPIResourceById("id:" + api.getKey(), TENANT)).thenReturn(resource);
        }
        listener = new CDSAuthorizedAPIListener();
    }

    @AfterMethod
    public void tearDown() {

        carbonContextStatic.close();
    }

    private static ApplicationBasicInfo application(String name) {

        ApplicationBasicInfo application = new ApplicationBasicInfo();
        application.setApplicationName(name);
        return application;
    }

    private static Set<String> requestedScopes() {

        Set<String> scopes = new HashSet<>();
        for (List<String> apiScopes : CDSAuthorizedAPIListener.MANAGEMENT_API_SCOPES.values()) {
            scopes.addAll(apiScopes);
        }
        return scopes;
    }

    @Test
    public void testIsEnabled() {

        assertTrue(listener.isEnable());
    }

    @Test
    public void testAuthorizesTheApplicationOfCDSForTheScopesCDSAsksFor() throws Exception {

        List<AuthorizedScopes> authorizedScopes = new ArrayList<>();
        listener.postGetAuthorizedScopes(authorizedScopes, CDS_APP, TENANT);

        Set<String> granted = new HashSet<>();
        for (AuthorizedScopes scopes : authorizedScopes) {
            assertEquals(scopes.getPolicyId(), "RBAC");
            granted.addAll(scopes.getScopes());
        }
        assertEquals(granted, requestedScopes());
    }

    @Test
    public void testAddsOnlyTheScopesTheApplicationLacks() throws Exception {

        List<AuthorizedScopes> authorizedScopes = new ArrayList<>();
        authorizedScopes.add(new AuthorizedScopes("RBAC",
                new ArrayList<>(Collections.singletonList("internal_claim_meta_view"))));
        listener.postGetAuthorizedScopes(authorizedScopes, CDS_APP, TENANT);

        List<String> all = new ArrayList<>();
        for (AuthorizedScopes scopes : authorizedScopes) {
            all.addAll(scopes.getScopes());
        }
        assertEquals(all.size(), requestedScopes().size(), "A scope is granted twice.");
    }

    @Test
    public void testLeavesOtherApplicationsAlone() throws Exception {

        List<AuthorizedScopes> authorizedScopes = new ArrayList<>();
        listener.postGetAuthorizedScopes(authorizedScopes, OTHER_APP, TENANT);
        List<AuthorizedAPI> authorizedAPIs = new ArrayList<>();
        listener.postGetAuthorizedAPIs(authorizedAPIs, OTHER_APP, TENANT);

        assertTrue(authorizedScopes.isEmpty());
        assertTrue(authorizedAPIs.isEmpty());
        listener.preAddAuthorizedAPI(OTHER_APP, new AuthorizedAPI(), TENANT);
        listener.preDeleteAuthorizedAPI(OTHER_APP, "an-api", TENANT);
    }

    // Without the reserved name, a tenant could create an application with the name of the application of CDS.
    @Test
    public void testLeavesAnApplicationWithTheNameAloneWhenTheNameIsNotReserved() throws Exception {

        when(applicationManagementService.getSystemApplications()).thenReturn(Collections.singleton("Console"));

        List<AuthorizedScopes> authorizedScopes = new ArrayList<>();
        listener.postGetAuthorizedScopes(authorizedScopes, CDS_APP, TENANT);
        List<AuthorizedAPI> authorizedAPIs = new ArrayList<>();
        listener.postGetAuthorizedAPIs(authorizedAPIs, CDS_APP, TENANT);

        assertTrue(authorizedScopes.isEmpty());
        assertTrue(authorizedAPIs.isEmpty());
        assertNull(listener.postGetAuthorizedAPI(null, CDS_APP, "id:/api/server/v1/claim-dialects", TENANT));
        listener.preAddAuthorizedAPI(CDS_APP, new AuthorizedAPI(), TENANT);
    }

    @Test
    public void testListsEachManagementAPIOnceWithOnlyTheRequestedScopes() throws Exception {

        List<AuthorizedAPI> authorizedAPIs = new ArrayList<>();
        AuthorizedAPI stored = new AuthorizedAPI();
        stored.setAPIId("id:/scim2/Users");
        authorizedAPIs.add(stored);

        listener.postGetAuthorizedAPIs(authorizedAPIs, CDS_APP, TENANT);

        assertEquals(authorizedAPIs.size(), CDSAuthorizedAPIListener.MANAGEMENT_API_SCOPES.size());
        for (AuthorizedAPI api : authorizedAPIs) {
            if (api == stored) {
                continue;
            }
            assertEquals(api.getPolicyId(), "RBAC");
            for (Scope scope : api.getScopes()) {
                assertTrue(CDSAuthorizedAPIListener.MANAGEMENT_API_SCOPES.get(api.getAPIIdentifier())
                        .contains(scope.getName()), "An unrequested scope is granted: " + scope.getName());
            }
        }
    }

    @Test
    public void testReturnsAManagementAPIOfTheApplicationOfCDS() throws Exception {

        AuthorizedAPI api = listener.postGetAuthorizedAPI(null, CDS_APP, "id:/api/server/v1/claim-dialects", TENANT);

        assertNotNull(api);
        assertEquals(api.getAPIIdentifier(), "/api/server/v1/claim-dialects");
        assertNull(listener.postGetAuthorizedAPI(null, OTHER_APP, "id:/api/server/v1/claim-dialects", TENANT));
    }

    @Test
    public void testRefusesChangesToTheAuthorizationOfTheApplicationOfCDS() throws Exception {

        try {
            listener.preAddAuthorizedAPI(CDS_APP, new AuthorizedAPI(), TENANT);
            fail("Expected adding an API to be refused.");
        } catch (IdentityApplicationManagementClientException e) {
            assertTrue(e.getMessage().contains(CDSServiceCredentialsManager.APPLICATION_NAME));
        }
        try {
            listener.preDeleteAuthorizedAPI(CDS_APP, "id:/scim2/Users", TENANT);
            fail("Expected removing an API to be refused.");
        } catch (IdentityApplicationManagementClientException e) {
            assertNotNull(e.getMessage());
        }
        try {
            listener.prePatchAuthorizedAPI(CDS_APP, "id:/scim2/Users", Collections.singletonList("a"),
                    Collections.emptyList(), TENANT);
            fail("Expected changing the scopes of an API to be refused.");
        } catch (IdentityApplicationManagementClientException e) {
            assertNotNull(e.getMessage());
        }
    }
}

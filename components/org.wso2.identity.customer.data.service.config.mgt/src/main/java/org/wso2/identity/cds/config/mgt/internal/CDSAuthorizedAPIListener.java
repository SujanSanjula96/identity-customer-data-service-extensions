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

import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.api.resource.mgt.APIResourceManager;
import org.wso2.carbon.identity.api.resource.mgt.APIResourceMgtException;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementClientException;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.APIResource;
import org.wso2.carbon.identity.application.common.model.ApplicationBasicInfo;
import org.wso2.carbon.identity.application.common.model.AuthorizedAPI;
import org.wso2.carbon.identity.application.common.model.AuthorizedScopes;
import org.wso2.carbon.identity.application.common.model.Scope;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.application.mgt.listener.AbstractAuthorizedAPIManagementListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authorizes the application of CDS for the management APIs CDS calls, without storing the authorization, as the
 * Identity Server does for the Console. The application gets these APIs and scopes from this listener, and its
 * authorization cannot be changed.
 */
public class CDSAuthorizedAPIListener extends AbstractAuthorizedAPIManagementListener {

    private static final int ORDER_ID = 60;
    private static final String RBAC = "RBAC";

    /**
     * The management APIs CDS asks a token for, with the scopes it asks for. They have to match the scope of
     * FetchToken in CDS. The /o APIs are those of a sub organization, which a token of the shared application
     * reaches.
     */
    static final Map<String, List<String>> MANAGEMENT_API_SCOPES;

    static {
        Map<String, List<String>> scopes = new LinkedHashMap<>();
        scopes.put("/api/server/v1/applications", Collections.singletonList("internal_application_mgt_view"));
        scopes.put("/api/server/v1/claim-dialects", Collections.singletonList("internal_claim_meta_view"));
        scopes.put("/scim2/Users", Arrays.asList("internal_user_mgt_list", "internal_user_mgt_view"));
        scopes.put("/api/server/v1/organizations", Collections.singletonList("internal_organization_view"));
        scopes.put("/o/api/server/v1/applications", Collections.singletonList("internal_org_application_mgt_view"));
        scopes.put("/o/api/server/v1/claim-dialects", Collections.singletonList("internal_org_claim_meta_view"));
        scopes.put("/o/scim2/Users", Arrays.asList("internal_org_user_mgt_list", "internal_org_user_mgt_view"));
        scopes.put("/o/api/server/v1/organizations", Collections.singletonList("internal_org_organization_view"));
        MANAGEMENT_API_SCOPES = Collections.unmodifiableMap(scopes);
    }

    @Override
    public int getDefaultOrderId() {

        return ORDER_ID;
    }

    @Override
    public boolean isEnable() {

        return true;
    }

    @Override
    public void preAddAuthorizedAPI(String appId, AuthorizedAPI authorizedAPI, String tenantDomain)
            throws IdentityApplicationManagementException {

        refuseChange(appId, tenantDomain);
    }

    @Override
    public void preDeleteAuthorizedAPI(String appId, String apiId, String tenantDomain)
            throws IdentityApplicationManagementException {

        refuseChange(appId, tenantDomain);
    }

    @Override
    public void prePatchAuthorizedAPI(String appId, String apiId, List<String> addedScopes,
                                      List<String> removedScopes, String tenantDomain)
            throws IdentityApplicationManagementException {

        refuseChange(appId, tenantDomain);
    }

    @Override
    public void postGetAuthorizedAPIs(List<AuthorizedAPI> authorizedAPIs, String appId, String tenantDomain)
            throws IdentityApplicationManagementException {

        if (!isApplicationOfCDS(appId, tenantDomain)) {
            return;
        }
        Set<String> present = new HashSet<>();
        for (AuthorizedAPI authorizedAPI : authorizedAPIs) {
            present.add(authorizedAPI.getAPIId());
        }
        for (String identifier : MANAGEMENT_API_SCOPES.keySet()) {
            APIResource apiResource = getAPIResource(identifier, tenantDomain);
            if (apiResource != null && present.add(apiResource.getId())) {
                authorizedAPIs.add(toAuthorizedAPI(appId, apiResource));
            }
        }
    }

    @Override
    public AuthorizedAPI postGetAuthorizedAPI(AuthorizedAPI authorizedAPI, String appId, String apiId,
                                              String tenantDomain) throws IdentityApplicationManagementException {

        if (authorizedAPI != null || !isApplicationOfCDS(appId, tenantDomain)) {
            return authorizedAPI;
        }
        APIResource apiResource;
        try {
            apiResource = getAPIResourceManager().getAPIResourceById(apiId, tenantDomain);
        } catch (APIResourceMgtException e) {
            throw new IdentityApplicationManagementException("Error while reading the API resource " + apiId, e);
        }
        if (apiResource == null || !MANAGEMENT_API_SCOPES.containsKey(apiResource.getIdentifier())) {
            return null;
        }
        return toAuthorizedAPI(appId, apiResource);
    }

    @Override
    public void postGetAuthorizedScopes(List<AuthorizedScopes> authorizedScopes, String appId, String tenantDomain)
            throws IdentityApplicationManagementException {

        if (!isApplicationOfCDS(appId, tenantDomain)) {
            return;
        }
        Set<String> scopes = new HashSet<>();
        for (List<String> apiScopes : MANAGEMENT_API_SCOPES.values()) {
            scopes.addAll(apiScopes);
        }
        for (AuthorizedScopes authorized : authorizedScopes) {
            if (RBAC.equals(authorized.getPolicyId()) && authorized.getScopes() != null) {
                scopes.removeAll(authorized.getScopes());
            }
        }
        if (!scopes.isEmpty()) {
            authorizedScopes.add(new AuthorizedScopes(RBAC, new ArrayList<>(scopes)));
        }
    }

    private void refuseChange(String appId, String tenantDomain) throws IdentityApplicationManagementException {

        if (isApplicationOfCDS(appId, tenantDomain)) {
            throw new IdentityApplicationManagementClientException("The API authorization of the application '" +
                    CDSServiceCredentialsManager.APPLICATION_NAME + "' cannot be changed.");
        }
    }

    /*
     * The application of CDS is the one with its name, and only while the deployment reserves that name. Otherwise
     * a tenant could create an application with the name and get these APIs.
     */
    private static boolean isApplicationOfCDS(String appId, String tenantDomain)
            throws IdentityApplicationManagementException {

        ApplicationManagementService applicationManagementService = getApplicationManagementService();
        ApplicationBasicInfo application =
                applicationManagementService.getApplicationBasicInfoByResourceId(appId, tenantDomain);
        return application != null &&
                CDSServiceCredentialsManager.APPLICATION_NAME.equals(application.getApplicationName()) &&
                CDSServiceCredentialsManager.isReserved(applicationManagementService);
    }

    private static APIResource getAPIResource(String identifier, String tenantDomain)
            throws IdentityApplicationManagementException {

        try {
            return getAPIResourceManager().getAPIResourceByIdentifier(identifier, tenantDomain);
        } catch (APIResourceMgtException e) {
            throw new IdentityApplicationManagementException("Error while reading the API resource " + identifier,
                    e);
        }
    }

    private static AuthorizedAPI toAuthorizedAPI(String appId, APIResource apiResource) {

        List<String> names = MANAGEMENT_API_SCOPES.get(apiResource.getIdentifier());
        List<Scope> scopes = new ArrayList<>();
        if (apiResource.getScopes() != null) {
            for (Scope scope : apiResource.getScopes()) {
                if (names.contains(scope.getName())) {
                    scopes.add(scope);
                }
            }
        }
        AuthorizedAPI authorizedAPI = new AuthorizedAPI();
        authorizedAPI.setAppId(appId);
        authorizedAPI.setAPIId(apiResource.getId());
        authorizedAPI.setAPIIdentifier(apiResource.getIdentifier());
        authorizedAPI.setAPIName(apiResource.getName());
        authorizedAPI.setType(apiResource.getType());
        authorizedAPI.setPolicyId(RBAC);
        authorizedAPI.setScopes(scopes);
        return authorizedAPI;
    }

    private static ApplicationManagementService getApplicationManagementService() {

        return (ApplicationManagementService) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(ApplicationManagementService.class, null);
    }

    private static APIResourceManager getAPIResourceManager() {

        return (APIResourceManager) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(APIResourceManager.class, null);
    }
}

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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ApplicationBasicInfo;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.ServiceProviderProperty;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.application.mgt.inbound.dto.ApplicationDTO;
import org.wso2.carbon.identity.application.mgt.inbound.dto.InboundProtocolsDTO;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.oauth.IdentityOAuthAdminException;
import org.wso2.carbon.identity.oauth.OAuthAdminServiceImpl;
import org.wso2.carbon.identity.oauth.dto.OAuthConsumerAppDTO;
import org.wso2.carbon.identity.organization.management.application.OrgApplicationManager;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.client.Utils;
import org.wso2.identity.cds.client.exception.CDSException;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtServerException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.wso2.identity.cds.config.mgt.constant.CDSConfigMgtConstants.ErrorMessages.ERROR_CODE_CDS_SERVICE_CREDENTIALS;

/**
 * Sets up the application of a tenant that the Customer Data Service calls the Identity Server management APIs
 * with. An OAuth client belongs to one tenant, so each tenant that enables CDS gets its own application, and CDS gets
 * its credentials with POST /service-credentials/sync.
 *
 * CDS never creates the application and never holds Identity Server administrator credentials. The application is
 * created here, inside the Identity Server. CDSAuthorizedAPIListener authorizes it for the management APIs CDS calls
 * and nothing else, without storing the authorization.
 *
 * The application is shared with every sub organization of the tenant, existing and future, so that CDS gets a
 * token in a sub organization with the same credentials, at the token endpoint of that sub organization.
 *
 * The application is never deleted here. A deployment reserves its name in [system_applications] of
 * deployment.toml, which makes it read-only and blocks its deletion, so an application left by a failed enable is
 * reused by the next one.
 */
public class CDSServiceCredentialsManager {

    private static final Log log = LogFactory.getLog(CDSServiceCredentialsManager.class);

    static final String APPLICATION_NAME = "Customer Data Service";
    private static final String APPLICATION_DESCRIPTION =
            "Used by the Customer Data Service to call the Identity Server management APIs.";
    private static final String M2M_TEMPLATE_ID = "m2m-application";
    private static final String OAUTH2 = "oauth2";
    private static final String OAUTH_VERSION = "OAuth-2.0";
    private static final String CLIENT_CREDENTIALS = "client_credentials";
    // The application properties that the Identity Server sets on a shared application: isAppShared from IS 7.1,
    // shareWithAllChildren before it. Nobody else can share the reserved application, so either means it is shared
    // with all sub organizations.
    private static final List<String> SHARED_PROPERTIES = Arrays.asList("isAppShared", "shareWithAllChildren");

    // One set-up at a time per tenant, so that two enables do not both create the application, or renew the secret
    // the other one is sending.
    private static final ConcurrentMap<String, ReentrantLock> TENANT_LOCKS = new ConcurrentHashMap<>();

    /**
     * Make sure CDS holds working credentials for the application of a tenant. The application is created if the
     * tenant has none, and shared with its sub organizations. Its secret is renewed and sent to CDS with the org ID of
     * the tenant each time, because the Identity Server gives a client secret only when it creates or renews it, and
     * CDS may have lost the credentials it held, as after its database is restored. So enabling CDS again recovers
     * from credentials that no longer work. CDSAuthorizedAPIListener authorizes the application for the management
     * APIs CDS calls.
     *
     * If CDS stores no service credentials, it uses its configured client for every tenant, and nothing fails.
     *
     * @param tenantDomain Tenant domain of a root organization.
     * @throws CDSConfigMgtServerException If the application cannot be set up, or CDS does not take the credentials.
     */
    public void ensureServiceCredentials(String tenantDomain) throws CDSConfigMgtServerException {

        ReentrantLock lock = TENANT_LOCKS.computeIfAbsent(tenantDomain, tenant -> new ReentrantLock());
        lock.lock();
        try {
            setUp(tenantDomain);
        } finally {
            lock.unlock();
        }
    }

    private void setUp(String tenantDomain) throws CDSConfigMgtServerException {

        String sanitizedTenant = Utils.sanitizeForLog(tenantDomain);
        String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        try {
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantDomain(tenantDomain);
            carbonContext.setTenantId(IdentityTenantUtil.getTenantId(tenantDomain));
            if (StringUtils.isBlank(username)) {
                username = getTenantAdmin(tenantDomain);
            }
            carbonContext.setUsername(username);

            ApplicationManagementService applicationManagementService = getApplicationManagementService();
            ApplicationBasicInfo existing =
                    applicationManagementService.getApplicationBasicInfoByName(APPLICATION_NAME, tenantDomain);
            String resourceId = existing == null ? createApplication(tenantDomain, username) :
                    existing.getApplicationResourceId();
            ServiceProvider application = applicationManagementService.getApplicationByResourceId(resourceId,
                    tenantDomain);
            String clientId = getOAuthClientId(application);
            if (clientId == null) {
                throw serverException(null, sanitizedTenant, "The application '" + APPLICATION_NAME +
                        "' has no OAuth client.");
            }
            String organizationId = getOrganizationManager().resolveOrganizationId(tenantDomain);
            shareWithSubOrganizations(application, resourceId, organizationId, tenantDomain);

            String clientSecret = getOAuthAdminService().updateAndRetrieveOauthSecretKey(clientId)
                    .getOauthConsumerSecret();
            if (CDSClient.syncServiceCredentials(tenantDomain, organizationId, clientId, clientSecret)) {
                log.info("Sent the service credentials of tenant: " + sanitizedTenant + " to CDS.");
            } else if (log.isDebugEnabled()) {
                log.debug("CDS stores no service credentials, so none are sent for tenant: " + sanitizedTenant);
            }
        } catch (CDSException e) {
            throw serverException(e, sanitizedTenant, e.getDescription());
        } catch (IdentityApplicationManagementException | IdentityOAuthAdminException |
                 OrganizationManagementException | UserStoreException e) {
            throw serverException(e, sanitizedTenant, e.getMessage());
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    /*
     * Create the application with its OAuth client in one call, as the application management API does. The
     * Identity Server undoes a creation that fails part way.
     */
    private String createApplication(String tenantDomain, String username)
            throws IdentityApplicationManagementException {

        ServiceProvider serviceProvider = new ServiceProvider();
        serviceProvider.setApplicationName(APPLICATION_NAME);
        serviceProvider.setDescription(APPLICATION_DESCRIPTION);
        serviceProvider.setTemplateId(M2M_TEMPLATE_ID);

        OAuthConsumerAppDTO oAuthApp = new OAuthConsumerAppDTO();
        oAuthApp.setApplicationName(APPLICATION_NAME);
        oAuthApp.setOAuthVersion(OAUTH_VERSION);
        oAuthApp.setGrantTypes(CLIENT_CREDENTIALS);
        InboundProtocolsDTO inboundProtocols = new InboundProtocolsDTO();
        inboundProtocols.addProtocolConfiguration(oAuthApp);

        String resourceId = getApplicationManagementService().createApplication(new ApplicationDTO.Builder()
                .serviceProvider(serviceProvider).inboundProtocolConfigurationDto(inboundProtocols).build(),
                tenantDomain, username);
        log.info("Created the application '" + APPLICATION_NAME + "' of tenant: " +
                Utils.sanitizeForLog(tenantDomain));
        return resourceId;
    }

    /*
     * Share the application with all sub organizations of the tenant, including those created later. Sharing updates
     * the application, which the reserved name makes read-only, so CDSApplicationShareListener allows those updates
     * for this call only.
     */
    private void shareWithSubOrganizations(ServiceProvider application, String resourceId, String organizationId,
                                           String tenantDomain) throws OrganizationManagementException {

        if (application.getSpProperties() != null) {
            for (ServiceProviderProperty property : application.getSpProperties()) {
                if (SHARED_PROPERTIES.contains(property.getName()) && Boolean.parseBoolean(property.getValue())) {
                    return;
                }
            }
        }
        try {
            CDSApplicationShareListener.startSharing();
            getOrgApplicationManager().shareOrganizationApplication(organizationId, resourceId, true,
                    Collections.emptyList());
        } finally {
            CDSApplicationShareListener.endSharing();
        }
        log.info("Shared the application '" + APPLICATION_NAME + "' of tenant: " +
                Utils.sanitizeForLog(tenantDomain) + " with its sub organizations.");
    }

    private static String getOAuthClientId(ServiceProvider application) {

        if (application == null || application.getInboundAuthenticationConfig() == null ||
                application.getInboundAuthenticationConfig().getInboundAuthenticationRequestConfigs() == null) {
            return null;
        }
        for (InboundAuthenticationRequestConfig config :
                application.getInboundAuthenticationConfig().getInboundAuthenticationRequestConfigs()) {
            if (OAUTH2.equals(config.getInboundAuthType()) && StringUtils.isNotBlank(config.getInboundAuthKey())) {
                return config.getInboundAuthKey();
            }
        }
        return null;
    }

    private static String getTenantAdmin(String tenantDomain) throws UserStoreException {

        return IdentityTenantUtil.getRealmService().getTenantUserRealm(IdentityTenantUtil.getTenantId(tenantDomain))
                .getRealmConfiguration().getAdminUserName();
    }

    private static ApplicationManagementService getApplicationManagementService() {

        return (ApplicationManagementService) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(ApplicationManagementService.class, null);
    }

    private static OrgApplicationManager getOrgApplicationManager() {

        return (OrgApplicationManager) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(OrgApplicationManager.class, null);
    }

    private static OrganizationManager getOrganizationManager() {

        return (OrganizationManager) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(OrganizationManager.class, null);
    }

    private static OAuthAdminServiceImpl getOAuthAdminService() {

        return (OAuthAdminServiceImpl) PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .getOSGiService(OAuthAdminServiceImpl.class, null);
    }

    private static CDSConfigMgtServerException serverException(Throwable cause, String tenantDomain, String reason) {

        return new CDSConfigMgtServerException(ERROR_CODE_CDS_SERVICE_CREDENTIALS.getMessage(),
                String.format(ERROR_CODE_CDS_SERVICE_CREDENTIALS.getDescription(), tenantDomain, reason),
                ERROR_CODE_CDS_SERVICE_CREDENTIALS.getCode(), cause);
    }
}

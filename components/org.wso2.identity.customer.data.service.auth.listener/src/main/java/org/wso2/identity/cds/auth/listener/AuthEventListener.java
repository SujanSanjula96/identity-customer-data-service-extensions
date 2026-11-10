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

package org.wso2.identity.cds.auth.listener;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.UserIdNotFoundException;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.identity.cds.auth.listener.internal.AuthListenerServiceDataHolder;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtException;

import java.util.HashMap;
import java.util.Map;

import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.AUTHENTICATION_SUCCESS;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.SESSION_TERMINATE;

/**
 * This class contains the implementation of the Authentication event listener.
 * This handles authentication success events and communicates to CDS.
 */
public class AuthEventListener extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(AuthEventListener.class);
    public static final String USER_ID = "userId";
    public static final String ORG_HANDLE = "orgHandle";
    public static final String TENANT_DOMAIN = "tenant-domain";
    public static final String USER_TENANT_DOMAIN = "user-tenant-domain";
    public static final String PROFILE_COOKIE = "profileCookie";
    private static final String CDS_PROFILE_COOKIE = "cds_profile";
    private static final String ANONYMOUS_PROFILE_TRACKER = "anonymous_profile_tracker";
    
    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        Map<String, Object> eventProperties = event.getEventProperties();
        if (eventProperties == null) {
            LOG.debug("Event properties are null for event: " + event.getEventName());
            return;
        }

        AuthenticationContext authContext = (AuthenticationContext) eventProperties.get("context");
        if (authContext == null || !isCDSEnabled(authContext.getTenantDomain())) {
            return;
        }

        if (AUTHENTICATION_SUCCESS.equals(event.getEventName())) {
            AuthenticationContext context = (AuthenticationContext) eventProperties.get("context");
            if (context != null) {
                String cookieValue = (String) context.getProperty(CDS_PROFILE_COOKIE);
                LOG.debug("cds profile cookie captured during successful authentication context.");
                if (cookieValue == null || cookieValue.isEmpty()) {
                    LOG.debug("No cds profile cookie found in the authentication context.");
                    return;
                }
                try {
                    String userId;
                    AuthenticatedUser authenticatedUser = context.getLastAuthenticatedUser();
                    if (authenticatedUser == null) {
                        LOG.debug("Authenticated user is null in authentication context.");
                        return;
                    }
                    userId = authenticatedUser.getUserId();
                    if (StringUtils.isBlank(userId)) {
                        LOG.debug("User ID is blank in authentication context.");
                        return;
                    }
                    Map<String, Object> profileSyncPayload = new HashMap<>();
                    profileSyncPayload.put(PROFILE_COOKIE, cookieValue);
                    profileSyncPayload.put(USER_ID, userId);
                    String tenant = context.getTenantDomain();
                    profileSyncPayload.put(ORG_HANDLE, tenant);
                    CDSClient.triggerIdentityDataSync(event.getEventName(), profileSyncPayload, tenant);
                } catch (UserIdNotFoundException e) {
                    LOG.warn("User ID not found in authentication context.", e);
                }
            }
        }

        if (SESSION_TERMINATE.equals(event.getEventName())) {
            AuthenticationContext context = (AuthenticationContext) eventProperties.get("context");
            if (context != null) {
                String tenant = context.getTenantDomain();
                try {
                    AuthenticatedUser authenticatedUser = context.getLastAuthenticatedUser();
                    if (authenticatedUser == null) {
                        LOG.debug("Authenticated user is null in authentication context.");
                        Object userObj = context.getParameter("AuthenticatedUser");
                        if (userObj != null) {
                            LOG.debug("Attempting to extract user Id from context parameters.");
                            if (userObj instanceof AuthenticatedUser) {
                                authenticatedUser = (AuthenticatedUser) userObj;
                                LOG.debug("Successfully extracted authenticated user from context parameters.");
                            } else {
                                LOG.debug("AuthenticatedUser is not available in context parameters.");
                                return;
                            }
                        } else {
                            LOG.debug("No AuthenticatedUser parameter found in context.");
                            return;
                        }
                    }
                    String userId = authenticatedUser.getUserId();
                    if (StringUtils.isBlank(userId)) {
                        LOG.debug("User ID is blank in authentication context.");
                        return;
                    }
                    // Extract cds_profile cookie from the request headers
                    String cookieValue = null;
                    if (context.getAuthenticationRequest() != null
                            && context.getAuthenticationRequest().getRequestHeaders() != null) {
                        String cookieHeader = context.getAuthenticationRequest()
                                .getRequestHeaders().get("cookie");
                        if (StringUtils.isNotBlank(cookieHeader)) {
                            cookieValue = extractCookieValue(cookieHeader, CDS_PROFILE_COOKIE);
                        }
                    }
                    if (StringUtils.isBlank(cookieValue) && context.getAuthenticationRequest() != null) {
                        String[] trackerValues = context.getAuthenticationRequest()
                                .getRequestQueryParam(ANONYMOUS_PROFILE_TRACKER);
                        if (trackerValues != null && trackerValues.length > 0) {
                            cookieValue = trackerValues[0];
                            LOG.debug("Anonymous profile tracker captured during logout.");
                        }
                    }
                    LOG.debug("Cookie captured during logout: " + cookieValue);

                    Map<String, Object> profileSyncPayload = new HashMap<>();
                    profileSyncPayload.put(USER_ID, userId);
                    profileSyncPayload.put(ORG_HANDLE, tenant);
                    if (StringUtils.isNotBlank(cookieValue)) {
                        profileSyncPayload.put(PROFILE_COOKIE, cookieValue);
                    }
                    CDSClient.triggerIdentityDataSync(event.getEventName(), profileSyncPayload, tenant);
                } catch (UserIdNotFoundException e) {
                    LOG.warn("User ID not found in authentication context.", e);
                }
            }
        }
    }

    private String extractCookieValue(String cookieHeader, String cookieName) {
        if (StringUtils.isBlank(cookieHeader)) {
            return null;
        }
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(cookieName + "=")) {
                return trimmed.substring(cookieName.length() + 1);
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "cds.authentication.listener";
    }

    private boolean isCDSEnabled(String tenantDomain) {

        CDSConfigService cdsConfigService = AuthListenerServiceDataHolder.getCDSConfigService();
        if (cdsConfigService == null) {
            return false;
        }
        try {
            return cdsConfigService.isCDSEnabled(tenantDomain);
        } catch (CDSConfigMgtException e) {
            LOG.warn(e.getMessage() + ". CDS is not enabled for the tenant.", e);
            return false;
        }
    }
}

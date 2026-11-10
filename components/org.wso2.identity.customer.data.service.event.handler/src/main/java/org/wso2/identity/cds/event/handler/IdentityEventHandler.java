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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtException;
import org.wso2.identity.cds.event.handler.internal.EventHandlerDataHolder;

import java.util.HashMap;
import java.util.Map;

import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_ADD_USER;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_DELETE_USER_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_SET_USER_CLAIM_VALUES_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_SET_USER_CLAIM_VALUE_WITH_ID;
import static org.wso2.identity.cds.event.handler.Constants.CLAIMS;
import static org.wso2.identity.cds.event.handler.Constants.ORG_HANDLE;
import static org.wso2.identity.cds.event.handler.Constants.PROFILE_COOKIE;
import static org.wso2.identity.cds.event.handler.Constants.PROFILE_COOKIE_CLAIM;
import static org.wso2.identity.cds.event.handler.Constants.TENANT_DOMAIN;
import static org.wso2.identity.cds.event.handler.Constants.USER_CLAIMS_PROPERTY;
import static org.wso2.identity.cds.event.handler.Constants.USER_ID;
import static org.wso2.identity.cds.event.handler.Constants.USER_ID_CLAIM;
import static org.wso2.identity.cds.event.handler.Constants.USER_ID_PROPERTY;
import static org.wso2.identity.cds.event.handler.Constants.WSO2_CLAIMS_DIALECT;

/**
 * This class handles identity events and triggers profile syncs with the Customer Data Management Service.
 */
public class IdentityEventHandler extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(IdentityEventHandler.class);

    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        Map<String, Object> properties = event.getEventProperties();
        if (properties == null || !isCDSEnabled((String) properties.get(TENANT_DOMAIN))) {
            return;
        }
        String eventName = event.getEventName();
        if (POST_ADD_USER.equals(eventName)) {
            try {
                Map<String, Object> userClaims = (Map<String, Object>) properties.get(USER_CLAIMS_PROPERTY);
                if (userClaims == null || userClaims.isEmpty()) {
                    LOG.info("No USER_CLAIMS found in event properties.");
                    return;
                }

                // Filter userClaims to only include keys that start with "http://wso2.org/claims/"
                Map<String, Object> filteredUserClaims = userClaims.entrySet().stream()
                        .filter(entry -> entry.getKey().startsWith(WSO2_CLAIMS_DIALECT))
                        .collect(HashMap::new, (m, e) ->
                                m.put(e.getKey(), e.getValue()), HashMap::putAll);

                if (filteredUserClaims.isEmpty()) {
                    LOG.info("No WSO2 claims found in USER_CLAIMS.");
                    return;
                }

                String profileCookie = (String) filteredUserClaims.get(PROFILE_COOKIE_CLAIM);
                String userId = (String) filteredUserClaims.get(USER_ID_CLAIM);
                Map<String, Object> profileSyncPayload = new HashMap<>();
                profileSyncPayload.put(PROFILE_COOKIE, profileCookie);
                profileSyncPayload.put(USER_ID, userId);
                profileSyncPayload.put(CLAIMS, new HashMap<>(filteredUserClaims));
                profileSyncPayload.put(ORG_HANDLE, properties.get(TENANT_DOMAIN));
                String tenant = (String) properties.get(TENANT_DOMAIN);
                if (profileCookie == null || profileCookie.isEmpty()) {
                    CDSClient.triggerProfileSync(POST_ADD_USER, profileSyncPayload, tenant);
                    return;
                } else {
                    CDSClient.triggerIdentityDataSync(eventName, profileSyncPayload, tenant);
                }
            } catch (Exception e) {
                LOG.debug("Error handling event for CDS sync.", e);
            }
        }

        if (POST_SET_USER_CLAIM_VALUES_WITH_ID.equals(eventName) ||
                POST_SET_USER_CLAIM_VALUE_WITH_ID.equals(eventName)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> userClaims = (Map<String, Object>) properties.get(USER_CLAIMS_PROPERTY);

                if (userClaims != null) {
                    if (userClaims.isEmpty()) {
                        throw new IdentityEventException("No USER_CLAIMS found in event properties.");
                    }

                    String userId = properties.get(USER_ID_PROPERTY).toString();
                    // Filter userClaims to only include keys that start with "http://wso2.org/claims/"
                    Map<String, Object> filteredUserClaims = userClaims.entrySet().stream()
                            .filter(entry -> entry.getKey().startsWith(WSO2_CLAIMS_DIALECT))
                            .collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll);

                    Map<String, Object> profileSyncPayload = new HashMap<>();
                    profileSyncPayload.put(USER_ID, userId);
                    profileSyncPayload.put(CLAIMS, new HashMap<>(filteredUserClaims));
                    profileSyncPayload.put(ORG_HANDLE, properties.get(TENANT_DOMAIN));
                    String tenant = (String) properties.get(TENANT_DOMAIN);
                    CDSClient.triggerIdentityDataSync(eventName, profileSyncPayload, tenant);
                }

            } catch (Exception e) {
                LOG.debug("Error handling event for CDS sync.", e);
            }
        }

        if (POST_DELETE_USER_WITH_ID.equals(eventName)) {
            try {
            String userId = properties.get(USER_ID_PROPERTY).toString();
            Map<String, Object> profileSyncPayload = new HashMap<>();
            profileSyncPayload.put(USER_ID, userId);
            profileSyncPayload.put(ORG_HANDLE, properties.get(TENANT_DOMAIN));
            String tenant = (String) properties.get(TENANT_DOMAIN);
            CDSClient.triggerIdentityDataSync(eventName, profileSyncPayload, tenant);
            } catch (Exception e) {
                LOG.debug("Error handling event for CDS sync.", e);
            }
        }
    }

    @Override
    public String getName() {
        return "cds.identity.event.handler";
    }

    private boolean isCDSEnabled(String tenantDomain) {

        CDSConfigService cdsConfigService = EventHandlerDataHolder.getCDSConfigService();
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

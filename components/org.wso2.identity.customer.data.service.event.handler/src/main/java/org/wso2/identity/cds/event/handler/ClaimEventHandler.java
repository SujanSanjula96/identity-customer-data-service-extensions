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
import org.osgi.service.component.annotations.Reference;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtException;
import org.wso2.identity.cds.event.handler.internal.EventHandlerDataHolder;

import java.util.HashMap;
import java.util.Map;

import static org.wso2.identity.cds.event.handler.Constants.EVENT;
import static org.wso2.identity.cds.event.handler.Constants.ORG_HANDLE;

/**
 * This class handles identity events and triggers profile syncs with the Customer Data Management Service.
 */
public class ClaimEventHandler extends AbstractEventHandler {
    private static final Log LOG = LogFactory.getLog(ClaimEventHandler.class);

    @Reference
    private RealmService realmService;

    private static String safeLogValue(String s) {
        if (s == null) {
            return "null";
        }
        return s.replace('\r', '_').replace('\n', '_').
                replace('\t', '_');
    }


    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        String eventName = event.getEventName();
         if ("POST_ADD_EXTERNAL_CLAIM".equals(eventName) || "POST_UPDATE_EXTERNAL_CLAIM".equals(eventName) ||
                "POST_DELETE_EXTERNAL_CLAIM".equals(eventName) || "POST_UPDATE_LOCAL_CLAIM".equals(eventName)) {

             Object tenantIdObj = event.getEventProperties() != null ? event.getEventProperties().get("tenantId")
                     : null;
             if (!(tenantIdObj instanceof Integer)) {
                 if (LOG.isDebugEnabled()) {
                     LOG.debug("Skipping profile schema sync: tenantId is missing or invalid.");
                 }
                 return;
             }
             int tenantId = (Integer) tenantIdObj;
             String tenantDomain;
             tenantDomain = IdentityTenantUtil.getTenantDomain(tenantId);
             if (!isCDSEnabled(tenantDomain)) {
                 return;
             }
             Map<String, Object> profileSchemaSyncPayload = new HashMap<>();
             profileSchemaSyncPayload.put(EVENT, eventName);
             profileSchemaSyncPayload.put(ORG_HANDLE, tenantDomain);
             if (LOG.isDebugEnabled()) {
                 LOG.debug("Triggering profile schema sync for event: " + safeLogValue(eventName) +
                        " in tenant: " + safeLogValue(tenantDomain));
             }
             CDSClient.triggerProfileSchemasync(profileSchemaSyncPayload, tenantDomain);
        }
    }

    @Override
    public String getName() {
        return "cds.claim.event.handler";
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

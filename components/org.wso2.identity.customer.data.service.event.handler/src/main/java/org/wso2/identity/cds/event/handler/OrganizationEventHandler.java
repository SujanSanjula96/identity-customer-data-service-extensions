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
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.IdentityEventException;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.client.Utils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pushes organization lifecycle events to CDS, so that CDS provisions a new sub organization and removes a deleted
 * one. The payload names only the event and the organization ID. CDS reads the organization details from the
 * organization management API, so the payload does not depend on the IS organization model.
 */
public class OrganizationEventHandler extends AbstractEventHandler {

    private static final Log LOG = LogFactory.getLog(OrganizationEventHandler.class);

    static final String POST_ADD_ORGANIZATION = "POST_ADD_ORGANIZATION";
    static final String POST_UPDATE_ORGANIZATION = "POST_UPDATE_ORGANIZATION";
    static final String POST_PATCH_ORGANIZATION = "POST_PATCH_ORGANIZATION";
    static final String POST_DELETE_ORGANIZATION = "POST_DELETE_ORGANIZATION";
    private static final String EVENT_PROP_ORGANIZATION = "ORGANIZATION";
    private static final String EVENT_PROP_ORGANIZATION_ID = "ORGANIZATION_ID";

    // CDS event names, which are neutral to the identity provider.
    static final String ORG_CREATED = "ORG_CREATED";
    static final String ORG_UPDATED = "ORG_UPDATED";
    static final String ORG_DELETED = "ORG_DELETED";

    /*
     * CDS calls IS back to read the organization. The event can fire before IS commits the organization, so the
     * call to CDS runs on another thread and does not block the organization operation.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cds-organization-sync");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void handleEvent(Event event) throws IdentityEventException {

        if (!Utils.isCDSEnabled()) {
            return;
        }
        String cdsEvent = toCdsEvent(event.getEventName());
        if (cdsEvent == null) {
            return;
        }
        String orgId = resolveOrganizationId(event.getEventProperties());
        if (isBlank(orgId)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Skipping CDS organization sync: no organization ID in event " + event.getEventName());
            }
            return;
        }
        String tenantDomain = IdentityTenantUtil.getTenantDomainFromContext();
        Map<String, Object> payload = new HashMap<>();
        payload.put(Constants.EVENT, cdsEvent);
        payload.put("org_id", orgId);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Sending organization event " + cdsEvent + " for organization " + Utils.sanitizeForLog(orgId)
                    + " to CDS through tenant " + Utils.sanitizeForLog(tenantDomain));
        }
        EXECUTOR.submit(() -> CDSClient.triggerOrganizationSync(payload, tenantDomain));
    }

    static String toCdsEvent(String eventName) {

        if (POST_ADD_ORGANIZATION.equals(eventName)) {
            return ORG_CREATED;
        }
        if (POST_UPDATE_ORGANIZATION.equals(eventName) || POST_PATCH_ORGANIZATION.equals(eventName)) {
            return ORG_UPDATED;
        }
        if (POST_DELETE_ORGANIZATION.equals(eventName)) {
            return ORG_DELETED;
        }
        return null;
    }

    /**
     * Reads the organization ID from the ORGANIZATION_ID property, or from the ORGANIZATION object. The object is
     * read by reflection, so that this bundle does not import the organization management model.
     */
    static String resolveOrganizationId(Map<String, Object> properties) {

        if (properties == null) {
            return null;
        }
        Object id = properties.get(EVENT_PROP_ORGANIZATION_ID);
        if (id instanceof String && !isBlank((String) id)) {
            return (String) id;
        }
        Object organization = properties.get(EVENT_PROP_ORGANIZATION);
        if (organization == null) {
            return null;
        }
        try {
            Method getId = organization.getClass().getMethod("getId");
            Object value = getId.invoke(organization);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException e) {
            LOG.debug("Could not read the organization ID from the event.", e);
            return null;
        }
    }

    private static boolean isBlank(String value) {

        return value == null || value.trim().isEmpty();
    }

    @Override
    public String getName() {

        return "cds.organization.event.handler";
    }
}

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

package org.wso2.identity.cds.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.identity.cds.client.exception.CDSClientException;
import org.wso2.identity.cds.client.exception.CDSException;
import org.wso2.identity.cds.client.exception.CDSServerException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * This class handles communication with the Customer Data Service (CDS).
 */
public class CDSClient {

    private static final Log log = LogFactory.getLog(CDSClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // The second value is the path of the org: /t/{root_handle} or /t/{root_handle}/o/{org_id}.
    private static final String PROFILE_SYNC_API = "%s%s/cds/api/v1/profiles/sync";
    private static final String PROFILE_SCHEMA_SYNC_API = "%s%s/cds/api/v1/profile-schema/sync";
    private static final String ORGANIZATION_SYNC_API = "%s%s/cds/api/v1/organizations/event-sync";
    // The CDS config is set on the root organization only.
    private static final String CONFIG_API = "%s/t/%s/cds/api/v1/config";
    private static final String CDS_ENABLED = "cds_enabled";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String AUTHORIZATION = "Authorization";
    private static final String APPLICATION_JSON = "application/json";
    private static final String EVENT = "event";
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_CLIENT_ERROR_MIN = 400;
    private static final int HTTP_SERVER_ERROR_MIN = 500;
    private static final String CDS_NOT_ENABLED_ERROR_CODE = "CDS-16001";

    // Trigger identity data sync in CDS
    public static void triggerIdentityDataSync(String event, Map<String, Object> payload, String tenant) {
        payload.put(EVENT, event);
        try {
            String json = MAPPER.writeValueAsString(payload);
            String url = buildProfileSyncAPI(tenant);
            doPost(url, json, tenant, "identity-data-sync");
        } catch (IOException e) {
            log.warn("I/O error while triggering CDS identity data sync. tenant="
                    + Utils.sanitizeForLog(tenant), e);
        } catch (OrganizationManagementException e) {
            logUnresolvedPath("identity data sync", tenant, e);
        }
    }

    // Trigger Profile data sync in CDS
    public static void triggerProfileSync(String event, Map<String, Object> payload, String tenant) {

        payload.put(EVENT, event);
        try {
            String json = MAPPER.writeValueAsString(payload);
            String url = buildProfileSyncAPI(tenant);
            doPost(url, json, tenant, "profile-sync");
        } catch (IOException e) {
            log.warn("I/O error while triggering CDS profile sync. tenant="
                    + Utils.sanitizeForLog(tenant), e);
        } catch (OrganizationManagementException e) {
            logUnresolvedPath("profile sync", tenant, e);
        }
    }

    public static void triggerProfileSchemasync(Map<String, Object> payload, String tenant) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            String url = buildProfileSchemaSyncAPI(tenant);
            doPost(url, json, tenant, "profile-schema-sync");
        } catch (IOException e) {
            log.warn("I/O error while triggering CDS profile schema sync. tenant="
                    + Utils.sanitizeForLog(tenant), e);
        } catch (OrganizationManagementException e) {
            logUnresolvedPath("profile schema sync", tenant, e);
        }
    }

    // Trigger organization sync in CDS
    public static void triggerOrganizationSync(Map<String, Object> payload, String tenant) {

        try {
            String json = MAPPER.writeValueAsString(payload);
            String url = String.format(ORGANIZATION_SYNC_API, Utils.getCDSServiceURL(),
                    OrganizationPathResolver.pathOf(tenant));
            doPost(url, json, tenant, "organization-sync");
        } catch (IOException e) {
            log.warn("I/O error while triggering CDS organization sync. tenant="
                    + Utils.sanitizeForLog(tenant), e);
        } catch (OrganizationManagementException e) {
            logUnresolvedPath("organization sync", tenant, e);
        }
    }

    private static void logUnresolvedPath(String operation, String tenant, OrganizationManagementException e) {

        log.error("Could not resolve the CDS path of the organization for tenant: " +
                Utils.sanitizeForLog(tenant) + ". The CDS " + operation + " is not sent.", e);
    }

    /**
     * Send the CDS enable config of a tenant to CDS with PATCH /config. The call is synchronous, so the caller
     * knows if CDS accepted the value.
     *
     * @param tenant     Tenant domain.
     * @param cdsEnabled The new value.
     * @throws CDSException CDSClientException if CDS returns a 4xx status. CDSServerException if CDS is not
     *                      configured, cannot be reached, or returns another status.
     */
    public static void updateCDSConfig(String tenant, boolean cdsEnabled) throws CDSException {

        String serviceURL = Utils.getCDSServiceURL();
        String credentials = Utils.getBase64EncodedCredentials();
        if (StringUtils.isBlank(serviceURL) || StringUtils.isBlank(credentials)) {
            String error = "The Customer Data Service is not configured.";
            throw new CDSServerException(error, error, null);
        }

        String sanitizedTenant = Utils.sanitizeForLog(tenant);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPatch httpPatch = new HttpPatch(String.format(CONFIG_API, serviceURL, tenant));
            httpPatch.setEntity(new StringEntity(
                    MAPPER.writeValueAsString(Collections.singletonMap(CDS_ENABLED, cdsEnabled)),
                    StandardCharsets.UTF_8));
            httpPatch.setHeader(CONTENT_TYPE, APPLICATION_JSON);
            httpPatch.setHeader(AUTHORIZATION, "Basic " + credentials);

            try (CloseableHttpResponse response = client.execute(httpPatch)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == HTTP_OK) {
                    if (log.isDebugEnabled()) {
                        log.debug("CDS config update succeeded for tenant: " + sanitizedTenant);
                    }
                    return;
                }

                String responseBody = "";
                if (response.getEntity() != null && response.getEntity().getContent() != null) {
                    responseBody = new String(response.getEntity().getContent().readAllBytes(),
                            StandardCharsets.UTF_8);
                }
                CdsError cdsError = parseCdsError(responseBody);
                String description = String.format("The Customer Data Service returned status %d, code %s: %s",
                        statusCode, cdsError.code, cdsError.description);
                if (statusCode >= HTTP_CLIENT_ERROR_MIN && statusCode < HTTP_SERVER_ERROR_MIN) {
                    throw new CDSClientException(cdsError.message, description, cdsError.code);
                }
                throw new CDSServerException(cdsError.message, description, cdsError.code);
            }
        } catch (IOException e) {
            log.warn("I/O error while updating the CDS config. tenant=" + sanitizedTenant, e);
            throw new CDSServerException("The Customer Data Service cannot be reached.",
                    "An I/O error occurred while the CDS configuration was sent.", null, e);
        }
    }

    // Execute HTTP POST request to CDS
    private static void doPost(String url, String jsonBody, String tenant, String operation) throws IOException {

        String sanitizedTenant = Utils.sanitizeForLog(tenant);
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost httpPost = new HttpPost(url);
            httpPost.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
            httpPost.setHeader(CONTENT_TYPE, APPLICATION_JSON);
            httpPost.setHeader(AUTHORIZATION, "Basic " + Utils.getBase64EncodedCredentials());

            try (CloseableHttpResponse response = client.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String responseBody = "";

                if (response.getEntity() != null && response.getEntity().getContent() != null) {
                    responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                }

                if (statusCode == 200 || statusCode == 204) {
                    if (log.isDebugEnabled()) {
                        log.debug("CDS " + Utils.sanitizeForLog(operation) + " succeeded for tenant: " +
                                sanitizedTenant);
                    }
                    return;
                }

                CdsError cdsError = parseCdsError(responseBody);

                if (isCdsNotEnabled(statusCode, cdsError.code)) {
                    if (log.isDebugEnabled()) {
                        log.debug("CDS " + Utils.sanitizeForLog(operation) + " skipped as CDS is not enabled for " +
                                "tenant: " + sanitizedTenant);
                    }
                    return;
                }

                log.warn(String.format("CDS %s failed for tenant=%s with status=%d, code=%s, message=%s, " +
                                "description=%s",
                        Utils.sanitizeForLog(operation),
                        sanitizedTenant,
                        statusCode,
                        Utils.sanitizeForLog(cdsError.code),
                        Utils.sanitizeForLog(cdsError.message),
                        Utils.sanitizeForLog(cdsError.description)));
            }
        }
    }

    /**
     * CDS rejects sync requests sent for an organization that has not enabled the customer data service. That is an
     * expected response for such organizations rather than a sync failure, so it is not logged as a warning.
     *
     * @param statusCode HTTP status code of the CDS response.
     * @param errorCode  Error code returned by CDS.
     * @return true if the response indicates that CDS is not enabled for the organization.
     */
    static boolean isCdsNotEnabled(int statusCode, String errorCode) {
        return statusCode == HTTP_BAD_REQUEST && CDS_NOT_ENABLED_ERROR_CODE.equals(errorCode);
    }

    private static CdsError parseCdsError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return new CdsError("n/a", "n/a", "n/a");
        }

        try {
            JsonNode node = MAPPER.readTree(responseBody);
            return new CdsError(
                    getTextOrDefault(node, "code", "n/a"),
                    getTextOrDefault(node, "message", "n/a"),
                    getTextOrDefault(node, "description", "n/a")
            );
        } catch (JsonProcessingException e) {
            return new CdsError("parse_error", "Unexpected response format", "Unable to parse error response");
        }
    }

    private static String getTextOrDefault(JsonNode node, String field, String defaultValue) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? defaultValue : v.asText();
    }

    // Build the Profile Sync API URL
    private static String buildProfileSyncAPI(String tenant) throws OrganizationManagementException {

        return String.format(PROFILE_SYNC_API, Utils.getCDSServiceURL(), OrganizationPathResolver.pathOf(tenant));
    }

    // Build the Profile Schema Sync API URL
    private static String buildProfileSchemaSyncAPI(String tenant) throws OrganizationManagementException {

        return String.format(PROFILE_SCHEMA_SYNC_API, Utils.getCDSServiceURL(),
                OrganizationPathResolver.pathOf(tenant));
    }

    private static final class CdsError {
        private final String code;
        private final String message;
        private final String description;

        private CdsError(String code, String message, String description) {
            this.code = code;
            this.message = message;
            this.description = description;
        }
    }
}

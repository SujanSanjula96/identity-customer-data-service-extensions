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
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.identity.cds.client.exception.CDSClientException;
import org.wso2.identity.cds.client.exception.CDSException;
import org.wso2.identity.cds.client.exception.CDSServerException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_ADD_USER;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_DELETE_USER_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_SET_USER_CLAIM_VALUES_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_SET_USER_CLAIM_VALUE_WITH_ID;

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
    private static final String CONFIG_SYNC_API = "%s/t/%s/cds/api/v1/config/sync";
    private static final String CDS_ENABLED = "cds_enabled";
    // Service credentials are synced on the root organization only.
    private static final String SERVICE_CREDENTIALS_SYNC_API = "%s/t/%s/cds/api/v1/service-credentials/sync";
    private static final String SERVICE_CREDENTIALS_UPDATED = "SERVICE_CREDENTIALS_UPDATED";
    private static final String ORG_ID = "org_id";
    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String AUTHORIZATION = "Authorization";
    private static final String APPLICATION_JSON = "application/json";
    private static final String EVENT = "event";
    private static final int HTTP_OK = 200;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_CLIENT_ERROR_MIN = 400;
    private static final int HTTP_SERVER_ERROR_MIN = 500;
    private static final int HTTP_NO_CONTENT = 204;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final String CDS_NOT_ENABLED_ERROR_CODE = "CDS-16001";
    private static final String SERVICE_CREDENTIALS_NOT_CONFIGURED_ERROR_CODE = "CDS-16004";
    private static final String USER_ID = "userId";
    // The syncs of these user events are sent again once when they fail without a response or with a 5xx status.
    // CDS cannot recover them from a later event: a lost delete, for example, leaves the profile of the deleted
    // user in CDS. CDS applies them by user ID, so a sync that CDS already applied is safe to send again.
    static final int USER_SYNC_MAX_ATTEMPTS = 2;
    private static final Set<String> RETRIED_USER_EVENTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            POST_ADD_USER, POST_DELETE_USER_WITH_ID, POST_SET_USER_CLAIM_VALUE_WITH_ID,
            POST_SET_USER_CLAIM_VALUES_WITH_ID)));

    // Trigger identity data sync in CDS
    public static void triggerIdentityDataSync(String event, Map<String, Object> payload, String tenant) {
        payload.put(EVENT, event);
        try {
            String json = MAPPER.writeValueAsString(payload);
            String url = buildProfileSyncAPI(tenant);
            sendUserSync(url, json, tenant, "identity-data-sync", event, payload.get(USER_ID));
        } catch (IOException e) {
            log.warn("I/O error while triggering CDD identity data sync. tenant="
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
            sendUserSync(url, json, tenant, "profile-sync", event, payload.get(USER_ID));
        } catch (IOException e) {
            log.warn("I/O error while triggering CDD profile sync. tenant="
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
            log.warn("I/O error while triggering CDD profile schema sync. tenant="
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
            log.warn("I/O error while triggering CDD organization sync. tenant="
                    + Utils.sanitizeForLog(tenant), e);
        } catch (OrganizationManagementException e) {
            logUnresolvedPath("organization sync", tenant, e);
        }
    }

    private static void logUnresolvedPath(String operation, String tenant, OrganizationManagementException e) {

        log.error("Could not resolve the CDD path of the organization for tenant: " +
                Utils.sanitizeForLog(tenant) + ". The CDD " + operation + " is not sent.", e);
    }

    /**
     * Send the CDS enable config of a tenant to CDS with POST /config/sync. The call is synchronous, so the caller
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
            String error = "The Customer Data Directory is not configured.";
            throw new CDSServerException(error, error, null);
        }

        String sanitizedTenant = Utils.sanitizeForLog(tenant);
        try {
            CloseableHttpClient client = CDSHttpClient.get();
            HttpPost httpPost = new HttpPost(String.format(CONFIG_SYNC_API, serviceURL, tenant));
            httpPost.setConfig(CDSHttpClient.adminRequestConfig());
            httpPost.setEntity(new StringEntity(
                    MAPPER.writeValueAsString(Collections.singletonMap(CDS_ENABLED, cdsEnabled)),
                    StandardCharsets.UTF_8));
            httpPost.setHeader(CONTENT_TYPE, APPLICATION_JSON);
            httpPost.setHeader(AUTHORIZATION, "Basic " + credentials);

            try (CloseableHttpResponse response = client.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == HTTP_OK) {
                    if (log.isDebugEnabled()) {
                        log.debug("CDD config update succeeded for tenant: " + sanitizedTenant);
                    }
                    return;
                }

                String responseBody = "";
                if (response.getEntity() != null && response.getEntity().getContent() != null) {
                    responseBody = new String(response.getEntity().getContent().readAllBytes(),
                            StandardCharsets.UTF_8);
                }
                CdsError cdsError = parseCdsError(responseBody);
                String description = String.format("The Customer Data Directory returned status %d, code %s: %s",
                        statusCode, cdsError.code, cdsError.description);
                if (statusCode >= HTTP_CLIENT_ERROR_MIN && statusCode < HTTP_SERVER_ERROR_MIN) {
                    throw new CDSClientException(cdsError.message, description, cdsError.code);
                }
                throw new CDSServerException(cdsError.message, description, cdsError.code);
            }
        } catch (IOException e) {
            log.warn("I/O error while updating the CDD config. tenant=" + sanitizedTenant, e);
            throw new CDSServerException("The Customer Data Directory cannot be reached.",
                    "An I/O error occurred while the CDD configuration was sent.", null, e);
        }
    }

    // Execute HTTP POST request to CDS
    private static void doPost(String url, String jsonBody, String tenant, String operation) throws IOException {

        String sanitizedTenant = Utils.sanitizeForLog(tenant);
        PostResult result = post(url, jsonBody);

        if (result.succeeded()) {
            if (log.isDebugEnabled()) {
                log.debug("CDD " + Utils.sanitizeForLog(operation) + " succeeded for tenant: " +
                        sanitizedTenant);
            }
            return;
        }

        if (result.isCdsNotEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("CDD " + Utils.sanitizeForLog(operation) + " skipped as CDD is not enabled for " +
                        "tenant: " + sanitizedTenant);
            }
            return;
        }

        log.warn(String.format("CDD %s failed for tenant=%s with status=%d, code=%s, message=%s, " +
                        "description=%s",
                Utils.sanitizeForLog(operation),
                sanitizedTenant,
                result.statusCode,
                Utils.sanitizeForLog(result.cdsError.code),
                Utils.sanitizeForLog(result.cdsError.message),
                Utils.sanitizeForLog(result.cdsError.description)));
    }

    /**
     * Send the sync of a user event to CDS. The sync of an event in {@link #RETRIED_USER_EVENTS} that fails without a
     * response or with a 5xx status is sent again once, and a WARN with the user ID is logged only when every attempt
     * fails. The sync of any other event, such as a sign-in, is sent once, as CDS gets it again with a later event.
     *
     * @param url       URL of the sync API.
     * @param jsonBody  Body of the sync.
     * @param tenant    Tenant domain.
     * @param operation Name of the sync, for the logs.
     * @param event     Name of the user event.
     * @param userId    ID of the user, for the logs.
     * @throws IOException If the sync of an event that is not retried cannot be sent.
     */
    private static void sendUserSync(String url, String jsonBody, String tenant, String operation, String event,
                                     Object userId) throws IOException {

        if (!RETRIED_USER_EVENTS.contains(event)) {
            doPost(url, jsonBody, tenant, operation);
            return;
        }

        String sanitizedOperation = Utils.sanitizeForLog(operation);
        String sanitizedUserId = Utils.sanitizeForLog(userId);
        String sanitizedTenant = Utils.sanitizeForLog(tenant);
        String sanitizedEvent = Utils.sanitizeForLog(event);
        List<String> failures = new ArrayList<>();
        for (int attempt = 1; attempt <= USER_SYNC_MAX_ATTEMPTS; attempt++) {
            try {
                PostResult result = post(url, jsonBody);
                if (result.succeeded() || result.isCdsNotEnabled()) {
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("CDD %s of event %s for user: %s, tenant: %s %s on attempt %d.",
                                sanitizedOperation, sanitizedEvent, sanitizedUserId, sanitizedTenant,
                                result.succeeded() ? "succeeded" : "skipped as CDD is not enabled", attempt));
                    }
                    return;
                }
                if (!result.isServerError()) {
                    // CDS refused the sync, so sending it again would fail the same way.
                    log.warn(String.format("CDD %s failed for user: %s, tenant: %s, event: %s. The change is not " +
                                    "synced to CDD. status=%d, code=%s, message=%s, description=%s",
                            sanitizedOperation, sanitizedUserId, sanitizedTenant, sanitizedEvent,
                            result.statusCode, Utils.sanitizeForLog(result.cdsError.code),
                            Utils.sanitizeForLog(result.cdsError.message),
                            Utils.sanitizeForLog(result.cdsError.description)));
                    return;
                }
                failures.add("status " + result.statusCode + ", code " + Utils.sanitizeForLog(result.cdsError.code));
            } catch (IOException e) {
                failures.add(Utils.sanitizeForLog(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            if (attempt < USER_SYNC_MAX_ATTEMPTS && log.isDebugEnabled()) {
                log.debug(String.format("CDD %s of event %s failed for user: %s, tenant: %s (%s). Retrying once.",
                        sanitizedOperation, sanitizedEvent, sanitizedUserId, sanitizedTenant,
                        failures.get(failures.size() - 1)));
            }
        }
        log.warn(String.format("CDD %s failed for user: %s, tenant: %s, event: %s, after %d attempts (%s). " +
                        "The change is not synced to CDD.", sanitizedOperation, sanitizedUserId, sanitizedTenant,
                sanitizedEvent, USER_SYNC_MAX_ATTEMPTS, String.join("; ", failures)));
    }

    /**
     * Send a POST request to CDS and read its response.
     *
     * @param url      URL of the API.
     * @param jsonBody Body of the request.
     * @return The status and the error of the response.
     * @throws IOException If the request cannot be sent or the response cannot be read.
     */
    private static PostResult post(String url, String jsonBody) throws IOException {

        CloseableHttpClient client = CDSHttpClient.get();
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
            if (statusCode == HTTP_OK || statusCode == HTTP_NO_CONTENT) {
                return new PostResult(statusCode, null);
            }
            return new PostResult(statusCode, parseCdsError(responseBody));
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

    /**
     * Send the credentials of the application CDS calls the Identity Server with for a tenant, with
     * POST /service-credentials/sync. CDS gets a token with the credentials before it stores them.
     *
     * @param tenant       Tenant domain of a root organization.
     * @param orgId        Organization ID of the tenant, which CDS keys the credentials by.
     * @param clientId     Client ID of the application.
     * @param clientSecret Client secret of the application.
     * @return false if CDS does not store service credentials: it has no encryption key (CDS-16004), or it is a CDS
     * without the endpoint. CDS then uses its configured client for every organization.
     * @throws CDSException CDSClientException if CDS refuses the credentials. CDSServerException if CDS is not
     *                      configured, cannot be reached, or fails.
     */
    public static boolean syncServiceCredentials(String tenant, String orgId, String clientId, String clientSecret)
            throws CDSException {

        String serviceURL = Utils.getCDSServiceURL();
        String credentials = Utils.getBase64EncodedCredentials();
        if (StringUtils.isBlank(serviceURL) || StringUtils.isBlank(credentials)) {
            String error = "The Customer Data Directory is not configured.";
            throw new CDSServerException(error, error, null);
        }

        Map<String, String> event = new HashMap<>();
        event.put(EVENT, SERVICE_CREDENTIALS_UPDATED);
        event.put(ORG_ID, orgId);
        event.put(CLIENT_ID, clientId);
        event.put(CLIENT_SECRET, clientSecret);

        String sanitizedTenant = Utils.sanitizeForLog(tenant);
        try {
            CloseableHttpClient client = CDSHttpClient.get();
            HttpPost httpPost = new HttpPost(String.format(SERVICE_CREDENTIALS_SYNC_API, serviceURL, tenant));
            httpPost.setConfig(CDSHttpClient.adminRequestConfig());
            httpPost.setEntity(new StringEntity(MAPPER.writeValueAsString(event), StandardCharsets.UTF_8));
            httpPost.setHeader(CONTENT_TYPE, APPLICATION_JSON);
            httpPost.setHeader(AUTHORIZATION, "Basic " + credentials);

            try (CloseableHttpResponse response = client.execute(httpPost)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == HTTP_OK || statusCode == HTTP_NO_CONTENT) {
                    if (log.isDebugEnabled()) {
                        log.debug("CDD service credentials sync succeeded for tenant: " + sanitizedTenant);
                    }
                    return true;
                }
                if (statusCode == HTTP_NOT_FOUND || statusCode == HTTP_METHOD_NOT_ALLOWED) {
                    // A CDS without the endpoint uses its configured client.
                    return false;
                }

                String responseBody = "";
                if (response.getEntity() != null && response.getEntity().getContent() != null) {
                    responseBody = new String(response.getEntity().getContent().readAllBytes(),
                            StandardCharsets.UTF_8);
                }
                CdsError cdsError = parseCdsError(responseBody);
                if (statusCode == HTTP_BAD_REQUEST && SERVICE_CREDENTIALS_NOT_CONFIGURED_ERROR_CODE.equals(
                        cdsError.code)) {
                    return false;
                }
                String description = String.format("The Customer Data Directory returned status %d, code %s: %s",
                        statusCode, cdsError.code, cdsError.description);
                if (statusCode >= HTTP_CLIENT_ERROR_MIN && statusCode < HTTP_SERVER_ERROR_MIN) {
                    throw new CDSClientException(cdsError.message, description, cdsError.code);
                }
                throw new CDSServerException(cdsError.message, description, cdsError.code);
            }
        } catch (IOException e) {
            log.warn("I/O error while syncing the CDD service credentials. tenant=" + sanitizedTenant, e);
            throw new CDSServerException("The Customer Data Directory cannot be reached.",
                    "An I/O error occurred while the service credentials were sent.", null, e);
        }
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

    /**
     * The status of a response of CDS, and the error it returned when it did not succeed.
     */
    private static final class PostResult {
        private final int statusCode;
        private final CdsError cdsError;

        private PostResult(int statusCode, CdsError cdsError) {
            this.statusCode = statusCode;
            this.cdsError = cdsError;
        }

        private boolean succeeded() {
            return cdsError == null;
        }

        private boolean isCdsNotEnabled() {
            return cdsError != null && CDSClient.isCdsNotEnabled(statusCode, cdsError.code);
        }

        private boolean isServerError() {
            return statusCode >= HTTP_SERVER_ERROR_MIN;
        }
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

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

import com.sun.net.httpserver.HttpServer;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.identity.cds.client.exception.CDSClientException;
import org.wso2.identity.cds.client.exception.CDSException;
import org.wso2.identity.cds.client.exception.CDSServerException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.mockStatic;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Unit tests for the service credentials sync of {@link CDSClient}, against a stub CDS.
 */
public class CDSClientServiceCredentialsTest {

    private static final String TENANT = "acme";
    private static final String PATH = "/t/acme/cds/api/v1/service-credentials/sync";
    private static final String CREDENTIALS = "c3luYzpzeW5jLXBhc3N3b3Jk";

    private HttpServer cds;
    private MockedStatic<Utils> utils;
    private int status;
    private String responseBody;
    private String requestMethod;
    private String requestBody;
    private String requestAuthorization;

    @BeforeMethod
    public void setUp() throws IOException {

        cds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cds.createContext(PATH, exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        cds.start();

        utils = mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS);
        utils.when(Utils::getCDSServiceURL).thenReturn("http://127.0.0.1:" + cds.getAddress().getPort());
        utils.when(Utils::getBase64EncodedCredentials).thenReturn(CREDENTIALS);
    }

    @AfterMethod
    public void tearDown() {

        utils.close();
        cds.stop(0);
    }

    private void respond(int status, String body) {

        this.status = status;
        this.responseBody = body;
    }

    @Test
    public void testSyncSendsTheCredentials() throws CDSException {

        respond(204, "");
        assertTrue(CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client", "acme-secret"));
        assertEquals(requestMethod, "POST");
        assertEquals(requestAuthorization, "Basic " + CREDENTIALS);
        assertTrue(requestBody.contains("\"event\":\"SERVICE_CREDENTIALS_UPDATED\""));
        assertTrue(requestBody.contains("\"org_id\":\"acme-org-id\""));
        assertTrue(requestBody.contains("\"client_id\":\"acme-client\""));
        assertTrue(requestBody.contains("\"client_secret\":\"acme-secret\""));
    }

    @Test
    public void testSyncTellsThatCDSStoresNoServiceCredentials() throws CDSException {

        respond(400, "{\"code\":\"CDS-16004\",\"message\":\"m\",\"description\":\"d\"}");
        assertFalse(CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client", "acme-secret"));
    }

    // A CDS from before the endpoint uses its configured client, so enabling CDS must not fail on it.
    @Test
    public void testSyncTellsThatACDSWithoutTheEndpointStoresNoServiceCredentials() throws CDSException {

        respond(404, "");
        assertFalse(CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client", "acme-secret"));
    }

    @Test
    public void testSyncFailsWhenCDSRejectsTheCredentials() {

        respond(400, "{\"code\":\"CDS-16003\",\"message\":\"m\",\"description\":\"refused\"}");
        try {
            CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client", "wrong-secret");
            fail("Expected a CDSClientException.");
        } catch (CDSClientException e) {
            assertEquals(e.getErrorCode(), "CDS-16003");
            assertFalse(e.getMessage().contains("wrong-secret"));
            assertFalse(e.getDescription().contains("wrong-secret"));
        } catch (CDSException e) {
            fail("Expected a CDSClientException, got " + e);
        }
    }

    @Test
    public void testSyncFailsWhenCDSIsNotConfigured() {

        utils.when(Utils::getCDSServiceURL).thenReturn(null);
        try {
            CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client", "acme-secret");
            fail("Expected a CDSServerException.");
        } catch (CDSServerException e) {
            assertTrue(e.getMessage().contains("not configured"));
        } catch (CDSException e) {
            fail("Expected a CDSServerException, got " + e);
        }
    }
}

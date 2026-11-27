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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.AUTHENTICATION_SUCCESS;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_ADD_USER;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_DELETE_USER_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_SET_USER_CLAIM_VALUES_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.POST_SET_USER_CLAIM_VALUE_WITH_ID;
import static org.wso2.carbon.identity.event.IdentityEventConstants.Event.SESSION_TERMINATE;

/**
 * Tests that the syncs of user changes CDS cannot recover from a later event are sent again once when they fail
 * without a response or with a 5xx status, that the other syncs are sent once, and that a WARN with the user ID is
 * logged only when every attempt fails.
 */
public class CDSClientRetryTest {

    private static final String TENANT = "acme";
    private static final String USER_ID = "8a6e3c1f-6c1b-4d5e-9a3e-0e5c2b7d9f10";
    private static final String EMAIL = "alice@acme.example";
    private static final String STALL = "stall";
    // Shorter than the time a stalled answer of the fake CDS waits, so a stalled attempt ends in a read timeout.
    private static final String READ_TIMEOUT_MILLIS = "500";
    private static final String FINAL_WARN = "The change is not synced to CDD.";
    private static final String CDS_REFUSED = "{\"code\":\"CDS-10001\",\"message\":\"Bad request\","
            + "\"description\":\"The sync is not valid.\"}";
    private static final String CDS_NOT_ENABLED = "{\"code\":\"CDS-16001\",\"message\":\"Not enabled\","
            + "\"description\":\"CDS is not enabled.\"}";

    private HttpServer fakeCds;
    private ExecutorService fakeCdsThreads;
    // The answers of the fake CDS, in order: an HTTP status, or STALL. 204 when none is left.
    private final Deque<String> answers = new ArrayDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private String cdsUrl;
    private MockedStatic<Utils> utils;
    private MockedStatic<IdentityUtil> identityUtil;
    private MockedStatic<OrganizationPathResolver> pathResolver;

    @BeforeMethod
    public void setUp() throws IOException {

        answers.clear();
        requests.set(0);
        fakeCds = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeCdsThreads = Executors.newCachedThreadPool();
        fakeCds.setExecutor(fakeCdsThreads);
        fakeCds.createContext("/", this::answer);
        fakeCds.start();
        cdsUrl = "http://127.0.0.1:" + fakeCds.getAddress().getPort();

        utils = mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS);
        utils.when(Utils::getCDSServiceURL).thenAnswer(invocation -> cdsUrl);
        utils.when(Utils::getBase64EncodedCredentials).thenReturn("dXNlcjpwYXNz");
        identityUtil = mockStatic(IdentityUtil.class);
        identityUtil.when(() -> IdentityUtil.getProperty(anyString())).thenAnswer(invocation ->
                CDSHttpClient.SOCKET_TIMEOUT_PROPERTY.equals(invocation.getArgument(0)) ? READ_TIMEOUT_MILLIS : null);
        pathResolver = mockStatic(OrganizationPathResolver.class);
        pathResolver.when(() -> OrganizationPathResolver.pathOf(TENANT)).thenReturn("/t/" + TENANT);
        CDSHttpClient.reset();
    }

    @AfterMethod
    public void tearDown() {

        CDSHttpClient.reset();
        pathResolver.close();
        identityUtil.close();
        utils.close();
        fakeCds.stop(0);
        fakeCdsThreads.shutdownNow();
    }

    @DataProvider
    public Object[][] retriedUserEvents() {

        return new Object[][]{
                {POST_ADD_USER},
                {POST_DELETE_USER_WITH_ID},
                {POST_SET_USER_CLAIM_VALUE_WITH_ID},
                {POST_SET_USER_CLAIM_VALUES_WITH_ID}
        };
    }

    @Test(dataProvider = "retriedUserEvents")
    public void testAUserSyncIsSentAgainAfterA5xxWithoutAWarn(String event) {

        answers.add("503");

        String output = captureOutput(() -> CDSClient.triggerIdentityDataSync(event, userPayload(), TENANT));

        assertEquals(requests.get(), 2, "The sync of " + event + " was not sent again.");
        assertFalse(output.contains(FINAL_WARN), "A WARN was logged although the retry succeeded: " + output);
    }

    // POST_ADD_USER without a profile cookie goes through the profile sync.
    @Test
    public void testTheProfileSyncOfANewUserIsSentAgainAfterA5xx() {

        answers.add("502");

        CDSClient.triggerProfileSync(POST_ADD_USER, userPayload(), TENANT);

        assertEquals(requests.get(), 2);
    }

    @Test
    public void testAUserSyncIsSentAgainAfterAReadTimeout() {

        answers.add(STALL);

        String output = captureOutput(() ->
                CDSClient.triggerIdentityDataSync(POST_DELETE_USER_WITH_ID, userPayload(), TENANT));

        assertEquals(requests.get(), 2);
        assertFalse(output.contains(FINAL_WARN), "A WARN was logged although the retry succeeded: " + output);
    }

    @Test
    public void testOneWarnWithTheUserIdIsLoggedWhenBothAttemptsFail() {

        answers.add("503");
        answers.add(STALL);

        String output = captureOutput(() ->
                CDSClient.triggerIdentityDataSync(POST_DELETE_USER_WITH_ID, userPayload(), TENANT));

        assertEquals(requests.get(), CDSClient.USER_SYNC_MAX_ATTEMPTS);
        assertEquals(countOf(output, FINAL_WARN), 1, "Expected one WARN: " + output);
        String warn = lineWith(output, FINAL_WARN);
        assertTrue(warn.contains("WARN"), warn);
        assertTrue(warn.contains("user: " + USER_ID), warn);
        assertTrue(warn.contains("tenant: " + TENANT), warn);
        assertTrue(warn.contains("event: " + POST_DELETE_USER_WITH_ID), warn);
        assertTrue(warn.contains("after 2 attempts (status 503, code n/a; SocketTimeoutException: Read timed out)"),
                warn);
        // HttpClient's wire log, at DEBUG in the tests only, shows the body; the logs of the client must not.
        for (String line : output.split("\\R")) {
            if (line.contains("[" + CDSClient.class.getName() + "]")) {
                assertFalse(line.contains(EMAIL), "A claim value was logged: " + line);
            }
        }
    }

    @Test
    public void testAUserSyncIsSentAgainWhenCDSCannotBeReached() throws IOException {

        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            cdsUrl = "http://127.0.0.1:" + closed.getLocalPort();
        }

        String output = captureOutput(() ->
                CDSClient.triggerIdentityDataSync(POST_DELETE_USER_WITH_ID, userPayload(), TENANT));

        String warn = lineWith(output, FINAL_WARN);
        assertTrue(warn.contains("user: " + USER_ID), warn);
        assertEquals(countOf(warn, "HttpHostConnectException"), 2, "Expected two refused attempts: " + warn);
    }

    @Test
    public void testAUserSyncThatCDSRefusesIsNotSentAgain() {

        answers.add("400 " + CDS_REFUSED);

        String output = captureOutput(() ->
                CDSClient.triggerIdentityDataSync(POST_DELETE_USER_WITH_ID, userPayload(), TENANT));

        assertEquals(requests.get(), 1);
        assertEquals(countOf(output, FINAL_WARN), 1, "Expected one WARN: " + output);
        String warn = lineWith(output, FINAL_WARN);
        assertTrue(warn.contains("user: " + USER_ID), warn);
        assertTrue(warn.contains("status=400, code=CDS-10001"), warn);
    }

    @Test
    public void testAUserSyncForAnOrganizationWithoutCDSIsNotSentAgainOrWarned() {

        answers.add("400 " + CDS_NOT_ENABLED);

        String output = captureOutput(() ->
                CDSClient.triggerIdentityDataSync(POST_DELETE_USER_WITH_ID, userPayload(), TENANT));

        assertEquals(requests.get(), 1);
        assertFalse(output.contains(FINAL_WARN), "A WARN was logged for an organization without CDS: " + output);
    }

    @DataProvider
    public Object[][] signInEvents() {

        return new Object[][]{{AUTHENTICATION_SUCCESS}, {SESSION_TERMINATE}};
    }

    // CDS gets the sign-in events again at the next sign-in, so they are not retried and add no wait to a sign-in.
    @Test(dataProvider = "signInEvents")
    public void testASignInSyncIsSentOnce(String event) {

        answers.add("503");

        CDSClient.triggerIdentityDataSync(event, userPayload(), TENANT);

        assertEquals(requests.get(), 1);
    }

    // CDS reads the whole schema again on the next schema event, and syncs the organization tree on its own.
    @Test
    public void testTheSchemaAndOrganizationSyncsAreSentOnce() {

        answers.add("503");
        CDSClient.triggerProfileSchemasync(new HashMap<>(Collections.singletonMap("event", "POST_ADD_LOCAL_CLAIM")),
                TENANT);
        assertEquals(requests.get(), 1);

        requests.set(0);
        answers.add("503");
        CDSClient.triggerOrganizationSync(new HashMap<>(Collections.singletonMap("event", "POST_ADD_ORGANIZATION")),
                TENANT);
        assertEquals(requests.get(), 1);
    }

    private Map<String, Object> userPayload() {

        Map<String, Object> payload = new HashMap<>();
        payload.put("userId", USER_ID);
        payload.put("claims", new HashMap<>(Collections.singletonMap("http://wso2.org/claims/emailaddress", EMAIL)));
        payload.put("orgHandle", TENANT);
        return payload;
    }

    private void answer(HttpExchange exchange) throws IOException {

        requests.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        String next;
        synchronized (answers) {
            next = answers.isEmpty() ? "204" : answers.poll();
        }
        if (STALL.equals(next)) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }
        String[] parts = next.split(" ", 2);
        int status = Integer.parseInt(parts[0]);
        byte[] body = parts.length > 1 ? parts[1].getBytes(StandardCharsets.UTF_8) : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /**
     * Run a call and return what it wrote to the standard output, where the logger of the tests writes, while still
     * writing it there.
     */
    private static String captureOutput(Runnable call) {

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(new TeeOutputStream(original, captured), true));
        try {
            call.run();
        } finally {
            System.out.flush();
            System.setOut(original);
        }
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int countOf(String text, String part) {

        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + part.length())) {
            count++;
        }
        return count;
    }

    private static String lineWith(String text, String part) {

        for (String line : text.split("\\R")) {
            if (line.contains(part)) {
                return line;
            }
        }
        return "";
    }

    private static final class TeeOutputStream extends OutputStream {

        private final OutputStream first;
        private final OutputStream second;

        private TeeOutputStream(OutputStream first, OutputStream second) {

            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {

            first.write(b);
            second.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {

            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {

            first.flush();
            second.flush();
        }
    }
}

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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.identity.cds.client.exception.CDSServerException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Tests that the calls to CDS end when CDS accepts the connection but never responds, so a slow CDS cannot hold
 * the threads of the Identity Server, and that the timeouts and the pool size of
 * [customer_data_service.http_client] are the ones the calls use.
 */
public class CDSClientTimeoutTest {

    private static final Log LOG = LogFactory.getLog(CDSClientTimeoutTest.class);
    private static final String TENANT = "acme";
    // Longer than the socket timeouts of the client, so a call that has no timeout is seen as stuck.
    private static final long CALL_LIMIT_SECONDS = 15;

    private ServerSocket stalledCds;
    private final List<Socket> heldConnections = new CopyOnWriteArrayList<>();
    // The identity.xml properties the mocked IdentityUtil returns.
    private final Map<String, String> properties = new ConcurrentHashMap<>();
    private ExecutorService caller;

    @BeforeMethod
    public void setUp() throws IOException {

        heldConnections.clear();
        // Accepts every connection and reads nothing, as a CDS that hangs does.
        stalledCds = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!stalledCds.isClosed()) {
                try {
                    heldConnections.add(stalledCds.accept());
                } catch (IOException e) {
                    return;
                }
            }
        }, "stalled-cds");
        acceptor.setDaemon(true);
        acceptor.start();

        properties.clear();
        caller = Executors.newFixedThreadPool(2);
        CDSHttpClient.reset();
    }

    @AfterMethod
    public void tearDown() throws IOException {

        // Closing the connections also frees a call that is still stuck.
        stalledCds.close();
        for (Socket connection : heldConnections) {
            connection.close();
        }
        caller.shutdownNow();
        CDSHttpClient.reset();
    }

    @Test
    public void testConfigUpdateEndsAfterTheDefaultAdminTimeout() throws Exception {

        Call call = call(() -> {
            CDSClient.updateCDSConfig(TENANT, true);
            return null;
        });
        assertTrue(call.failure instanceof CDSServerException, "Expected a CDSServerException, got " + call.failure);
        assertEndedAfter(call, CDSHttpClient.DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS, CALL_LIMIT_SECONDS * 1000);
    }

    @Test
    public void testServiceCredentialsSyncEndsAfterTheDefaultAdminTimeout() throws Exception {

        Call call = call(() -> CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client",
                "acme-secret"));
        assertTrue(call.failure instanceof CDSServerException, "Expected a CDSServerException, got " + call.failure);
        assertEndedAfter(call, CDSHttpClient.DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS, CALL_LIMIT_SECONDS * 1000);
    }

    // The sync events log the failure and return, so the sign-in or user operation that sent them goes on. A sign-in
    // sync is sent once, so it ends after one read timeout.
    @Test
    public void testASignInSyncEndsAfterTheDefaultReadTimeout() throws Exception {

        Call call = call(this::signInSync);
        assertTrue(call.failure == null, "Expected the sign-in sync to return, got " + call.failure);
        assertEndedAfter(call, CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS,
                CDSHttpClient.DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS);
    }

    @Test
    public void testTheConfiguredAdminReadTimeoutIsUsed() throws Exception {

        properties.put(CDSHttpClient.ADMIN_SOCKET_TIMEOUT_PROPERTY, "2500");

        Call config = call(() -> {
            CDSClient.updateCDSConfig(TENANT, true);
            return null;
        });
        assertTrue(config.failure instanceof CDSServerException, "Expected a CDSServerException, got "
                + config.failure);
        assertEndedAfter(config, 2500, CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS);

        Call credentials = call(() -> CDSClient.syncServiceCredentials(TENANT, "acme-org-id", "acme-client",
                "acme-secret"));
        assertTrue(credentials.failure instanceof CDSServerException, "Expected a CDSServerException, got "
                + credentials.failure);
        assertEndedAfter(credentials, 2500, CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    @Test
    public void testTheConfiguredReadTimeoutIsUsed() throws Exception {

        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, "1500");

        Call call = call(this::signInSync);
        assertTrue(call.failure == null, "Expected the sign-in sync to return, got " + call.failure);
        assertEndedAfter(call, 1500, CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    // The sync of a user deletion is sent again once, so when CDS never responds it ends after two read timeouts.
    @Test
    public void testADeleteUserSyncEndsAfterTwoReadTimeouts() throws Exception {

        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, "1500");

        Call call = call(() -> {
            CDSClient.triggerIdentityDataSync("POST_DELETE_USER_WITH_ID",
                    new HashMap<>(Collections.singletonMap("userId", "acme-user-id")), TENANT);
            return null;
        });
        assertTrue(call.failure == null, "Expected the user sync to return, got " + call.failure);
        assertEndedAfter(call, 2 * 1500, 3 * 1500);
        assertTrue(heldConnections.size() == 2, "Expected two attempts, CDS got " + heldConnections.size()
                + " connections.");
    }

    // With a pool of one connection, a second call waits for the connection the first one holds, up to the
    // connection request timeout, and then gives up without reaching CDS.
    @Test
    public void testTheConfiguredPoolSizeAndConnectionRequestTimeoutAreUsed() throws Exception {

        properties.put(CDSHttpClient.MAX_CONNECTIONS_PROPERTY, "1");
        properties.put(CDSHttpClient.CONNECTION_REQUEST_TIMEOUT_PROPERTY, "500");
        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, "5000");

        Future<Call> first = submit(this::signInSync);
        waitForConnections(1);
        Call second = call(this::signInSync);

        assertTrue(second.failure == null, "Expected the sign-in sync to return, got " + second.failure);
        assertEndedAfter(second, 500, 2000);
        assertTrue(heldConnections.size() == 1, "The second call opened a connection to CDS: "
                + heldConnections.size() + " connections.");
        Call firstCall = first.get(CALL_LIMIT_SECONDS, TimeUnit.SECONDS);
        assertEndedAfter(firstCall, 5000, CALL_LIMIT_SECONDS * 1000);
    }

    private Object signInSync() {

        CDSClient.triggerIdentityDataSync("AUTHENTICATION_SUCCESS", new HashMap<>(), TENANT);
        return null;
    }

    // A timeout ends a call only after it has passed, and a call that ended long after did not use it.
    private static void assertEndedAfter(Call call, long atLeastMillis, long beforeMillis) {

        assertTrue(call.millis >= atLeastMillis, "The call ended after " + call.millis + " ms, before "
                + atLeastMillis + " ms.");
        assertTrue(call.millis < beforeMillis, "The call ended after " + call.millis + " ms, not before "
                + beforeMillis + " ms.");
    }

    private void waitForConnections(int count) throws InterruptedException {

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (heldConnections.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(heldConnections.size() >= count, "CDS got " + heldConnections.size() + " connections.");
    }

    private Call call(Callable<?> body) throws Exception {

        try {
            return submit(body).get(CALL_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("The call to CDS was still waiting after " + CALL_LIMIT_SECONDS + " seconds.");
            return null;
        }
    }

    /**
     * Run a call on another thread. The static mocks are opened on that thread, as Mockito applies a static mock
     * only on the thread that opened it.
     */
    private Future<Call> submit(Callable<?> body) {

        return caller.submit(() -> {
            long start = System.nanoTime();
            Throwable failure = null;
            try (MockedStatic<Utils> utils = mockStatic(Utils.class, Mockito.CALLS_REAL_METHODS);
                 MockedStatic<IdentityUtil> identityUtil = mockStatic(IdentityUtil.class);
                 MockedStatic<OrganizationPathResolver> pathResolver = mockStatic(OrganizationPathResolver.class)) {
                utils.when(Utils::getCDSServiceURL).thenReturn("http://127.0.0.1:" + stalledCds.getLocalPort());
                utils.when(Utils::getBase64EncodedCredentials).thenReturn("dXNlcjpwYXNz");
                identityUtil.when(() -> IdentityUtil.getProperty(anyString()))
                        .thenAnswer(invocation -> properties.get(invocation.<String>getArgument(0)));
                pathResolver.when(() -> OrganizationPathResolver.pathOf(TENANT)).thenReturn("/t/" + TENANT);
                body.call();
            } catch (Exception e) {
                failure = e;
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            LOG.info("[CDSClientTimeoutTest] call ended after " + millis + " ms");
            return new Call(millis, failure);
        });
    }

    private static final class Call {

        private final long millis;
        private final Throwable failure;

        private Call(long millis, Throwable failure) {

            this.millis = millis;
            this.failure = failure;
        }
    }
}

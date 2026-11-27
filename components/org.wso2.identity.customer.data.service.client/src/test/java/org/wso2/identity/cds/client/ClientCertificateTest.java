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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManagerFactory;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link ClientCertificate} and the client of {@link CDSHttpClient} that presents it, against a stub
 * CDD that asks for a client certificate and answers 401 without one, as CDD does on its sync endpoints with
 * tls.require_client_cert on.
 */
public class ClientCertificateTest {

    private static final String KEYSTORE = "client-certificate-test.p12";
    private static final String SERVER_ONLY_KEYSTORE = "client-certificate-server-only.p12";
    private static final String CONFIGURED_KEYSTORE = "client-certificate-configured.p12";
    private static final char[] PASSWORD = "testpass".toCharArray();
    private static final String CONFIGURED_PASSWORD = "configpass";
    private static final String[] TRUST_STORE_PROPERTIES = {"javax.net.ssl.trustStore",
            "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType"};

    private KeyStore keyStore;
    private X509Certificate certificate;
    private KeyStore serverOnlyKeyStore;
    private X509Certificate serverOnlyCertificate;
    private X509Certificate configuredCertificate;
    private HttpsServer cdd;
    private Properties savedProperties;
    private final Map<String, String> properties = new HashMap<>();
    // The client port of each request CDD answers, one per connection.
    private final List<Integer> clientPorts = Collections.synchronizedList(new ArrayList<>());
    private MockedStatic<IdentityUtil> identityUtil;

    @BeforeMethod
    public void setUp() throws Exception {

        keyStore = load(KEYSTORE, PASSWORD);
        certificate = (X509Certificate) keyStore.getCertificate("test");
        serverOnlyKeyStore = load(SERVER_ONLY_KEYSTORE, PASSWORD);
        serverOnlyCertificate = (X509Certificate) serverOnlyKeyStore.getCertificate("test");
        configuredCertificate = (X509Certificate) load(CONFIGURED_KEYSTORE, CONFIGURED_PASSWORD.toCharArray())
                .getCertificate("cdd-client");

        // CDD serves the test certificate, and trusts it and the configured certificate as client certificates.
        KeyStore clientTrustStore = KeyStore.getInstance("PKCS12");
        clientTrustStore.load(null, null);
        clientTrustStore.setCertificateEntry("test", certificate);
        clientTrustStore.setCertificateEntry("configured", configuredCertificate);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(clientTrustStore);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);

        cdd = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cdd.setHttpsConfigurator(new HttpsConfigurator(serverContext) {
            @Override
            public void configure(HttpsParameters params) {

                SSLParameters parameters = serverContext.getDefaultSSLParameters();
                parameters.setWantClientAuth(true);
                params.setSSLParameters(parameters);
            }
        });
        clientPorts.clear();
        cdd.createContext("/", exchange -> {
            clientPorts.add(exchange.getRemoteAddress().getPort());
            int status;
            try {
                X509Certificate peer = (X509Certificate) ((HttpsExchange) exchange).getSSLSession()
                        .getPeerCertificates()[0];
                status = peer.equals(certificate) ? 204 : peer.equals(configuredCertificate) ? 200 : 403;
            } catch (SSLPeerUnverifiedException e) {
                status = 401;
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        cdd.start();

        // The Identity Server trusts CDD through the default trust managers, as it does with its trust store.
        savedProperties = new Properties();
        for (String property : TRUST_STORE_PROPERTIES) {
            if (System.getProperty(property) != null) {
                savedProperties.setProperty(property, System.getProperty(property));
            }
        }
        System.setProperty("javax.net.ssl.trustStore", resource(KEYSTORE));
        System.setProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

        properties.clear();
        CDSHttpClient.reset();
        identityUtil = mockStatic(IdentityUtil.class);
        identityUtil.when(() -> IdentityUtil.getProperty(anyString())).thenAnswer(
                invocation -> properties.get(invocation.<String>getArgument(0)));
    }

    @AfterMethod
    public void tearDown() throws Exception {

        identityUtil.close();
        cdd.stop(0);
        for (String property : TRUST_STORE_PROPERTIES) {
            System.clearProperty(property);
        }
        savedProperties.forEach((key, value) -> System.setProperty((String) key, (String) value));
        sslContextField().set(null, null);
        CDSHttpClient.reset();
    }

    @Test
    public void presentsTheKeyPairOfThePrimaryKeystore() throws Exception {

        try (MockedStatic<KeyStoreManager> keyStoreManagers = primaryKeystore(keyStore)) {
            assertEquals(get(), 204);
        }
    }

    @Test
    public void presentsNoCertificateWhenThePrimaryKeystoreCannotBeRead() throws Exception {

        try (MockedStatic<KeyStoreManager> keyStoreManagers = mockStatic(KeyStoreManager.class)) {
            keyStoreManagers.when(() -> KeyStoreManager.getInstance(MultitenantConstants.SUPER_TENANT_ID))
                    .thenThrow(new IllegalStateException("the keystore is not ready"));

            assertEquals(get(), 401);
            assertEquals(sslContextField().get(null), null, "the keystore is read again on the next call");
        }
    }

    // The client of the calls to CDD is shared: one created while the keystore could not be read is replaced by one
    // that presents the certificate, once the keystore can be read.
    @Test
    public void presentsTheKeyPairOnceThePrimaryKeystoreCanBeRead() throws Exception {

        Object clientWithoutCertificate;
        try (MockedStatic<KeyStoreManager> keyStoreManagers = mockStatic(KeyStoreManager.class)) {
            keyStoreManagers.when(() -> KeyStoreManager.getInstance(MultitenantConstants.SUPER_TENANT_ID))
                    .thenThrow(new IllegalStateException("the keystore is not ready"));
            assertEquals(get(), 401);
            clientWithoutCertificate = CDSHttpClient.get();
        }
        try (MockedStatic<KeyStoreManager> keyStoreManagers = primaryKeystore(keyStore)) {
            assertEquals(get(), 204);
            assertNotSame(CDSHttpClient.get(), clientWithoutCertificate);
            Object clientWithCertificate = CDSHttpClient.get();
            assertEquals(get(), 204);
            assertSame(CDSHttpClient.get(), clientWithCertificate, "the client is created again only once");
        }
    }

    // The calls share the connections of the pool, also when they present the client certificate.
    @Test
    public void reusesTheConnectionThatPresentedTheKeyPair() throws Exception {

        try (MockedStatic<KeyStoreManager> keyStoreManagers = primaryKeystore(keyStore)) {
            for (int i = 0; i < 3; i++) {
                assertEquals(get(), 204);
            }
        }
        assertEquals(new HashSet<>(clientPorts).size(), 1, "one connection for the three calls: " + clientPorts);
    }

    // Only getting the client reads a key pair that did not load again, so a call to CDD, which also gets a request
    // config, reads it, and logs that it cannot, once.
    @Test
    public void readsAKeyPairThatDidNotLoadOncePerCall() throws Exception {

        try (MockedStatic<KeyStoreManager> keyStoreManagers = mockStatic(KeyStoreManager.class)) {
            keyStoreManagers.when(() -> KeyStoreManager.getInstance(MultitenantConstants.SUPER_TENANT_ID))
                    .thenThrow(new IllegalStateException("the keystore is not ready"));

            for (int call = 0; call < 2; call++) {
                CDSHttpClient.get();
                CDSHttpClient.adminRequestConfig();
                CDSHttpClient.requestConfig();
                CDSHttpClient.maxConnections();
            }
            keyStoreManagers.verify(() -> KeyStoreManager.getInstance(MultitenantConstants.SUPER_TENANT_ID),
                    times(2));
        }
    }

    // A server certificate a public CA issued is for TLS server authentication only, and CDD would end the handshake
    // on it: it is held back, so CDD answers with the reason, and the decision is kept rather than logged per call.
    @Test
    public void holdsBackAPrimaryCertificateForServersOnly() throws Exception {

        try (MockedStatic<KeyStoreManager> keyStoreManagers = primaryKeystore(serverOnlyKeyStore)) {
            assertEquals(get(), 401);
            assertNotNull(sslContextField().get(null));
        }
    }

    @Test
    public void presentsTheConfiguredKeyPairOverThePrimaryOne() throws Exception {

        configure("cdd-client");
        try (MockedStatic<KeyStoreManager> keyStoreManagers = primaryKeystore(keyStore)) {
            assertEquals(get(), 200);
        }
    }

    @Test
    public void presentsNoCertificateWhenTheConfiguredKeyPairIsNotThere() throws Exception {

        configure("another-alias");
        try (MockedStatic<KeyStoreManager> keyStoreManagers = primaryKeystore(keyStore)) {
            assertEquals(get(), 401, "a key pair that is set but missing never falls back to the primary one");
        }
    }

    @Test
    public void allowsClientAuth() throws Exception {

        assertTrue(ClientCertificate.allowsClientAuth(certificate), "no extended key usage");
        assertTrue(ClientCertificate.allowsClientAuth(configuredCertificate), "clientAuth");
        assertFalse(ClientCertificate.allowsClientAuth(serverOnlyCertificate), "serverAuth only");
    }

    private void configure(String alias) throws Exception {

        properties.put("CustomerDataService.ClientCertificate.Location", resource(CONFIGURED_KEYSTORE));
        properties.put("CustomerDataService.ClientCertificate.Type", "PKCS12");
        properties.put("CustomerDataService.ClientCertificate.Password", CONFIGURED_PASSWORD);
        properties.put("CustomerDataService.ClientCertificate.KeyAlias", alias);
    }

    private static MockedStatic<KeyStoreManager> primaryKeystore(KeyStore primary) throws Exception {

        KeyStoreManager keyStoreManager = mock(KeyStoreManager.class);
        when(keyStoreManager.getDefaultPrivateKey()).thenReturn((PrivateKey) primary.getKey("test", PASSWORD));
        when(keyStoreManager.getDefaultPrimaryCertificate()).thenReturn(
                (X509Certificate) primary.getCertificate("test"));
        when(keyStoreManager.getPrimaryKeyStore()).thenReturn(primary);

        MockedStatic<KeyStoreManager> keyStoreManagers = mockStatic(KeyStoreManager.class);
        keyStoreManagers.when(() -> KeyStoreManager.getInstance(MultitenantConstants.SUPER_TENANT_ID))
                .thenReturn(keyStoreManager);
        return keyStoreManagers;
    }

    private static KeyStore load(String name, char[] password) throws Exception {

        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = ClientCertificateTest.class.getClassLoader().getResourceAsStream(name)) {
            store.load(in, password);
        }
        return store;
    }

    private static String resource(String name) throws Exception {

        return Paths.get(ClientCertificateTest.class.getClassLoader().getResource(name).toURI()).toString();
    }

    private static Field sslContextField() throws Exception {

        Field sslContext = ClientCertificate.class.getDeclaredField("sslContext");
        sslContext.setAccessible(true);
        return sslContext;
    }

    // The shared client of the calls to CDD, which the calls do not close.
    private int get() throws Exception {

        try (CloseableHttpResponse response = CDSHttpClient.get().execute(
                new HttpGet("https://localhost:" + cdd.getAddress().getPort() + "/"))) {
            return response.getStatusLine().getStatusCode();
        }
    }
}

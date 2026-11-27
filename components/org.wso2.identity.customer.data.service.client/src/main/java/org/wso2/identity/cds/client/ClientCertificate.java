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

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ssl.SSLContexts;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.core.util.KeyStoreManager;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.SSLContext;

/**
 * The client certificate the Identity Server presents to CDD, which CDD requires on the sync endpoints when
 * tls.require_client_cert is on: the key pair of [customer_data_service.client_certificate] when it is set, the key
 * pair of the primary keystore otherwise. The server certificate of CDD is verified as before, against the trust
 * store of the Identity Server.
 */
final class ClientCertificate {

    private static final Log log = LogFactory.getLog(ClientCertificate.class);
    private static final String ALIAS = "cdd-client";
    private static final char[] IN_MEMORY_PASSWORD = new char[0];
    private static final String CONFIG = "CustomerDataService.ClientCertificate.";
    private static final String LOCATION = CONFIG + "Location";
    private static final String TYPE = CONFIG + "Type";
    private static final String PASSWORD = CONFIG + "Password";
    private static final String KEY_ALIAS = CONFIG + "KeyAlias";
    private static final String KEY_PASSWORD = CONFIG + "KeyPassword";
    private static final String DEFAULT_TYPE = "PKCS12";
    private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    private static final String ANY_EXTENDED_KEY_USAGE = "2.5.29.37.0";

    private static volatile SSLContext sslContext;

    private ClientCertificate() {
    }

    /**
     * The SSL context of the HTTP client of the calls to CDD, which presents the client certificate. Only a context
     * that loaded is kept, so a keystore that was not ready yet is read again on the next call.
     *
     * @return The SSL context, or null when the key pair cannot be loaded: the client then presents no client
     * certificate, and a CDD that does not require one still accepts the calls.
     */
    static SSLContext getSSLContext() {

        if (sslContext == null) {
            synchronized (ClientCertificate.class) {
                if (sslContext == null) {
                    sslContext = buildSSLContext();
                }
            }
        }
        return sslContext;
    }

    private static SSLContext buildSSLContext() {

        boolean configured = StringUtils.isNotBlank(IdentityUtil.getProperty(LOCATION));
        String source = configured ? "[customer_data_service.client_certificate]" : "the primary keystore";
        try {
            KeyPair keyPair = configured ? configuredKeyPair() : primaryKeyPair();

            // A certificate CDD would end the handshake on is held back: CDD then answers with the reason, and one
            // that does not require a client certificate takes the calls. Public CAs now issue server certificates
            // for TLS server authentication only, so a primary keystore holding one is a common case.
            if (!allowsClientAuth(keyPair.certificate())) {
                String message = "The certificate of " + source + " does not allow TLS client authentication, so " +
                        "the calls to CDD present no client certificate, which a CDD with tls.require_client_cert " +
                        "on refuses. Set [customer_data_service.client_certificate] to a key pair with the " +
                        "extended key usage clientAuth.";
                if (configured) {
                    log.error(message);
                } else {
                    log.warn(message);
                }
                return SSLContexts.createDefault();
            }

            // A keystore of the one key pair, so the handshake cannot pick another entry of the keystore.
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setKeyEntry(ALIAS, keyPair.privateKey, IN_MEMORY_PASSWORD, keyPair.chain);

            // No trust material: the default trust managers, which read the trust store of the Identity Server.
            return SSLContexts.custom().loadKeyMaterial(keyStore, IN_MEMORY_PASSWORD).build();
        } catch (Exception | LinkageError e) {
            // A keystore that cannot be read leaves the calls as they were, without a client certificate.
            log.error("Could not load the key pair of " + source + ". The calls to CDD present no client " +
                    "certificate, which a CDD with tls.require_client_cert on refuses.", e);
            return null;
        }
    }

    private static KeyPair primaryKeyPair() throws Exception {

        KeyStoreManager keyStoreManager = KeyStoreManager.getInstance(MultitenantConstants.SUPER_TENANT_ID);
        PrivateKey privateKey = keyStoreManager.getDefaultPrivateKey();
        X509Certificate certificate = keyStoreManager.getDefaultPrimaryCertificate();
        return new KeyPair(privateKey, certificateChainOf(keyStoreManager.getPrimaryKeyStore(), certificate));
    }

    private static KeyPair configuredKeyPair() throws Exception {

        String location = IdentityUtil.getProperty(LOCATION).trim();
        String type = StringUtils.defaultIfBlank(IdentityUtil.getProperty(TYPE), DEFAULT_TYPE).trim();
        String alias = StringUtils.trimToNull(IdentityUtil.getProperty(KEY_ALIAS));
        char[] password = secret(PASSWORD);
        char[] keyPassword = StringUtils.isBlank(IdentityUtil.getProperty(KEY_PASSWORD)) ? password :
                secret(KEY_PASSWORD);
        if (alias == null) {
            throw new IllegalStateException("[customer_data_service.client_certificate] sets no alias");
        }

        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Paths.get(location))) {
            keyStore.load(in, password);
        }
        Key key = keyStore.getKey(alias, keyPassword);
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (!(key instanceof PrivateKey) || chain == null || chain.length == 0 ||
                !(chain[0] instanceof X509Certificate)) {
            throw new IllegalStateException("No key pair under the alias " + alias + " in " + location);
        }
        return new KeyPair((PrivateKey) key, chain);
    }

    private static char[] secret(String property) {

        String value = Utils.resolveIfRequired(property, StringUtils.trimToNull(IdentityUtil.getProperty(property)));
        return value == null ? new char[0] : value.toCharArray();
    }

    private static Certificate[] certificateChainOf(KeyStore primaryKeyStore, X509Certificate certificate)
            throws Exception {

        String alias = primaryKeyStore.getCertificateAlias(certificate);
        Certificate[] chain = alias == null ? null : primaryKeyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            return new Certificate[]{certificate};
        }
        return chain;
    }

    /**
     * Whether a certificate may identify a TLS client, as CDD checks it: an extended key usage, when it has one, that
     * names TLS client authentication, and a key usage, when it has one, that allows signatures.
     */
    static boolean allowsClientAuth(X509Certificate certificate) throws Exception {

        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null && !keyUsage[0]) {
            return false;
        }
        List<String> extendedKeyUsage = certificate.getExtendedKeyUsage();
        return extendedKeyUsage == null || extendedKeyUsage.contains(CLIENT_AUTH) ||
                extendedKeyUsage.contains(ANY_EXTENDED_KEY_USAGE);
    }

    /**
     * A private key and its certificate chain, the client certificate first.
     */
    private static final class KeyPair {

        private final PrivateKey privateKey;
        private final Certificate[] chain;

        private KeyPair(PrivateKey privateKey, Certificate[] chain) {

            this.privateKey = privateKey;
            this.chain = chain;
        }

        private X509Certificate certificate() {

            return (X509Certificate) chain[0];
        }
    }
}

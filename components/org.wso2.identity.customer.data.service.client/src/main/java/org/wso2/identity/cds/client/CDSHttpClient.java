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
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.io.IOException;

import javax.net.ssl.SSLContext;

/**
 * The HTTP client of the calls to CDS. Without timeouts, a CDS that accepts a connection but does not respond holds
 * the calling thread, such as the thread of a sign-in, for as long as it does not respond. The client is shared, so
 * its connection pool also limits how many connections the Identity Server opens to CDS.
 * <p>
 * The timeouts and the size of the pool are read from [customer_data_service.http_client] of deployment.toml, when
 * the client is first used. A value that is not set, or is not a positive whole number, takes the default.
 * <p>
 * The client presents the client certificate of {@link ClientCertificate}. When its key pair cannot be loaded, the
 * client presents none, and the key pair is read again on the next call until it loads.
 */
final class CDSHttpClient {

    private static final Log log = LogFactory.getLog(CDSHttpClient.class);

    // Time to open the connection to CDS.
    static final String CONNECT_TIMEOUT_PROPERTY = "CustomerDataService.HTTPClient.ConnectionTimeout";
    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 2000;
    // Time to wait for data from CDS, between two packets of the response.
    static final String SOCKET_TIMEOUT_PROPERTY = "CustomerDataService.HTTPClient.ReadTimeout";
    static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 3000;
    // The same for the calls an admin makes and waits for, as CDS calls the Identity Server back before it
    // responds to them.
    static final String ADMIN_SOCKET_TIMEOUT_PROPERTY = "CustomerDataService.HTTPClient.AdminReadTimeout";
    static final int DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS = 10000;
    // Time to wait for a free connection of the pool, when all of them are in use.
    static final String CONNECTION_REQUEST_TIMEOUT_PROPERTY =
            "CustomerDataService.HTTPClient.ConnectionRequestTimeout";
    static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT_MILLIS = 1000;
    // All the calls go to the one CDS, so the limit of the pool and of the route is the same.
    static final String MAX_CONNECTIONS_PROPERTY = "CustomerDataService.HTTPClient.ConnectionPoolSize";
    static final int DEFAULT_MAX_CONNECTIONS = 20;

    private static volatile Settings settings;

    private CDSHttpClient() {
    }

    /**
     * Get the shared client, creating it on first use. Callers close the response, not the client.
     *
     * @return The shared HTTP client.
     */
    static CloseableHttpClient get() {

        return settings(true).client;
    }

    /**
     * Get the request config of the client: the connect, socket and connection request timeouts.
     *
     * @return The request config of the calls to CDS.
     */
    static RequestConfig requestConfig() {

        return settings(false).requestConfig;
    }

    /**
     * Get the request config of the calls an admin makes and waits for, such as enabling CDS. It differs from the
     * default of the client only in the socket timeout.
     *
     * @return The request config of the admin calls.
     */
    static RequestConfig adminRequestConfig() {

        return settings(false).adminRequestConfig;
    }

    /**
     * Get the limit of the connection pool of the client, which is also the limit of connections to CDS.
     *
     * @return The maximum number of connections to CDS.
     */
    static int maxConnections() {

        return settings(false).maxConnections;
    }

    /**
     * Close the client and forget the configuration, so the next call reads the configuration again and creates a
     * new client. For tests.
     */
    static synchronized void reset() {

        Settings current = settings;
        settings = null;
        if (current != null) {
            try {
                current.client.close();
            } catch (IOException e) {
                log.debug("Error while closing the HTTP client of the calls to CDS.", e);
            }
        }
    }

    /**
     * Get the settings, creating them on first use.
     *
     * @param loadClientCertificate Whether to read the key pair of the client certificate again when it did not load
     *                              before. Only the call that gets the client does, so a call to CDS reads it, and
     *                              logs that it cannot, once.
     * @return The settings.
     */
    private static Settings settings(boolean loadClientCertificate) {

        Settings current = settings;
        if (current != null && (current.sslContextLoaded || !loadClientCertificate)) {
            return current;
        }
        synchronized (CDSHttpClient.class) {
            current = settings;
            if (current == null) {
                current = new Settings(ClientCertificate.getSSLContext());
                settings = current;
            } else if (!current.sslContextLoaded && loadClientCertificate) {
                SSLContext sslContext = ClientCertificate.getSSLContext();
                if (sslContext != null) {
                    // The previous client is not closed, as calls in progress may still use it. Its connections
                    // are closed when it is garbage collected.
                    current = new Settings(current, sslContext);
                    settings = current;
                }
            }
            return current;
        }
    }

    /**
     * Read a positive whole number from identity.xml.
     *
     * @param property     Name of the property.
     * @param defaultValue Value when the property is not set, or is not a positive whole number.
     * @return The value of the property, or the default.
     */
    static int readPositiveInt(String property, int defaultValue) {

        String value = StringUtils.trimToNull(IdentityUtil.getProperty(property));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // Logged below, with the value that is not a number.
        }
        log.warn(String.format("%s is %s, which is not a positive whole number. The default, %d, is used.",
                property, Utils.sanitizeForLog(value), defaultValue));
        return defaultValue;
    }

    private static CloseableHttpClient buildClient(RequestConfig requestConfig, int maxConnections,
                                                   SSLContext sslContext) {

        // Without connection state, a connection that presented the client certificate is reused: HttpClient would
        // otherwise tag it with the principal of the certificate, which no later call asks for, and open a connection
        // per call. Every call presents the same certificate, so the connections need no state.
        HttpClientBuilder builder = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .disableConnectionState();
        if (sslContext != null) {
            builder.setSSLContext(sslContext);
        }
        return builder.build();
    }

    /**
     * The configuration and the client built from it. They are created together, so the client and the request
     * configs always come from the same values.
     */
    private static final class Settings {

        private final int maxConnections;
        private final RequestConfig requestConfig;
        private final RequestConfig adminRequestConfig;
        private final CloseableHttpClient client;
        private final boolean sslContextLoaded;

        /**
         * The same configuration, with a client that presents the client certificate, which did not load before.
         */
        private Settings(Settings previous, SSLContext sslContext) {

            maxConnections = previous.maxConnections;
            requestConfig = previous.requestConfig;
            adminRequestConfig = previous.adminRequestConfig;
            client = buildClient(requestConfig, maxConnections, sslContext);
            sslContextLoaded = true;
            if (log.isDebugEnabled()) {
                log.debug("Created the HTTP client of the calls to CDS again, as the client certificate loaded.");
            }
        }

        private Settings(SSLContext sslContext) {

            int connectTimeout = readPositiveInt(CONNECT_TIMEOUT_PROPERTY, DEFAULT_CONNECT_TIMEOUT_MILLIS);
            int socketTimeout = readPositiveInt(SOCKET_TIMEOUT_PROPERTY, DEFAULT_SOCKET_TIMEOUT_MILLIS);
            int adminSocketTimeout = readPositiveInt(ADMIN_SOCKET_TIMEOUT_PROPERTY,
                    DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS);
            int connectionRequestTimeout = readPositiveInt(CONNECTION_REQUEST_TIMEOUT_PROPERTY,
                    DEFAULT_CONNECTION_REQUEST_TIMEOUT_MILLIS);
            maxConnections = readPositiveInt(MAX_CONNECTIONS_PROPERTY, DEFAULT_MAX_CONNECTIONS);

            requestConfig = RequestConfig.custom()
                    .setConnectTimeout(connectTimeout)
                    .setSocketTimeout(socketTimeout)
                    .setConnectionRequestTimeout(connectionRequestTimeout)
                    .build();
            adminRequestConfig = RequestConfig.copy(requestConfig)
                    .setSocketTimeout(adminSocketTimeout)
                    .build();
            client = buildClient(requestConfig, maxConnections, sslContext);
            sslContextLoaded = sslContext != null;

            if (log.isDebugEnabled()) {
                log.debug(String.format("Created the HTTP client of the calls to CDS. Connection timeout: %d ms, "
                                + "read timeout: %d ms, admin read timeout: %d ms, connection request timeout: %d ms, "
                                + "connection pool size: %d, client certificate loaded: %b.", connectTimeout,
                        socketTimeout, adminSocketTimeout, connectionRequestTimeout, maxConnections,
                        sslContextLoaded));
            }
        }
    }
}

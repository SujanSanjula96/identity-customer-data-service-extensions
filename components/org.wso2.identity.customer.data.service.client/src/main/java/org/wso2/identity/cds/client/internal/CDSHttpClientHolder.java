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

package org.wso2.identity.cds.client.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.wso2.identity.cds.client.Utils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Holds the one HTTP client that the CDS client uses for all the calls to the Customer Data Service.
 * The client has connect, read and connection request timeouts, so a slow Customer Data Service cannot block
 * the Identity Server threads that call it. The client uses the JVM default trust store and hostname verification.
 */
public final class CDSHttpClientHolder {

    private static final Log LOG = LogFactory.getLog(CDSHttpClientHolder.class);
    private static final long IDLE_CONNECTION_TIMEOUT_SECONDS = 30;

    private static volatile CloseableHttpClient httpClient;

    private CDSHttpClientHolder() {

    }

    /**
     * Get the shared HTTP client. The client is created on the first call, with the timeouts in identity.xml.
     *
     * @return The shared HTTP client.
     */
    public static CloseableHttpClient getHttpClient() {

        CloseableHttpClient client = httpClient;
        if (client == null) {
            synchronized (CDSHttpClientHolder.class) {
                client = httpClient;
                if (client == null) {
                    client = createHttpClient();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Close the shared HTTP client and its connections. A later call to {@link #getHttpClient()} creates a new
     * client.
     */
    public static void close() {

        CloseableHttpClient client;
        synchronized (CDSHttpClientHolder.class) {
            client = httpClient;
            httpClient = null;
        }
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            LOG.warn("Error while closing the HTTP client of the CDS client.", e);
        }
    }

    private static CloseableHttpClient createHttpClient() {

        int connectTimeout = Utils.getConnectTimeout();
        int readTimeout = Utils.getReadTimeout();
        int connectionRequestTimeout = Utils.getConnectionRequestTimeout();
        int maxConnections = Utils.getMaxConnections();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(readTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout)
                .build();

        // All the calls go to one Customer Data Service, so one route can use all the connections.
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnections);

        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Created the CDS HTTP client. connectTimeout=%dms, readTimeout=%dms, " +
                            "connectionRequestTimeout=%dms, maxConnections=%d", connectTimeout, readTimeout,
                    connectionRequestTimeout, maxConnections));
        }
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(IDLE_CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }
}

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

import org.apache.http.client.config.RequestConfig;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * Unit tests for {@link CDSHttpClient}.
 */
public class CDSHttpClientTest {

    // The identity.xml properties the mocked IdentityUtil returns.
    private final Map<String, String> properties = new HashMap<>();
    private MockedStatic<IdentityUtil> identityUtil;

    @BeforeMethod
    public void setUp() {

        properties.clear();
        identityUtil = mockStatic(IdentityUtil.class);
        identityUtil.when(() -> IdentityUtil.getProperty(anyString()))
                .thenAnswer(invocation -> properties.get(invocation.<String>getArgument(0)));
        CDSHttpClient.reset();
    }

    @AfterMethod
    public void tearDown() {

        CDSHttpClient.reset();
        identityUtil.close();
    }

    @Test
    public void testTheClientIsShared() {

        assertNotNull(CDSHttpClient.get());
        assertSame(CDSHttpClient.get(), CDSHttpClient.get());
    }

    // A value of 0 or less means no timeout to HttpClient, so each one must be set without a configuration.
    @Test
    public void testTheDefaultsAreUsedWhenNothingIsConfigured() {

        RequestConfig config = CDSHttpClient.requestConfig();
        assertEquals(config.getConnectTimeout(), CDSHttpClient.DEFAULT_CONNECT_TIMEOUT_MILLIS);
        assertEquals(config.getSocketTimeout(), CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS);
        assertEquals(config.getConnectionRequestTimeout(), CDSHttpClient.DEFAULT_CONNECTION_REQUEST_TIMEOUT_MILLIS);
        assertEquals(CDSHttpClient.adminRequestConfig().getSocketTimeout(),
                CDSHttpClient.DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS);
        assertEquals(CDSHttpClient.maxConnections(), CDSHttpClient.DEFAULT_MAX_CONNECTIONS);
    }

    @Test
    public void testTheConfiguredValuesAreUsed() {

        properties.put(CDSHttpClient.CONNECT_TIMEOUT_PROPERTY, "1500");
        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, "4000");
        properties.put(CDSHttpClient.ADMIN_SOCKET_TIMEOUT_PROPERTY, "15000");
        properties.put(CDSHttpClient.CONNECTION_REQUEST_TIMEOUT_PROPERTY, "500");
        properties.put(CDSHttpClient.MAX_CONNECTIONS_PROPERTY, "50");

        RequestConfig config = CDSHttpClient.requestConfig();
        assertEquals(config.getConnectTimeout(), 1500);
        assertEquals(config.getSocketTimeout(), 4000);
        assertEquals(config.getConnectionRequestTimeout(), 500);
        assertEquals(CDSHttpClient.adminRequestConfig().getSocketTimeout(), 15000);
        assertEquals(CDSHttpClient.maxConnections(), 50);
    }

    @Test
    public void testTheAdminCallsDifferOnlyInTheReadTimeout() {

        properties.put(CDSHttpClient.CONNECT_TIMEOUT_PROPERTY, "1500");
        properties.put(CDSHttpClient.CONNECTION_REQUEST_TIMEOUT_PROPERTY, "500");
        properties.put(CDSHttpClient.ADMIN_SOCKET_TIMEOUT_PROPERTY, "12000");

        RequestConfig admin = CDSHttpClient.adminRequestConfig();
        assertEquals(admin.getSocketTimeout(), 12000);
        assertEquals(admin.getConnectTimeout(), 1500);
        assertEquals(admin.getConnectionRequestTimeout(), 500);
        assertEquals(CDSHttpClient.requestConfig().getSocketTimeout(), CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS);
    }

    @DataProvider
    public Object[][] valuesThatAreNotPositiveWholeNumbers() {

        return new Object[][]{
                {"0"},            // No timeout to HttpClient.
                {"-1"},
                {"abc"},
                {"2.5"},
                {"3000ms"},
                {"2147483648"},   // Larger than an int.
                {""},
                {"   "}
        };
    }

    @Test(dataProvider = "valuesThatAreNotPositiveWholeNumbers")
    public void testAValueThatIsNotAPositiveWholeNumberTakesTheDefault(String value) {

        properties.put(CDSHttpClient.CONNECT_TIMEOUT_PROPERTY, value);
        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, value);
        properties.put(CDSHttpClient.ADMIN_SOCKET_TIMEOUT_PROPERTY, value);
        properties.put(CDSHttpClient.CONNECTION_REQUEST_TIMEOUT_PROPERTY, value);
        properties.put(CDSHttpClient.MAX_CONNECTIONS_PROPERTY, value);

        RequestConfig config = CDSHttpClient.requestConfig();
        assertEquals(config.getConnectTimeout(), CDSHttpClient.DEFAULT_CONNECT_TIMEOUT_MILLIS);
        assertEquals(config.getSocketTimeout(), CDSHttpClient.DEFAULT_SOCKET_TIMEOUT_MILLIS);
        assertEquals(config.getConnectionRequestTimeout(), CDSHttpClient.DEFAULT_CONNECTION_REQUEST_TIMEOUT_MILLIS);
        assertEquals(CDSHttpClient.adminRequestConfig().getSocketTimeout(),
                CDSHttpClient.DEFAULT_ADMIN_SOCKET_TIMEOUT_MILLIS);
        assertEquals(CDSHttpClient.maxConnections(), CDSHttpClient.DEFAULT_MAX_CONNECTIONS);
    }

    @Test
    public void testSpacesAroundAValueAreIgnored() {

        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, " 2500 ");

        assertEquals(CDSHttpClient.requestConfig().getSocketTimeout(), 2500);
    }

    // The client and its configuration are created once. A change to identity.xml needs a restart, as for the
    // other settings of the Identity Server.
    @Test
    public void testTheConfigurationIsReadWhenTheClientIsCreated() {

        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, "4000");
        Object client = CDSHttpClient.get();
        properties.put(CDSHttpClient.SOCKET_TIMEOUT_PROPERTY, "5000");
        assertEquals(CDSHttpClient.requestConfig().getSocketTimeout(), 4000);
        assertSame(CDSHttpClient.get(), client);

        CDSHttpClient.reset();
        assertEquals(CDSHttpClient.requestConfig().getSocketTimeout(), 5000);
        assertNotSame(CDSHttpClient.get(), client);
        assertTrue(CDSHttpClient.adminRequestConfig().getSocketTimeout() > 0);
    }
}

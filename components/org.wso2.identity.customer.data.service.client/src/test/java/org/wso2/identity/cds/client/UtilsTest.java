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

import org.mockito.MockedStatic;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityUtil;

import static org.mockito.Mockito.mockStatic;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 * Unit tests for the CDS URL check of {@link Utils}.
 */
public class UtilsTest {

    @DataProvider
    public Object[][] serviceURLs() {

        return new Object[][]{
                {"https://cds.example.com", true},
                {"https://cds.example.com:8900", true},
                {"HTTPS://cds.example.com", true},
                {"https://localhost:8900", true},
                // HTTP is accepted only for a loopback host, for local development.
                {"http://localhost:8900", true},
                {"http://LOCALHOST", true},
                {"http://127.0.0.1:8900", true},
                {"http://127.10.0.1", true},
                {"http://[::1]:8900", true},
                {"http://cds.example.com", false},
                {"http://cds:8900", false},
                {"http://10.0.0.7:8900", false},
                {"http://[2001:db8::1]:8900", false},
                {"http://localhost.example.com", false},
                {"http://127.0.0.1.example.com", false},
                {"ftp://localhost", false},
                {"cds.example.com:8900", false},
                {"https://", false},
                {"https://cds example.com", false},
        };
    }

    @Test(dataProvider = "serviceURLs")
    public void testIsAllowedServiceURL(String serviceURL, boolean allowed) {

        assertEquals(Utils.isAllowedServiceURL(serviceURL), allowed, serviceURL);
    }

    @Test
    public void testGetCDSServiceURLReturnsAnHttpsURL() {

        try (MockedStatic<IdentityUtil> identityUtil = mockStatic(IdentityUtil.class)) {
            identityUtil.when(() -> IdentityUtil.getProperty("CustomerDataService.ServerURL"))
                    .thenReturn(" https://cds.example.com:8900 ");
            assertEquals(Utils.getCDSServiceURL(), "https://cds.example.com:8900");
        }
    }

    @Test
    public void testGetCDSServiceURLRefusesHttpToAnotherHost() {

        try (MockedStatic<IdentityUtil> identityUtil = mockStatic(IdentityUtil.class)) {
            identityUtil.when(() -> IdentityUtil.getProperty("CustomerDataService.ServerURL"))
                    .thenReturn("http://cds.example.com:8900");
            assertNull(Utils.getCDSServiceURL());
        }
    }

    @Test
    public void testGetCDSServiceURLWithoutConfiguration() {

        try (MockedStatic<IdentityUtil> identityUtil = mockStatic(IdentityUtil.class)) {
            identityUtil.when(() -> IdentityUtil.getProperty("CustomerDataService.ServerURL")).thenReturn("  ");
            assertNull(Utils.getCDSServiceURL());
        }
    }
}

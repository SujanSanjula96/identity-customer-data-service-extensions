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

package org.wso2.identity.cds.event.handler;

import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.context.IdentityContext;
import org.wso2.carbon.identity.core.context.model.Flow;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.event.handler.internal.EventHandlerDataHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

public class IdentityEventHandlerTest {

    private static final String TENANT_DOMAIN = "carbon.super";
    private static final String USER_ID = "8a6e3c1f-6c1b-4d5e-9a3e-0e5c2b7d9f10";
    private static final String CONTEXT_TRACKER = "c0ntext-tracker";
    private static final String CLAIM_TRACKER = "cl4im-tracker";

    private IdentityEventHandler handler;
    private CDSConfigService cdsConfigService;
    private MockedStatic<CDSClient> cdsClient;
    private MockedStatic<IdentityContext> identityContextStatic;
    private IdentityContext identityContext;

    @BeforeClass
    public void setUpCarbonHome() throws Exception {

        // CarbonContext, the parent of IdentityContext, reads the carbon home when the class loads.
        Path carbonHome = Paths.get("target", "carbon-home").toAbsolutePath();
        Files.createDirectories(carbonHome.resolve(Paths.get("repository", "conf")));
        System.setProperty("carbon.home", carbonHome.toString());
    }

    @BeforeMethod
    public void setUp() throws Exception {

        handler = new IdentityEventHandler();
        cdsConfigService = mock(CDSConfigService.class);
        when(cdsConfigService.isCDSEnabled(TENANT_DOMAIN)).thenReturn(true);
        EventHandlerDataHolder.setCDSConfigService(cdsConfigService);
        cdsClient = mockStatic(CDSClient.class);
        identityContext = mock(IdentityContext.class);
        identityContextStatic = mockStatic(IdentityContext.class);
        identityContextStatic.when(IdentityContext::getThreadLocalIdentityContext).thenReturn(identityContext);
    }

    @AfterMethod
    public void tearDown() {

        identityContextStatic.close();
        cdsClient.close();
        EventHandlerDataHolder.setCDSConfigService(null);
    }

    @Test
    public void testTrackerFromTheIdentityContextFlow() throws Exception {

        enterRegisterFlow(CONTEXT_TRACKER);

        handler.handleEvent(postAddUserEvent(CLAIM_TRACKER));

        assertEquals(capturedIdentityDataSyncPayload().get(Constants.PROFILE_COOKIE), CONTEXT_TRACKER);
    }

    @Test
    public void testTrackerFromTheClaimWhenTheFlowHasNoTracker() throws Exception {

        enterRegisterFlow(null);

        handler.handleEvent(postAddUserEvent(CLAIM_TRACKER));

        assertEquals(capturedIdentityDataSyncPayload().get(Constants.PROFILE_COOKIE), CLAIM_TRACKER);
    }

    @Test
    public void testTrackerFromTheClaimWhenThereIsNoFlow() throws Exception {

        handler.handleEvent(postAddUserEvent(CLAIM_TRACKER));

        assertEquals(capturedIdentityDataSyncPayload().get(Constants.PROFILE_COOKIE), CLAIM_TRACKER);
    }

    @Test
    public void testProfileSyncWhenThereIsNoTracker() throws Exception {

        enterRegisterFlow(null);

        handler.handleEvent(postAddUserEvent(null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        cdsClient.verify(() -> CDSClient.triggerProfileSync(eq("POST_ADD_USER"), payload.capture(),
                eq(TENANT_DOMAIN)));
        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(anyString(), anyMap(), anyString()), never());
        assertEquals(payload.getValue().get(Constants.PROFILE_COOKIE), null);
    }

    private void enterRegisterFlow(String anonymousProfileTracker) {

        when(identityContext.getCurrentFlow()).thenReturn(new Flow.Builder()
                .name(Flow.Name.REGISTER)
                .initiatingPersona(Flow.InitiatingPersona.USER)
                .anonymousProfileTracker(anonymousProfileTracker)
                .build());
    }

    private Event postAddUserEvent(String trackerClaimValue) {

        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.USER_ID_CLAIM, USER_ID);
        claims.put("http://wso2.org/claims/emailaddress", "alex@example.com");
        if (trackerClaimValue != null) {
            claims.put(Constants.PROFILE_COOKIE_CLAIM, trackerClaimValue);
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(Constants.TENANT_DOMAIN, TENANT_DOMAIN);
        properties.put(Constants.USER_CLAIMS_PROPERTY, claims);
        return new Event("POST_ADD_USER", properties);
    }

    private Map<String, Object> capturedIdentityDataSyncPayload() {

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        cdsClient.verify(() -> CDSClient.triggerIdentityDataSync(eq("POST_ADD_USER"), payload.capture(),
                eq(TENANT_DOMAIN)));
        return payload.getValue();
    }
}

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
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.event.event.Event;
import org.wso2.carbon.identity.organization.management.service.model.Organization;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.client.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class OrganizationEventHandlerTest {

    private static final String ORG_ID = "4f088c20-150a-424c-9e01-abe2450b78ae";
    private static final String TENANT_DOMAIN = "carbon.super";

    private ExecutorService executor;
    private OrganizationEventHandler handler;
    private MockedStatic<Utils> utils;
    private MockedStatic<IdentityTenantUtil> identityTenantUtil;
    private MockedStatic<CDSClient> cdsClient;

    @BeforeMethod
    public void setUp() {

        executor = mock(ExecutorService.class);
        handler = new OrganizationEventHandler(executor);
        utils = mockStatic(Utils.class);
        utils.when(Utils::isCDSEnabled).thenReturn(true);
        utils.when(() -> Utils.sanitizeForLog(any())).thenReturn("");
        identityTenantUtil = mockStatic(IdentityTenantUtil.class);
        identityTenantUtil.when(IdentityTenantUtil::getTenantDomainFromContext).thenReturn(TENANT_DOMAIN);
        cdsClient = mockStatic(CDSClient.class);
    }

    @AfterMethod
    public void tearDown() {

        cdsClient.close();
        identityTenantUtil.close();
        utils.close();
    }

    @DataProvider(name = "eventNames")
    public Object[][] eventNames() {

        return new Object[][]{
                {"POST_ADD_ORGANIZATION", OrganizationEventHandler.ORG_CREATED},
                {"POST_UPDATE_ORGANIZATION", OrganizationEventHandler.ORG_UPDATED},
                {"POST_PATCH_ORGANIZATION", OrganizationEventHandler.ORG_UPDATED},
                {"POST_DELETE_ORGANIZATION", OrganizationEventHandler.ORG_DELETED},
                {"PRE_ADD_ORGANIZATION", null},
                {"POST_ADD_USER", null}
        };
    }

    @Test(dataProvider = "eventNames")
    public void testToCdsEvent(String eventName, String expected) {

        assertEquals(OrganizationEventHandler.toCdsEvent(eventName), expected);
    }

    @Test
    public void testOrganizationIdFromTheIdProperty() {

        Map<String, Object> properties = new HashMap<>();
        properties.put("ORGANIZATION_ID", ORG_ID);

        assertEquals(OrganizationEventHandler.resolveOrganizationId(properties), ORG_ID);
    }

    @Test
    public void testOrganizationIdFromTheOrganization() {

        Organization organization = new Organization();
        organization.setId(ORG_ID);
        Map<String, Object> properties = new HashMap<>();
        properties.put("ORGANIZATION", organization);

        assertEquals(OrganizationEventHandler.resolveOrganizationId(properties), ORG_ID);
    }

    @Test
    public void testNoOrganizationId() {

        Map<String, Object> properties = new HashMap<>();
        properties.put("ORGANIZATION_ID", " ");
        properties.put("ORGANIZATION", "not an organization");

        assertNull(OrganizationEventHandler.resolveOrganizationId(properties));
        assertNull(OrganizationEventHandler.resolveOrganizationId(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testHandleEventSendsTheOrganizationToCds() throws Exception {

        Map<String, Object> properties = new HashMap<>();
        properties.put("ORGANIZATION_ID", ORG_ID);

        handler.handleEvent(new Event("POST_DELETE_ORGANIZATION", properties));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(task.capture());
        task.getValue().run();
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        cdsClient.verify(() -> CDSClient.triggerOrganizationSync(payload.capture(), eq(TENANT_DOMAIN)));
        assertEquals(payload.getValue().get(Constants.EVENT), OrganizationEventHandler.ORG_DELETED);
        assertEquals(payload.getValue().get(OrganizationEventHandler.ORG_ID), ORG_ID);
    }

    @Test
    public void testHandleEventWhenCdsIsDisabled() throws Exception {

        utils.when(Utils::isCDSEnabled).thenReturn(false);
        Map<String, Object> properties = new HashMap<>();
        properties.put("ORGANIZATION_ID", ORG_ID);

        handler.handleEvent(new Event("POST_ADD_ORGANIZATION", properties));

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    public void testHandleEventWithoutOrganizationId() throws Exception {

        handler.handleEvent(new Event("POST_ADD_ORGANIZATION", new HashMap<>()));

        verify(executor, never()).execute(any(Runnable.class));
        cdsClient.verify(() -> CDSClient.triggerOrganizationSync(anyMap(), anyString()), never());
    }

    @Test
    public void testHandleEventIgnoresOtherEvents() throws Exception {

        Map<String, Object> properties = new HashMap<>();
        properties.put("ORGANIZATION_ID", ORG_ID);

        handler.handleEvent(new Event("PRE_ADD_ORGANIZATION", properties));

        verify(executor, never()).execute(any(Runnable.class));
    }
}

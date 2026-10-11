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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementServerException;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;
import org.wso2.identity.cds.client.internal.CDSClientDataHolder;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

public class OrganizationPathResolverTest {

    private static final String ROOT_DOMAIN = "carbon.super";
    private static final String ROOT_ID = "10084a8d-113f-4211-a0d5-efe36b082211";
    private static final String ORG_DOMAIN = "region1";
    private static final String ORG_ID = "4f088c20-150a-424c-9e01-abe2450b78ae";

    private OrganizationManager organizationManager;
    private MockedStatic<OrganizationManagementUtil> organizationManagementUtil;

    @BeforeMethod
    public void setUp() {

        organizationManager = mock(OrganizationManager.class);
        CDSClientDataHolder.getInstance().setOrganizationManager(organizationManager);
        organizationManagementUtil = mockStatic(OrganizationManagementUtil.class);
    }

    @AfterMethod
    public void tearDown() {

        organizationManagementUtil.close();
        CDSClientDataHolder.getInstance().setOrganizationManager(null);
    }

    @Test
    public void testRootUsesTheTenantPath() throws Exception {

        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(ROOT_DOMAIN))
                .thenReturn(false);

        assertEquals(OrganizationPathResolver.pathOf(ROOT_DOMAIN), "/t/" + ROOT_DOMAIN);
        verify(organizationManager, never()).resolveOrganizationId(anyString());
    }

    @Test
    public void testSubOrganizationUsesTheOrganizationPath() throws Exception {

        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(ORG_DOMAIN))
                .thenReturn(true);
        when(organizationManager.resolveOrganizationId(ORG_DOMAIN)).thenReturn(ORG_ID);
        when(organizationManager.getPrimaryOrganizationId(ORG_ID)).thenReturn(ROOT_ID);
        when(organizationManager.resolveTenantDomain(ROOT_ID)).thenReturn(ROOT_DOMAIN);

        assertEquals(OrganizationPathResolver.pathOf(ORG_DOMAIN), "/t/" + ROOT_DOMAIN + "/o/" + ORG_ID);
    }

    @Test(expectedExceptions = OrganizationManagementServerException.class)
    public void testMissingOrganizationManager() throws Exception {

        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(ORG_DOMAIN))
                .thenReturn(true);
        CDSClientDataHolder.getInstance().setOrganizationManager(null);

        OrganizationPathResolver.pathOf(ORG_DOMAIN);
    }

    @Test(expectedExceptions = OrganizationManagementServerException.class)
    public void testUnknownOrganization() throws Exception {

        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(ORG_DOMAIN))
                .thenReturn(true);
        when(organizationManager.resolveOrganizationId(ORG_DOMAIN)).thenReturn(null);

        OrganizationPathResolver.pathOf(ORG_DOMAIN);
    }

    @Test(expectedExceptions = OrganizationManagementServerException.class)
    public void testUnknownRootOrganization() throws Exception {

        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(ORG_DOMAIN))
                .thenReturn(true);
        when(organizationManager.resolveOrganizationId(ORG_DOMAIN)).thenReturn(ORG_ID);
        when(organizationManager.getPrimaryOrganizationId(ORG_ID)).thenReturn(ROOT_ID);
        when(organizationManager.resolveTenantDomain(ROOT_ID)).thenReturn(null);

        OrganizationPathResolver.pathOf(ORG_DOMAIN);
    }

    @Test(expectedExceptions = OrganizationManagementException.class)
    public void testFailedOrganizationCheck() throws Exception {

        organizationManagementUtil.when(() -> OrganizationManagementUtil.isOrganization(ORG_DOMAIN))
                .thenThrow(new OrganizationManagementException("Tenant not found."));

        OrganizationPathResolver.pathOf(ORG_DOMAIN);
    }
}

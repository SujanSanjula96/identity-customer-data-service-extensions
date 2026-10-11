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
import org.wso2.carbon.identity.organization.management.service.OrganizationManager;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementServerException;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;
import org.wso2.identity.cds.client.internal.CDSClientDataHolder;

/**
 * Builds the CDS path of the org of a tenant domain. A root uses /t/{root_handle}. A sub org uses
 * /t/{root_handle}/o/{org_id}, because CDS takes the org of a sub org only from the path.
 */
public final class OrganizationPathResolver {

    private static final String TENANT_PATH = "/t/";
    private static final String ORGANIZATION_PATH = "/o/";

    private OrganizationPathResolver() {

    }

    /**
     * Returns the CDS path of the org of the tenant domain, without the API part.
     *
     * @param tenantDomain The tenant domain of a root or of a sub org.
     * @return /t/{root_handle} for a root, or /t/{root_handle}/o/{org_id} for a sub org.
     * @throws OrganizationManagementException If the org of the tenant domain cannot be resolved.
     */
    public static String pathOf(String tenantDomain) throws OrganizationManagementException {

        if (StringUtils.isBlank(tenantDomain) || !OrganizationManagementUtil.isOrganization(tenantDomain)) {
            return TENANT_PATH + tenantDomain;
        }
        OrganizationManager organizationManager = CDSClientDataHolder.getInstance().getOrganizationManager();
        if (organizationManager == null) {
            throw new OrganizationManagementServerException("The organization manager is not available.");
        }
        String orgId = organizationManager.resolveOrganizationId(tenantDomain);
        if (StringUtils.isBlank(orgId)) {
            throw new OrganizationManagementServerException("Could not resolve the organization of the tenant " +
                    "domain: " + tenantDomain);
        }
        String rootTenantDomain = organizationManager.resolveTenantDomain(
                organizationManager.getPrimaryOrganizationId(orgId));
        if (StringUtils.isBlank(rootTenantDomain)) {
            throw new OrganizationManagementServerException("Could not resolve the root organization of the " +
                    "organization: " + orgId);
        }
        return TENANT_PATH + rootTenantDomain + ORGANIZATION_PATH + orgId;
    }
}

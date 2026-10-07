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
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import java.lang.reflect.Method;

/**
 * Builds the CDS path of the org of a tenant domain. A root uses /t/{root_handle}. A sub org uses
 * /t/{root_handle}/o/{org_id}, because CDS takes the org of a sub org only from the path.
 * <p>
 * The organization manager is read by reflection, so that this bundle does not import the
 * organization management service.
 */
public final class OrganizationPathResolver {

    private static final Log LOG = LogFactory.getLog(OrganizationPathResolver.class);
    private static final String ORGANIZATION_MANAGER =
            "org.wso2.carbon.identity.organization.management.service.OrganizationManager";

    private OrganizationPathResolver() {

    }

    /**
     * Returns the CDS path of the org of the tenant domain, without the API part.
     *
     * @param tenantDomain The tenant domain of a root or of a sub org.
     * @return /t/{root_handle} for a root, or /t/{root_handle}/o/{org_id} for a sub org.
     */
    public static String pathOf(String tenantDomain) {

        String rootPath = "/t/" + tenantDomain;
        Bundle bundle = FrameworkUtil.getBundle(OrganizationPathResolver.class);
        BundleContext context = bundle == null ? null : bundle.getBundleContext();
        if (context == null || StringUtils.isBlank(tenantDomain)) {
            return rootPath;
        }
        ServiceReference<?> reference = context.getServiceReference(ORGANIZATION_MANAGER);
        if (reference == null) {
            return rootPath;
        }
        Object manager = context.getService(reference);
        try {
            String orgId = invoke(manager, "resolveOrganizationId", tenantDomain);
            String rootOrgId = invoke(manager, "getPrimaryOrganizationId", orgId);
            if (StringUtils.isBlank(orgId) || StringUtils.isBlank(rootOrgId) || orgId.equals(rootOrgId)) {
                return rootPath;
            }
            String rootDomain = invoke(manager, "resolveTenantDomain", rootOrgId);
            if (StringUtils.isBlank(rootDomain)) {
                return rootPath;
            }
            return "/t/" + rootDomain + "/o/" + orgId;
        } catch (ReflectiveOperationException e) {
            LOG.warn("Could not resolve the CDS path of the organization of tenant: " +
                    Utils.sanitizeForLog(tenantDomain), e);
            return rootPath;
        } finally {
            context.ungetService(reference);
        }
    }

    private static String invoke(Object manager, String methodName, String argument)
            throws ReflectiveOperationException {

        if (manager == null || argument == null) {
            return null;
        }
        Method method = manager.getClass().getMethod(methodName, String.class);
        Object value = method.invoke(manager, argument);
        return value instanceof String ? (String) value : null;
    }
}

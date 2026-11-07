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

package org.wso2.identity.cds.config.mgt.internal;

import org.apache.commons.lang.StringUtils;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.configuration.mgt.core.ConfigurationManager;
import org.wso2.carbon.identity.configuration.mgt.core.exception.ConfigurationManagementException;
import org.wso2.carbon.identity.configuration.mgt.core.model.Attribute;
import org.wso2.carbon.identity.configuration.mgt.core.model.Resource;
import org.wso2.carbon.identity.configuration.mgt.core.model.ResourceAdd;
import org.wso2.carbon.identity.configuration.mgt.core.model.ResourceTypeAdd;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.organization.management.service.exception.OrganizationManagementException;
import org.wso2.carbon.identity.organization.management.service.util.OrganizationManagementUtil;
import org.wso2.identity.cds.client.CDSClient;
import org.wso2.identity.cds.client.Utils;
import org.wso2.identity.cds.client.exception.CDSException;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.config.mgt.constant.CDSConfigMgtConstants.ErrorMessages;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtClientException;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtException;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtServerException;
import org.wso2.identity.cds.config.mgt.model.CDSConfig;

import java.util.Collections;
import java.util.Optional;

import static org.wso2.carbon.identity.configuration.mgt.core.constant.ConfigurationConstants.ErrorMessages.ERROR_CODE_RESOURCE_DOES_NOT_EXISTS;
import static org.wso2.carbon.identity.configuration.mgt.core.constant.ConfigurationConstants.ErrorMessages.ERROR_CODE_RESOURCE_TYPE_ALREADY_EXISTS;
import static org.wso2.carbon.identity.configuration.mgt.core.constant.ConfigurationConstants.ErrorMessages.ERROR_CODE_RESOURCE_TYPE_DOES_NOT_EXISTS;
import static org.wso2.identity.cds.config.mgt.constant.CDSConfigMgtConstants.ErrorMessages.ERROR_CODE_CDS_CONFIG_RETRIEVE;
import static org.wso2.identity.cds.config.mgt.constant.CDSConfigMgtConstants.ErrorMessages.ERROR_CODE_CDS_CONFIG_SYNC;
import static org.wso2.identity.cds.config.mgt.constant.CDSConfigMgtConstants.ErrorMessages.ERROR_CODE_CDS_CONFIG_UPDATE;
import static org.wso2.identity.cds.config.mgt.constant.CDSConfigMgtConstants.ErrorMessages.ERROR_CODE_CDS_NOT_ENABLED;

/**
 * Implementation of {@link CDSConfigService}. The value is in the config store: resource type CDS_CONFIGURATION,
 * resource cds-config, attribute enabled.
 */
public class CDSConfigServiceImpl implements CDSConfigService {

    private static final String CDS_CONFIG_RESOURCE_TYPE = "CDS_CONFIGURATION";
    private static final String CDS_CONFIG_RESOURCE_NAME = "cds-config";
    private static final String CDS_CONFIG_ENABLED_ATTRIBUTE = "enabled";
    private static final String CDS_TENANT_CONFIG_ENABLE_PROPERTY = "CustomerDataService.TenantConfigEnable";

    private final CDSServiceCredentialsManager serviceCredentialsManager = new CDSServiceCredentialsManager();

    @Override
    public CDSConfig getCDSConfig(String tenantDomain) throws CDSConfigMgtException {

        if (!Utils.isCDSEnabled() || StringUtils.isBlank(tenantDomain)) {
            return new CDSConfig(false);
        }
        try {
            return new CDSConfig(getTenantCDSEnabled(getRootTenantDomain(tenantDomain)).orElse(false));
        } catch (OrganizationManagementException | ConfigurationManagementException e) {
            throw serverException(ERROR_CODE_CDS_CONFIG_RETRIEVE, e, Utils.sanitizeForLog(tenantDomain));
        }
    }

    @Override
    public boolean isCDSEnabled(String tenantDomain) throws CDSConfigMgtException {

        if (!Utils.isCDSEnabled()) {
            return false;
        }
        if (!Boolean.parseBoolean(IdentityUtil.getProperty(CDS_TENANT_CONFIG_ENABLE_PROPERTY))) {
            return true;
        }
        return getCDSConfig(tenantDomain).isEnabled();
    }

    @Override
    public void updateCDSConfig(CDSConfig cdsConfig, String tenantDomain) throws CDSConfigMgtException {

        if (!Utils.isCDSEnabled()) {
            throw clientException(ERROR_CODE_CDS_NOT_ENABLED, null);
        }
        if (cdsConfig.isEnabled()) {
            // CDS needs the credentials of the tenant before it is enabled: enabling syncs the profile schema from
            // the Identity Server. They are renewed and sent again each time the tenant turns CDS on, so that turning
            // CDS off and on recovers from credentials that no longer work.
            serviceCredentialsManager.ensureServiceCredentials(tenantDomain, !isTenantCDSEnabled(tenantDomain));
        }
        // CDS gets the configuration first. If CDS does not accept it, the configuration is not stored.
        try {
            CDSClient.updateCDSConfig(tenantDomain, cdsConfig.isEnabled());
        } catch (CDSException e) {
            throw serverException(ERROR_CODE_CDS_CONFIG_SYNC, e, Utils.sanitizeForLog(tenantDomain),
                    e.getDescription());
        }
        try {
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
            carbonContext.setTenantDomain(tenantDomain);
            carbonContext.setTenantId(IdentityTenantUtil.getTenantId(tenantDomain));
            storeCDSEnabled(cdsConfig.isEnabled());
        } catch (ConfigurationManagementException e) {
            throw serverException(ERROR_CODE_CDS_CONFIG_UPDATE, e, Utils.sanitizeForLog(tenantDomain));
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
    }

    /**
     * Get the tenant domain of the root organization. A root organization gets its own tenant domain.
     */
    private String getRootTenantDomain(String tenantDomain) throws OrganizationManagementException {

        if (OrganizationManagementUtil.isOrganization(tenantDomain)) {
            return OrganizationManagementUtil.getRootOrgTenantDomainBySubOrgTenantDomain(tenantDomain);
        }
        return tenantDomain;
    }

    private boolean isTenantCDSEnabled(String tenantDomain) throws CDSConfigMgtServerException {

        try {
            return getTenantCDSEnabled(tenantDomain).orElse(false);
        } catch (ConfigurationManagementException e) {
            throw serverException(ERROR_CODE_CDS_CONFIG_RETRIEVE, e, Utils.sanitizeForLog(tenantDomain));
        }
    }

        private Optional<Boolean> getTenantCDSEnabled(String tenantDomain) throws ConfigurationManagementException {

        ConfigurationManager configurationManager = CDSConfigMgtDataHolder.getInstance().getConfigurationManager();
        Resource resource;
        try {
            resource = configurationManager.getResourceByTenantId(IdentityTenantUtil.getTenantId(tenantDomain),
                    CDS_CONFIG_RESOURCE_TYPE, CDS_CONFIG_RESOURCE_NAME);
        } catch (ConfigurationManagementException e) {
            if (ERROR_CODE_RESOURCE_DOES_NOT_EXISTS.getCode().equals(e.getErrorCode()) ||
                    ERROR_CODE_RESOURCE_TYPE_DOES_NOT_EXISTS.getCode().equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
        if (resource == null || resource.getAttributes() == null) {
            return Optional.empty();
        }
        for (Attribute attribute : resource.getAttributes()) {
            if (CDS_CONFIG_ENABLED_ATTRIBUTE.equals(attribute.getKey())) {
                return Optional.of(Boolean.parseBoolean(attribute.getValue()));
            }
        }
        return Optional.empty();
    }

    private void storeCDSEnabled(boolean cdsEnabled) throws ConfigurationManagementException {

        ConfigurationManager configurationManager = CDSConfigMgtDataHolder.getInstance().getConfigurationManager();
        ResourceAdd resourceAdd = new ResourceAdd();
        resourceAdd.setName(CDS_CONFIG_RESOURCE_NAME);
        resourceAdd.setAttributes(Collections.singletonList(
                new Attribute(CDS_CONFIG_ENABLED_ATTRIBUTE, String.valueOf(cdsEnabled))));
        try {
            configurationManager.replaceResource(CDS_CONFIG_RESOURCE_TYPE, resourceAdd);
        } catch (ConfigurationManagementException e) {
            if (!ERROR_CODE_RESOURCE_TYPE_DOES_NOT_EXISTS.getCode().equals(e.getErrorCode())) {
                throw e;
            }
            // The resource type is created on the first update.
            addCDSConfigResourceType(configurationManager);
            configurationManager.replaceResource(CDS_CONFIG_RESOURCE_TYPE, resourceAdd);
        }
    }

    private void addCDSConfigResourceType(ConfigurationManager configurationManager)
            throws ConfigurationManagementException {

        ResourceTypeAdd resourceType = new ResourceTypeAdd();
        resourceType.setName(CDS_CONFIG_RESOURCE_TYPE);
        resourceType.setDescription("Resource type for the Customer Data Service configuration.");
        try {
            configurationManager.addResourceType(resourceType);
        } catch (ConfigurationManagementException e) {
            if (!ERROR_CODE_RESOURCE_TYPE_ALREADY_EXISTS.getCode().equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private CDSConfigMgtClientException clientException(ErrorMessages error, Throwable cause, Object... data) {

        return new CDSConfigMgtClientException(error.getMessage(), String.format(error.getDescription(), data),
                error.getCode(), cause);
    }

    private CDSConfigMgtServerException serverException(ErrorMessages error, Throwable cause, Object... data) {

        return new CDSConfigMgtServerException(error.getMessage(), String.format(error.getDescription(), data),
                error.getCode(), cause);
    }
}

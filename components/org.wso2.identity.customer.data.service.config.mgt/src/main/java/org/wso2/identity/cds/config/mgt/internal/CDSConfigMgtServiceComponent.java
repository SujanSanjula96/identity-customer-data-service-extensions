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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.application.mgt.listener.ApplicationMgtListener;
import org.wso2.carbon.identity.application.mgt.listener.AuthorizedAPIManagementListener;
import org.wso2.carbon.identity.configuration.mgt.core.ConfigurationManager;
import org.wso2.identity.cds.config.mgt.CDSConfigService;

/**
 * OSGi component that registers the CDS config service.
 */
@Component(name = "org.wso2.identity.cds.config.mgt", immediate = true)
public class CDSConfigMgtServiceComponent {

    private static final Log LOG = LogFactory.getLog(CDSConfigMgtServiceComponent.class);

    @Activate
    protected void activate(ComponentContext context) {

        context.getBundleContext().registerService(CDSConfigService.class.getName(), new CDSConfigServiceImpl(),
                null);
        context.getBundleContext().registerService(ApplicationMgtListener.class.getName(),
                new CDSApplicationShareListener(), null);
        context.getBundleContext().registerService(AuthorizedAPIManagementListener.class.getName(),
                new CDSAuthorizedAPIListener(), null);
        if (LOG.isDebugEnabled()) {
            LOG.debug("CDS config management component is activated.");
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        if (LOG.isDebugEnabled()) {
            LOG.debug("CDS config management component is deactivated.");
        }
    }

    @Reference(
            name = "resource.configuration.manager",
            service = ConfigurationManager.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetConfigurationManager"
    )
    protected void setConfigurationManager(ConfigurationManager configurationManager) {

        CDSConfigMgtDataHolder.getInstance().setConfigurationManager(configurationManager);
    }

    protected void unsetConfigurationManager(ConfigurationManager configurationManager) {

        CDSConfigMgtDataHolder.getInstance().setConfigurationManager(null);
    }
}

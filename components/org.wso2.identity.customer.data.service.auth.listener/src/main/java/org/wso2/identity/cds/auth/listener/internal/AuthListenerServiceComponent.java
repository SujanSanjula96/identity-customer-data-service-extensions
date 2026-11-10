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

package org.wso2.identity.cds.auth.listener.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.identity.cds.auth.listener.AuthEventListener;
import org.wso2.identity.cds.config.mgt.CDSConfigService;

/**
 * This class contains the implementation of the service component of the authentication event listener.
 */
@Component(
        name = "org.wso2.carbon.identity.cds.auth.listener",
        immediate = true
)
public class AuthListenerServiceComponent {

    private static final Log LOG = LogFactory.getLog(AuthListenerServiceComponent.class);

    @Activate
    protected void activate(ComponentContext context) {

        AuthEventListener authEventListener = new AuthEventListener();
        context.getBundleContext().registerService(AbstractEventHandler.class.getName(), authEventListener,
                null);
        LOG.debug("CDS post authentication listener activated");
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {

        if (LOG.isDebugEnabled()) {
            LOG.debug("CDS post authentication listener deactivated");
        }
    }

    @Reference(
            name = "cds.config.service",
            service = CDSConfigService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetCDSConfigService")
    protected void setCDSConfigService(CDSConfigService cdsConfigService) {

        AuthListenerServiceDataHolder.setCDSConfigService(cdsConfigService);
    }

    protected void unsetCDSConfigService(CDSConfigService cdsConfigService) {

        AuthListenerServiceDataHolder.setCDSConfigService(null);
    }
}

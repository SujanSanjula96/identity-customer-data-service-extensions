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

package org.wso2.identity.cds.auth.handler.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.PostAuthenticationHandler;
import org.wso2.identity.cds.auth.handler.CDSPostAuthnHandler;
import org.wso2.identity.cds.config.mgt.CDSConfigService;

/**
 * OSGi component for CDS Post Authentication Handler.
 */
@Component(name = "org.wso2.identity.cds.auth.handler",
        immediate = true)
public class CDSPostAuthHandlerComponent {

    private static final Log log = LogFactory.getLog(CDSPostAuthHandlerComponent.class);

    @Activate
    protected void activate(ComponentContext ctx) {

        CDSPostAuthnHandler cdsPostAuthnHandler  = new CDSPostAuthnHandler();
        ctx.getBundleContext().registerService(PostAuthenticationHandler.class.getName(), cdsPostAuthnHandler,
                null);
        log.info("CDS Post Authentication handler activated successfully.");
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {
        if (log.isDebugEnabled()) {
            log.debug("CDS Post Authentication handler is deactivated");
        }
    }

    @Reference(
            name = "cds.config.service",
            service = CDSConfigService.class,
            cardinality = ReferenceCardinality.MANDATORY,
            policy = ReferencePolicy.DYNAMIC,
            unbind = "unsetCDSConfigService")
    protected void setCDSConfigService(CDSConfigService cdsConfigService) {

        CDSPostAuthHandlerDataHolder.setCDSConfigService(cdsConfigService);
    }

    protected void unsetCDSConfigService(CDSConfigService cdsConfigService) {

        CDSPostAuthHandlerDataHolder.setCDSConfigService(null);
    }
}

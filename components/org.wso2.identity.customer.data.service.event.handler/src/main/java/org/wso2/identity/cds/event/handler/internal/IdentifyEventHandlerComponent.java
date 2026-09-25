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

package org.wso2.identity.cds.event.handler.internal;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.wso2.carbon.identity.event.handler.AbstractEventHandler;
import org.wso2.identity.cds.event.handler.ClaimEventHandler;
import org.wso2.identity.cds.event.handler.IdentityEventHandler;
import org.wso2.identity.cds.event.handler.OrganizationEventHandler;

/**
 * OSGi component that registers the CDS IdentifyEventHandler.
 */
@Component(name = "org.wso2.identity.cds.event.handler", immediate = true)
public class IdentifyEventHandlerComponent {

    private static final Log log = LogFactory.getLog(IdentifyEventHandlerComponent.class);

    @Activate
    protected void activate(ComponentContext ctx) {

        IdentityEventHandler identityEventHandler = new IdentityEventHandler();
        ctx.getBundleContext().registerService(AbstractEventHandler.class.getName(), identityEventHandler, null);
        ClaimEventHandler claimEventHandler = new ClaimEventHandler();
        ctx.getBundleContext().registerService(AbstractEventHandler.class.getName(), claimEventHandler, null);
        OrganizationEventHandler organizationEventHandler = new OrganizationEventHandler();
        ctx.getBundleContext().registerService(AbstractEventHandler.class.getName(), organizationEventHandler, null);
        log.info("CDS EventHandlers activated successfully.");
    }

    @Deactivate
    protected void deactivate(ComponentContext ctx) {

        if (log.isDebugEnabled()) {
            log.debug("CDS EventHandlers deactivated.");
        }
    }
}

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
package org.wso2.identity.cds.event.handler.app;

import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.mgt.listener.AbstractApplicationMgtListener;

/**
 * Adds the main app properties to a shared app on the reads of the application management service:
 * the app list with required attributes, and the reads by name.
 */
public class SharedApplicationListener extends AbstractApplicationMgtListener {

    // After the IS listeners, which set the inherited configuration of a shared app.
    private static final int ORDER = 950;

    @Override
    public int getDefaultOrderId() {

        return ORDER;
    }

    @Override
    public boolean doPostGetServiceProvider(ServiceProvider serviceProvider, String applicationName,
                                            String tenantDomain) throws IdentityApplicationManagementException {

        MainApplicationProperties.addTo(serviceProvider, tenantDomain);
        return true;
    }

    @Override
    public boolean doPostGetApplicationExcludingFileBasedSPs(ServiceProvider serviceProvider, String applicationName,
                                                             String tenantDomain)
            throws IdentityApplicationManagementException {

        MainApplicationProperties.addTo(serviceProvider, tenantDomain);
        return true;
    }

    @Override
    public boolean doPostGetApplicationWithRequiredAttributes(ServiceProvider serviceProvider, String tenantDomain)
            throws IdentityApplicationManagementException {

        MainApplicationProperties.addTo(serviceProvider, tenantDomain);
        return true;
    }
}

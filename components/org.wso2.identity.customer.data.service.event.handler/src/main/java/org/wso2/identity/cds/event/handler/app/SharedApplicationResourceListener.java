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

import org.wso2.carbon.identity.application.common.model.ApplicationBasicInfo;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.mgt.listener.ApplicationResourceManagementListener;

/**
 * Adds the main app properties to a shared app on a read by the app ID, which the app API uses for
 * GET /applications/{id}. The other operations do not change.
 */
public class SharedApplicationResourceListener implements ApplicationResourceManagementListener {

    private static final int ORDER = 950;

    @Override
    public int getDefaultOrderId() {

        return ORDER;
    }

    @Override
    public boolean doPostGetApplicationByResourceId(ServiceProvider serviceProvider, String resourceId,
                                                    String tenantDomain) {

        MainApplicationProperties.addTo(serviceProvider, tenantDomain);
        return true;
    }

    @Override
    public boolean doPreCreateApplication(ServiceProvider serviceProvider, String tenantDomain, String userName) {

        return true;
    }

    @Override
    public boolean doPostCreateApplication(String resourceId, ServiceProvider serviceProvider, String tenantDomain,
                                           String userName) {

        return true;
    }

    @Override
    public boolean doPreUpdateApplicationByResourceId(ServiceProvider serviceProvider, String resourceId,
                                                      String tenantDomain, String userName) {

        return true;
    }

    @Override
    public boolean doPostUpdateApplicationByResourceId(ServiceProvider serviceProvider, String resourceId,
                                                       String tenantDomain, String userName) {

        return true;
    }

    @Override
    public boolean doPreDeleteApplicationByResourceId(String resourceId, String tenantDomain, String userName) {

        return true;
    }

    @Override
    public boolean doPostDeleteApplicationByResourceId(ServiceProvider serviceProvider, String resourceId,
                                                       String tenantDomain, String userName) {

        return true;
    }

    @Override
    public boolean doPreGetApplicationByResourceId(String resourceId, String tenantDomain) {

        return true;
    }

    @Override
    public boolean doPreGetApplicationBasicInfoByResourceId(String resourceId, String tenantDomain) {

        return true;
    }

    @Override
    public boolean doPostGetApplicationBasicInfoByResourceId(ApplicationBasicInfo appInfo, String resourceId,
                                                             String tenantDomain) {

        return true;
    }
}

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

import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationManagementUtil;
import org.wso2.carbon.identity.application.mgt.listener.AbstractApplicationMgtListener;

/**
 * Lets the Identity Server update the application of CDS while this extension shares it with sub organizations.
 *
 * A deployment reserves the name of the application in [system_applications] read_only_apps, which blocks its updates.
 * Sharing updates the application more than once, and one of those updates does not allow for a reserved application,
 * so sharing a reserved application fails. This listener runs before the listener that blocks the update, and allows
 * it for the application of CDS, only while this extension shares it. Every other update stays blocked.
 */
public class CDSApplicationShareListener extends AbstractApplicationMgtListener {

    // Before AppPortalApplicationMgtListener (30), which blocks updates of reserved applications.
    private static final int ORDER_ID = 25;
    private static final ThreadLocal<Boolean> SHARING = new ThreadLocal<>();

    /**
     * Allow updates of the application of CDS on this thread until {@link #endSharing()}.
     */
    static void startSharing() {

        SHARING.set(Boolean.TRUE);
    }

    /**
     * Stop allowing updates of the application of CDS on this thread.
     */
    static void endSharing() {

        SHARING.remove();
        IdentityApplicationManagementUtil.removeAllowUpdateSystemApplicationThreadLocal();
    }

    @Override
    public int getDefaultOrderId() {

        return ORDER_ID;
    }

    @Override
    public boolean doPreUpdateApplication(ServiceProvider serviceProvider, String tenantDomain, String userName) {

        if (Boolean.TRUE.equals(SHARING.get()) && serviceProvider != null &&
                CDSServiceCredentialsManager.APPLICATION_NAME.equals(serviceProvider.getApplicationName())) {
            IdentityApplicationManagementUtil.setAllowUpdateSystemApplicationThreadLocal(true);
        }
        return true;
    }
}

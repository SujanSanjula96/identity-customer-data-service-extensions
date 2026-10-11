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

package org.wso2.identity.cds.client.internal;

import org.wso2.carbon.identity.organization.management.service.OrganizationManager;

/**
 * Holds the OSGi services that the CDS client uses.
 */
public class CDSClientDataHolder {

    private static final CDSClientDataHolder INSTANCE = new CDSClientDataHolder();

    private OrganizationManager organizationManager;

    private CDSClientDataHolder() {

    }

    /**
     * Get the instance of the data holder.
     *
     * @return The instance of the data holder.
     */
    public static CDSClientDataHolder getInstance() {

        return INSTANCE;
    }

    /**
     * Get the organization manager.
     *
     * @return The organization manager.
     */
    public OrganizationManager getOrganizationManager() {

        return organizationManager;
    }

    /**
     * Set the organization manager.
     *
     * @param organizationManager The organization manager.
     */
    public void setOrganizationManager(OrganizationManager organizationManager) {

        this.organizationManager = organizationManager;
    }
}

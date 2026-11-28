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

package org.wso2.identity.cds.event.handler;

/**
 * Constants used in the CDS Event Handler.
 */
public class Constants {
    public static final String EVENT = "event";
    public static final String PROFILE_COOKIE = "profileCookie";
    public static final String USER_ID = "userId";
    public static final String USER_ID_PROPERTY = "USER_ID";
    public static final String ORG_HANDLE = "orgHandle";
    public static final String TENANT_DOMAIN = "tenant-domain";
    public static final String USER_CLAIMS_PROPERTY = "USER_CLAIMS";
    public static final String CLAIMS = "claims";
    public static final String WSO2_CLAIMS_DIALECT = "http://wso2.org/claims/";
    public static final String USER_ID_CLAIM = "http://wso2.org/claims/userid";

    // The Identity Server sets this thread local property while it adds a shared user to an organization.
    public static final String PROCESS_ADD_SHARED_USER = "processAddSharedUser";
    // The claim with which the Identity Server marks a shared user: the organization that manages the user.
    public static final String MANAGED_ORGANIZATION_CLAIM = "http://wso2.org/claims/identity/managedOrg";

    // Organization management events and their properties.
    public static final String POST_ADD_ORGANIZATION = "POST_ADD_ORGANIZATION";
    public static final String POST_UPDATE_ORGANIZATION = "POST_UPDATE_ORGANIZATION";
    public static final String POST_PATCH_ORGANIZATION = "POST_PATCH_ORGANIZATION";
    public static final String POST_DELETE_ORGANIZATION = "POST_DELETE_ORGANIZATION";
    public static final String EVENT_PROP_ORGANIZATION = "ORGANIZATION";
    public static final String EVENT_PROP_ORGANIZATION_ID = "ORGANIZATION_ID";

    // CDS organization event names, which are neutral to the identity provider, and their properties.
    public static final String ORG_CREATED = "ORG_CREATED";
    public static final String ORG_UPDATED = "ORG_UPDATED";
    public static final String ORG_DELETED = "ORG_DELETED";
    public static final String ORG_ID = "org_id";

    // Default order of the organization event handler, so that it runs after the other organization handlers.
    public static final int ORGANIZATION_EVENT_HANDLER_PRIORITY = 100;


}

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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationConfig;
import org.wso2.carbon.identity.application.common.model.InboundAuthenticationRequestConfig;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.ServiceProviderProperty;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the identifiers of the main app to a shared (fragment) app of a sub org. CDS keys the
 * application data of a shared app by the main app in every org (R-016), and the sub org Console
 * uses these properties to map that key to the name of the shared app.
 * <p>
 * The properties are computed on read and are not stored. The org managers are read by reflection,
 * so that this bundle does not import the organization management services.
 */
public final class MainApplicationProperties {

    public static final String MAIN_APPLICATION_ID = "mainApplicationId";
    public static final String MAIN_APPLICATION_CLIENT_ID = "mainApplicationClientId";

    private static final Log LOG = LogFactory.getLog(MainApplicationProperties.class);
    private static final String IS_FRAGMENT_APP = "isFragmentApp";
    private static final String OAUTH2 = "oauth2";
    private static final String SAML = "samlsso";
    private static final String ORGANIZATION_MANAGER =
            "org.wso2.carbon.identity.organization.management.service.OrganizationManager";
    private static final String ORG_APPLICATION_MANAGER =
            "org.wso2.carbon.identity.organization.management.application.OrgApplicationManager";
    private static final int CACHE_SIZE = 10000;

    // A shared app keeps its main app for its whole life, so the result does not expire.
    private static final Map<String, String[]> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {

                    return size() > CACHE_SIZE;
                }
            });

    private MainApplicationProperties() {

    }

    /**
     * Adds the main app properties to the app when it is a shared app. Other apps do not change.
     *
     * @param serviceProvider The app that IS returns.
     * @param tenantDomain    The tenant domain of the org of the app.
     */
    public static void addTo(ServiceProvider serviceProvider, String tenantDomain) {

        if (serviceProvider == null || !isFragmentApp(serviceProvider) ||
                isBlank(serviceProvider.getApplicationResourceId()) ||
                hasProperty(serviceProvider, MAIN_APPLICATION_ID)) {
            return;
        }
        String[] main = CACHE.get(serviceProvider.getApplicationResourceId());
        if (main == null) {
            main = resolve(serviceProvider.getApplicationResourceId(), tenantDomain);
            if (main == null) {
                return;
            }
            CACHE.put(serviceProvider.getApplicationResourceId(), main);
        }
        List<ServiceProviderProperty> properties = serviceProvider.getSpProperties() == null ?
                new ArrayList<>() : new ArrayList<>(Arrays.asList(serviceProvider.getSpProperties()));
        properties.add(property(MAIN_APPLICATION_ID, main[0]));
        if (!isBlank(main[1])) {
            properties.add(property(MAIN_APPLICATION_CLIENT_ID, main[1]));
        }
        serviceProvider.setSpProperties(properties.toArray(new ServiceProviderProperty[0]));
    }

    /**
     * Returns the main app ID and its client ID (or SAML issuer), or null when the app is not found.
     */
    private static String[] resolve(String sharedAppId, String tenantDomain) {

        Bundle bundle = FrameworkUtil.getBundle(MainApplicationProperties.class);
        BundleContext context = bundle == null ? null : bundle.getBundleContext();
        if (context == null) {
            return null;
        }
        ServiceReference<?> orgReference = context.getServiceReference(ORGANIZATION_MANAGER);
        ServiceReference<?> appReference = context.getServiceReference(ORG_APPLICATION_MANAGER);
        if (orgReference == null || appReference == null) {
            return null;
        }
        Object organizationManager = context.getService(orgReference);
        Object orgApplicationManager = context.getService(appReference);
        try {
            String sharedOrgId = (String) invoke(organizationManager, "resolveOrganizationId",
                    new Class<?>[]{String.class}, tenantDomain);
            String mainAppId = (String) invoke(orgApplicationManager, "getMainApplicationIdForGivenSharedApp",
                    new Class<?>[]{String.class, String.class}, sharedAppId, sharedOrgId);
            if (isBlank(mainAppId)) {
                return null;
            }
            return new String[]{mainAppId, clientIdOf(organizationManager, orgApplicationManager, mainAppId,
                    sharedAppId, sharedOrgId)};
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOG.warn("Could not find the main app of the shared app: " + sharedAppId, e);
            return null;
        } finally {
            context.ungetService(orgReference);
            context.ungetService(appReference);
        }
    }

    /**
     * Returns the client ID or the SAML issuer of the main app. The main app is in the root, unless
     * a sub org shared its own app. Then the owner org comes from the ancestor apps of the shared app.
     */
    private static String clientIdOf(Object organizationManager, Object orgApplicationManager, String mainAppId,
                                     String sharedAppId, String sharedOrgId) throws ReflectiveOperationException {

        String rootOrgId = (String) invoke(organizationManager, "getPrimaryOrganizationId",
                new Class<?>[]{String.class}, sharedOrgId);
        String ownerOrgId = rootOrgId;
        ServiceProvider mainApp = readApp(organizationManager, mainAppId, ownerOrgId);
        if (mainApp == null) {
            Object ancestors = invoke(orgApplicationManager, "getAncestorAppIds",
                    new Class<?>[]{String.class, String.class}, sharedAppId, sharedOrgId);
            if (ancestors instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) ancestors).entrySet()) {
                    if (mainAppId.equals(entry.getValue())) {
                        ownerOrgId = String.valueOf(entry.getKey());
                    }
                }
            }
            if (!ownerOrgId.equals(rootOrgId)) {
                mainApp = readApp(organizationManager, mainAppId, ownerOrgId);
            }
        }
        if (mainApp == null) {
            return null;
        }
        InboundAuthenticationConfig inbound = mainApp.getInboundAuthenticationConfig();
        if (inbound == null || inbound.getInboundAuthenticationRequestConfigs() == null) {
            return null;
        }
        String issuer = null;
        for (InboundAuthenticationRequestConfig config : inbound.getInboundAuthenticationRequestConfigs()) {
            if (OAUTH2.equals(config.getInboundAuthType())) {
                return config.getInboundAuthKey();
            }
            if (SAML.equals(config.getInboundAuthType())) {
                issuer = config.getInboundAuthKey();
            }
        }
        return issuer;
    }

    private static ServiceProvider readApp(Object organizationManager, String appId, String orgId)
            throws ReflectiveOperationException {

        if (isBlank(orgId)) {
            return null;
        }
        String tenantDomain = (String) invoke(organizationManager, "resolveTenantDomain",
                new Class<?>[]{String.class}, orgId);
        try {
            return ApplicationManagementService.getInstance().getApplicationByResourceId(appId, tenantDomain);
        } catch (Exception e) {
            LOG.debug("Could not read the app " + appId + " in the org " + orgId, e);
            return null;
        }
    }

    private static boolean isBlank(String value) {

        return value == null || value.trim().isEmpty();
    }

    private static boolean isFragmentApp(ServiceProvider serviceProvider) {

        return serviceProvider.getSpProperties() != null && Arrays.stream(serviceProvider.getSpProperties())
                .anyMatch(p -> IS_FRAGMENT_APP.equals(p.getName()) && Boolean.parseBoolean(p.getValue()));
    }

    private static boolean hasProperty(ServiceProvider serviceProvider, String name) {

        return serviceProvider.getSpProperties() != null && Arrays.stream(serviceProvider.getSpProperties())
                .anyMatch(p -> name.equals(p.getName()));
    }

    private static ServiceProviderProperty property(String name, String value) {

        ServiceProviderProperty property = new ServiceProviderProperty();
        property.setName(name);
        property.setDisplayName(name);
        property.setValue(value);
        return property;
    }

    private static Object invoke(Object target, String methodName, Class<?>[] types, Object... arguments)
            throws ReflectiveOperationException {

        if (target == null) {
            return null;
        }
        Method method = target.getClass().getMethod(methodName, types);
        return method.invoke(target, arguments);
    }
}

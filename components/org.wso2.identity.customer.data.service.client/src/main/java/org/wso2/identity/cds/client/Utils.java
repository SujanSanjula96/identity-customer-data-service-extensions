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

package org.wso2.identity.cds.client;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.securevault.SecretResolver;
import org.wso2.securevault.SecretResolverFactory;
import org.wso2.securevault.commons.MiscellaneousUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/**
 * This class contains utility methods for the Customer Data Management Service client.
 */
public class Utils {

    private static final Log LOG = LogFactory.getLog(Utils.class);

    private static final String CONNECT_TIMEOUT = "CustomerDataService.ConnectTimeout";
    private static final String READ_TIMEOUT = "CustomerDataService.ReadTimeout";
    private static final String CONNECTION_REQUEST_TIMEOUT = "CustomerDataService.ConnectionRequestTimeout";
    private static final String MAX_CONNECTIONS = "CustomerDataService.MaxConnections";

    // Default timeouts in milliseconds. The calls to CDS run on the Identity Server user management and login
    // threads, so the timeouts are short.
    static final int DEFAULT_CONNECT_TIMEOUT = 2000;
    static final int DEFAULT_READ_TIMEOUT = 5000;
    static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT = 2000;
    static final int DEFAULT_MAX_CONNECTIONS = 50;

    public static String getCDSServiceURL() {
        return IdentityUtil.getProperty("CustomerDataService.ServerURL");
    }

  // Get Base64 encoded credentials for CDS admin user
    public static String getBase64EncodedCredentials() {

      String usernameProp =
              IdentityUtil.getProperty("CustomerDataService.AdminUsername");
      String passwordProp =
              IdentityUtil.getProperty("CustomerDataService.AdminPassword");

      String resolvedUserName = resolveIfRequired("CustomerDataService.AdminUsername",
                      StringUtils.trimToNull(usernameProp));

      String resolvedPassword = resolveIfRequired("CustomerDataService.AdminPassword",
                      StringUtils.trimToNull(passwordProp));

      if (StringUtils.isBlank(resolvedUserName) || StringUtils.isBlank(resolvedPassword)) {
          return "";
      }

      String credentials = resolvedUserName + ":" + resolvedPassword;

      return Base64.getEncoder()
              .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }


    /**
     * Get the time in milliseconds to wait for a connection to CDS.
     *
     * @return The value of CustomerDataService.ConnectTimeout, or the default if it is not set or not valid.
     */
    public static int getConnectTimeout() {

        return getPositiveIntProperty(CONNECT_TIMEOUT, DEFAULT_CONNECT_TIMEOUT);
    }

    /**
     * Get the time in milliseconds to wait for data from CDS on an open connection.
     *
     * @return The value of CustomerDataService.ReadTimeout, or the default if it is not set or not valid.
     */
    public static int getReadTimeout() {

        return getPositiveIntProperty(READ_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    /**
     * Get the time in milliseconds to wait for a free connection from the connection pool.
     *
     * @return The value of CustomerDataService.ConnectionRequestTimeout, or the default if it is not set or not
     * valid.
     */
    public static int getConnectionRequestTimeout() {

        return getPositiveIntProperty(CONNECTION_REQUEST_TIMEOUT, DEFAULT_CONNECTION_REQUEST_TIMEOUT);
    }

    /**
     * Get the maximum number of connections to CDS.
     *
     * @return The value of CustomerDataService.MaxConnections, or the default if it is not set or not valid.
     */
    public static int getMaxConnections() {

        return getPositiveIntProperty(MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS);
    }

    private static int getPositiveIntProperty(String key, int defaultValue) {

        String value = StringUtils.trimToNull(IdentityUtil.getProperty(key));
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // Use the default value below.
        }
        LOG.warn("The value of " + key + " is not a positive integer: " + sanitizeForLog(value) +
                ". The default value " + defaultValue + " is used.");
        return defaultValue;
    }

    public static boolean isCDSEnabled() {
        String cdsEnabled = IdentityUtil.getProperty("CustomerDataService.Enable");
        return Boolean.parseBoolean(cdsEnabled);
    }

    // Resolve secrets if secure vault is enabled
    public static String resolveIfRequired(String key, String value) {

        if (value == null) {
            return null;
        }

        Properties props = new Properties();
        props.put(key, value);

        SecretResolver resolver = SecretResolverFactory.create(props);

        if (resolver != null && resolver.isInitialized()) {
            return MiscellaneousUtil.resolve(value, resolver);
        }
        return value;
    }

    /**
     * Prevent CRLF/log forging.
     * Updated to handle more edge cases and satisfy scanners.
     */
    public static String sanitizeForLog(Object input) {
        if (input == null) {
            return "null";
        }
        String clean = String.valueOf(input);
        if (StringUtils.isBlank(clean)) {
            return "empty";
        }
        // Comprehensive CRLF replacement
        return clean.replace("\r", "_")
                .replace("\n", "_")
                .replaceAll("(?i)%0a", "_")
                .replaceAll("(?i)%0d", "_");
    }
}

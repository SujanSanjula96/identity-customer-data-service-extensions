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

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * This class contains utility methods for the Customer Data Management Service client.
 */
public class Utils {

    private static final Log LOG = LogFactory.getLog(Utils.class);
    private static final String HTTPS = "https";
    private static final String HTTP = "http";
    private static final String LOCALHOST = "localhost";
    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    // The last URL that a warning or an error was logged for, so that each URL is reported once.
    private static volatile String reportedServiceURL;

    /**
     * Get the CDD URL from the configuration.
     * <p>
     * The Identity Server sends the CDD admin credentials, user IDs, user claims, the profile cookie and the service
     * credentials to this URL. So the URL must use HTTPS. HTTP is accepted only for a loopback host (localhost,
     * 127.0.0.0/8 or ::1), for local development, and a warning is logged.
     *
     * @return The CDD URL, or null if it is not configured or does not use HTTPS.
     */
    public static String getCDSServiceURL() {

        String serviceURL = StringUtils.trimToNull(IdentityUtil.getProperty("CustomerDataService.ServerURL"));
        if (serviceURL == null) {
            return null;
        }
        return isAllowedServiceURL(serviceURL) ? serviceURL : null;
    }

    /**
     * Check that the CDD URL uses HTTPS, or HTTP to a loopback host.
     *
     * @param serviceURL The configured CDD URL.
     * @return true if the Identity Server can send data to the URL.
     */
    static boolean isAllowedServiceURL(String serviceURL) {

        URI uri;
        try {
            uri = new URI(serviceURL);
        } catch (URISyntaxException e) {
            reportOnce(serviceURL, true, "The CDD URL (CustomerDataService.ServerURL) is not a valid URL. " +
                    "The Identity Server does not send data to CDD.");
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (HTTPS.equalsIgnoreCase(scheme) && StringUtils.isNotBlank(host)) {
            return true;
        }
        if (HTTP.equalsIgnoreCase(scheme) && isLoopbackHost(host)) {
            reportOnce(serviceURL, false, "The CDD URL (CustomerDataService.ServerURL) uses HTTP. HTTP is " +
                    "accepted only because the host is a loopback address. Use HTTPS in a deployment.");
            return true;
        }
        reportOnce(serviceURL, true, "The CDD URL (CustomerDataService.ServerURL) must use HTTPS. Host: " +
                sanitizeForLog(host) + ". The Identity Server does not send data to CDD.");
        return false;
    }

    /**
     * Check if a host is a loopback host. Only "localhost" and IP address literals are checked, so that no name is
     * resolved.
     *
     * @param host Host of the URL.
     * @return true for localhost, 127.0.0.0/8 and ::1.
     */
    static boolean isLoopbackHost(String host) {

        if (StringUtils.isBlank(host)) {
            return false;
        }
        String address = host;
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        if (LOCALHOST.equalsIgnoreCase(address)) {
            return true;
        }
        if (!IPV4_LITERAL.matcher(address).matches() && !address.contains(":")) {
            return false;
        }
        try {
            // An IP address literal is parsed without a name lookup.
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static void reportOnce(String serviceURL, boolean error, String message) {

        if (serviceURL.equals(reportedServiceURL)) {
            return;
        }
        reportedServiceURL = serviceURL;
        if (error) {
            LOG.error(message);
        } else {
            LOG.warn(message);
        }
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

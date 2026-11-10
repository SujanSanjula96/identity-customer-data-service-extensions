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

package org.wso2.identity.cds.auth.handler;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.context.AuthenticationContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.PostAuthenticationFailedException;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.AbstractPostAuthnHandler;
import org.wso2.carbon.identity.application.authentication.framework.handler.request.PostAuthnHandlerFlowStatus;
import org.wso2.identity.cds.auth.handler.internal.CDSPostAuthHandlerDataHolder;
import org.wso2.identity.cds.config.mgt.CDSConfigService;
import org.wso2.identity.cds.config.mgt.exception.CDSConfigMgtException;

import java.util.Optional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Post authentication handler captures CDS related cookies and store them in the authentication context.
 */
public class CDSPostAuthnHandler extends AbstractPostAuthnHandler {

    private static final Log LOG = LogFactory.getLog(CDSPostAuthnHandler.class);
    private static final String CDS_PROFILE = "cds_profile";
    private static final String ANONYMOUS_PROFILE_TRACKER = "anonymous_profile_tracker";

    @Override
    public PostAuthnHandlerFlowStatus handle(HttpServletRequest request,
                                             HttpServletResponse response,
                                             AuthenticationContext context)
            throws PostAuthenticationFailedException {

        if (!isCDSEnabled(context.getTenantDomain())) {
            return PostAuthnHandlerFlowStatus.SUCCESS_COMPLETED;
        }

        resolveCDSProfile(request, context).ifPresent(val -> {
            context.setProperty(CDS_PROFILE, val);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Setting cds_profile cookie in authentication context");
            }
        });
        return PostAuthnHandlerFlowStatus.SUCCESS_COMPLETED;
    }

    private Optional<String> resolveCDSProfile(HttpServletRequest request, AuthenticationContext context) {

        if (request.getCookies() != null) {
            for (javax.servlet.http.Cookie cookie : request.getCookies()) {
                if (CDS_PROFILE.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (StringUtils.isNotBlank(value)) {
                        return Optional.of(value);
                    }
                    break;
                }
            }
        }
        // The post-auth handler runs against the sessionDataKey callback request, not the
        // original /oauth2/authorize request, so request.getParameter() no longer carries
        // ANONYMOUS_PROFILE_TRACKER. The original /authorize query parameters are preserved on the
        // AuthenticationContext.
        if (context.getAuthenticationRequest() != null) {
            String[] values = context.getAuthenticationRequest().getRequestQueryParam(ANONYMOUS_PROFILE_TRACKER);
            if (values != null && values.length > 0) {
                return Optional.ofNullable(values[0]);
            }
        }
        return Optional.empty();
    }

    private boolean isCDSEnabled(String tenantDomain) {

        CDSConfigService cdsConfigService = CDSPostAuthHandlerDataHolder.getCDSConfigService();
        if (cdsConfigService == null) {
            return false;
        }
        try {
            return cdsConfigService.isCDSEnabled(tenantDomain);
        } catch (CDSConfigMgtException e) {
            LOG.warn(e.getMessage() + ". CDS is not enabled for the tenant.", e);
            return false;
        }
    }
}

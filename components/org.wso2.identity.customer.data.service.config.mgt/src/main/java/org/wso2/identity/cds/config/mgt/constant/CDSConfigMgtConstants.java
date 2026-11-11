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

package org.wso2.identity.cds.config.mgt.constant;

/**
 * Constants of the CDS config management service.
 */
public class CDSConfigMgtConstants {

    private static final String ERROR_CODE_PREFIX = "CDSCM-";

    private CDSConfigMgtConstants() {

    }

    /**
     * Error messages of the CDS config management service.
     */
    public enum ErrorMessages {

        ERROR_CODE_CDS_NOT_ENABLED("60001",
                "CDS is not enabled.",
                "The Customer Data Service is not enabled for the server."),
        ERROR_CODE_CDS_CONFIG_RETRIEVE("65001",
                "Unable to retrieve CDS configuration.",
                "Server encountered an error while retrieving the CDS configuration of %s."),
        ERROR_CODE_CDS_CONFIG_UPDATE("65002",
                "Unable to update CDS configuration.",
                "Server encountered an error while updating the CDS configuration of %s."),
        ERROR_CODE_CDS_CONFIG_SYNC("65003",
                "Unable to update CDS configuration.",
                "Server encountered an error while sending the CDS configuration of %s to the Customer Data " +
                        "Service. %s");

        private final String code;
        private final String message;
        private final String description;

        ErrorMessages(String code, String message, String description) {

            this.code = code;
            this.message = message;
            this.description = description;
        }

        public String getCode() {

            return ERROR_CODE_PREFIX + code;
        }

        public String getMessage() {

            return message;
        }

        public String getDescription() {

            return description;
        }
    }
}

package com.trade.marketplace.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "trade.marketplace")
public class MarketplaceProperties {
    private int sessionRetentionDays = 30;
    private OssProperties oss = new OssProperties();

    @Data
    public static class OssProperties {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String roleArn = "";
        private String bucket = "";
        private String region = "";
        private String publicBaseUrl = "";
        private String stsEndpoint = "https://sts.aliyuncs.com";
        private int durationSeconds = 900;
        private String keyPrefix = "marketplace";

        public String requiredAccessKeyId() {
            return requiredText(accessKeyId, "trade.marketplace.oss.access-key-id is required");
        }

        public String requiredAccessKeySecret() {
            return requiredText(accessKeySecret, "trade.marketplace.oss.access-key-secret is required");
        }

        public String requiredRoleArn() {
            return requiredText(roleArn, "trade.marketplace.oss.role-arn is required");
        }

        public String requiredBucket() {
            return requiredText(bucket, "trade.marketplace.oss.bucket is required");
        }

        public String requiredRegion() {
            return requiredText(region, "trade.marketplace.oss.region is required");
        }

        public String normalizedPublicBaseUrl() {
            String regionEndpoint = requiredRegion();
            if (!regionEndpoint.startsWith("oss-")) {
                regionEndpoint = "oss-" + regionEndpoint;
            }
            String base = publicBaseUrl == null || publicBaseUrl.isBlank()
                    ? "https://" + requiredBucket() + "." + regionEndpoint + ".aliyuncs.com"
                    : publicBaseUrl.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base;
        }

        public String normalizedStsEndpoint() {
            String endpoint = requiredText(stsEndpoint, "trade.marketplace.oss.sts-endpoint is required");
            while (endpoint.endsWith("/")) {
                endpoint = endpoint.substring(0, endpoint.length() - 1);
            }
            return endpoint;
        }

        public int normalizedDurationSeconds() {
            return Math.max(900, Math.min(3600, durationSeconds));
        }

        public String normalizedKeyPrefix() {
            String prefix = keyPrefix == null || keyPrefix.isBlank() ? "marketplace" : keyPrefix.trim();
            while (prefix.startsWith("/")) {
                prefix = prefix.substring(1);
            }
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            return prefix.isBlank() ? "marketplace" : prefix;
        }

        private static String requiredText(String value, String message) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(message);
            }
            return value.trim();
        }
    }
}

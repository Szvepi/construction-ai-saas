package com.buildassist.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Encryption encryption = new Encryption();
    private final Gmail gmail = new Gmail();
    private final EmailClassification emailClassification = new EmailClassification();
    private final OpenAi openAi = new OpenAi();
    private String frontendUrl;

    @Data
    public static class Jwt {
        private String secret;
        private int expiryHours = 24;
    }

    @Data
    public static class Cors {
        private String allowedOrigins;
    }

    @Data
    public static class Encryption {
        private String aesKey;
    }

    @Data
    public static class Gmail {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
        private int syncWindowMonths = 6;
        private String quotaProjectKey = "default";
        private long projectQuotaUnitsPerMinute = 1_200_000;
        private long userQuotaUnitsPerMinute = 6_000;
        private double quotaSafetyMargin = 0.2;
        private int maxConcurrentRequestsPerAccount = 1;
        private int syncPollIntervalMs = 30_000;
        private int retryBaseDelaySeconds = 5;
        private int retryMaxDelaySeconds = 900;
        private int retryMaxAttempts = 8;
        private int listQuotaUnits = 5;
        private int getMetadataQuotaUnits = 20;
        private int getFullQuotaUnits = 60;
        private int historyQuotaUnits = 2;
        private int profileQuotaUnits = 1;
        private int draftCreateQuotaUnits = 10;
    }

    @Data
    public static class OpenAi {
        private String apiKey;
    }

    @Data
    public static class EmailClassification {
        private boolean enabled = true;
        private String version = "v1";
        private int maxPerRun = 5;
        private int maxPerDay = 100;
        private int maxReclassificationBatch = 100;
        private int maxAttempts = 3;
        private int retryBaseSeconds = 300;
        private int staleProcessingMinutes = 10;
        private int pollIntervalMs = 30_000;
        private int maxBodyCharacters = 12_000;
    }
}

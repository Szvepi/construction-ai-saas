package com.buildassist.config;

import java.math.BigDecimal;
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
        private boolean enabled;
        private String version;
        private int maxPerRun;
        private int maxPerDay;
        private int maxPerUserPerDay;
        private BigDecimal maxCostUsdPerDay;
        private BigDecimal inputPriceUsdPerMillionTokens;
        private BigDecimal outputPriceUsdPerMillionTokens;
        private int maxInputTokens;
        private int maxOutputTokens;
        private String dailyBudgetTimeZone;
        private int freshPriorityMinutes;
        private int metricsIntervalMs;
        private int maxReclassificationBatch;
        private int maxAttempts;
        private int retryBaseSeconds;
        private int retryMaxSeconds;
        private int staleProcessingMinutes;
        private int pollIntervalMs;
    }
}

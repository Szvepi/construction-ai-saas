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
    }

    @Data
    public static class OpenAi {
        private String apiKey;
    }
}

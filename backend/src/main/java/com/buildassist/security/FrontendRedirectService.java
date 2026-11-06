package com.buildassist.security;

import com.buildassist.config.AppProperties;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class FrontendRedirectService {

    private final AppProperties properties;

    public FrontendRedirectService(AppProperties properties) {
        this.properties = properties;
    }

    public String frontendOrigin() {
        URI frontend = parseOrigin(properties.getFrontendUrl());
        String allowedOrigins = properties.getCors().getAllowedOrigins();
        boolean allowed = allowedOrigins != null && Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty() && !"*".equals(value))
                .map(FrontendRedirectService::parseOrigin)
                .anyMatch(origin -> origin.equals(frontend));
        if (!allowed) {
            throw new IllegalStateException("FRONTEND_URL must match an allowed CORS origin");
        }
        return frontend.toString();
    }

    public String googleLoginFailureUrl() {
        return UriComponentsBuilder.fromUriString(frontendOrigin())
                .path("/auth/login")
                .queryParam("oauthError", "google")
                .build()
                .toUriString();
    }

    public String gmailConnectResultUrl(boolean connected) {
        return UriComponentsBuilder.fromUriString(frontendOrigin())
                .path("/dashboard")
                .queryParam("gmailConnect", connected ? "connected" : "failed")
                .build()
                .toUriString();
    }

    private static URI parseOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
                throw new IllegalStateException("OAuth frontend URL must be an HTTP(S) origin");
            }
            return URI.create(scheme.toLowerCase(Locale.ROOT) + "://"
                    + uri.getRawAuthority().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("OAuth frontend URL must be a valid HTTP(S) origin");
        }
    }
}

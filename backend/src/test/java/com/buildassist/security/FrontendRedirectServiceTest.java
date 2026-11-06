package com.buildassist.security;

import com.buildassist.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrontendRedirectServiceTest {

    @Test
    void redirectsUseOnlyTheConfiguredAllowlistedFrontendOrigin() {
        AppProperties properties = new AppProperties();
        properties.setFrontendUrl("http://localhost:3000/");
        properties.getCors().setAllowedOrigins("http://localhost:3000");

        FrontendRedirectService redirects = new FrontendRedirectService(properties);

        assertEquals("http://localhost:3000/auth/login?oauthError=google",
                redirects.googleLoginFailureUrl());
        assertEquals("http://localhost:3000/dashboard?gmailConnect=failed",
                redirects.gmailConnectResultUrl(false));
    }

    @Test
    void frontendRedirectIsRejectedWhenItIsNotAnAllowedOrigin() {
        AppProperties properties = new AppProperties();
        properties.setFrontendUrl("https://attacker.example");
        properties.getCors().setAllowedOrigins("http://localhost:3000");

        FrontendRedirectService redirects = new FrontendRedirectService(properties);

        assertThrows(IllegalStateException.class, redirects::googleLoginFailureUrl);
    }
}

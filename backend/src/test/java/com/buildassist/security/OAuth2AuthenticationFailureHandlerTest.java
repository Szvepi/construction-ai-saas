package com.buildassist.security;

import com.buildassist.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuth2AuthenticationFailureHandlerTest {

    @Test
    void failedGoogleLoginReturnsToFrontendLoginInsteadOfBackendLogin() throws Exception {
        AppProperties properties = new AppProperties();
        properties.setFrontendUrl("http://localhost:3000");
        properties.getCors().setAllowedOrigins("http://localhost:3000");
        OAuth2AuthenticationFailureHandler handler = new OAuth2AuthenticationFailureHandler(
                new FrontendRedirectService(properties));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

        assertEquals("http://localhost:3000/auth/login?oauthError=google", response.getRedirectedUrl());
    }
}

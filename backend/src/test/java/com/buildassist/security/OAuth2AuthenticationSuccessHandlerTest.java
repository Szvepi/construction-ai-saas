package com.buildassist.security;

import com.buildassist.config.AppProperties;
import com.buildassist.model.User;
import com.buildassist.repository.UserRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2AuthenticationSuccessHandlerTest {

    private static final String EMAIL = "owner@gmail.com";
    private UserRepository users;
    private JwtTokenProvider jwtTokens;
    private OAuth2AuthenticationSuccessHandler handler;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        jwtTokens = mock(JwtTokenProvider.class);
        AppProperties properties = new AppProperties();
        properties.setFrontendUrl("http://localhost:3000");
        properties.getCors().setAllowedOrigins("http://localhost:3000");
        FrontendRedirectService redirects = new FrontendRedirectService(properties);
        handler = new OAuth2AuthenticationSuccessHandler(users, jwtTokens, redirects);

        user = new User();
        user.setId(42L);
        user.setEmail(EMAIL);
        user.setActive(true);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(jwtTokens.createToken(42L, EMAIL)).thenReturn("test-jwt");
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("email", EMAIL), "email"));
    }

    @Test
    void identityOnlyGoogleLoginSucceedsWithoutGmailScopesOrPersistingGmailTokens() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("http://localhost:3000/auth/oauth-callback#token=test-jwt"));
        verify(users, never()).save(user);
        verify(jwtTokens).createToken(42L, EMAIL);
    }

    @Test
    void inactiveUserIsReturnedToFrontendWithoutIssuingAToken() throws Exception {
        user.setActive(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals("http://localhost:3000/auth/login?oauthError=google", response.getRedirectedUrl());
        verify(jwtTokens, never()).createToken(42L, EMAIL);
    }
}

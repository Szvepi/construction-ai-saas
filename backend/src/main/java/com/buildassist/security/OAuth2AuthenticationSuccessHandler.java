package com.buildassist.security;

import com.buildassist.config.AppProperties;
import com.buildassist.model.GmailConnection;
import com.buildassist.model.User;
import com.buildassist.repository.GmailConnectionRepository;
import com.buildassist.repository.UserRepository;
import com.buildassist.service.TokenEncryptionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AppProperties appProperties;
    private final GmailConnectionRepository gmailConnectionRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final OAuth2AuthorizedClientService oauth2ClientService;

    public OAuth2AuthenticationSuccessHandler(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            AppProperties appProperties,
            GmailConnectionRepository gmailConnectionRepository,
            TokenEncryptionService tokenEncryptionService,
            OAuth2AuthorizedClientService oauth2ClientService) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.appProperties = appProperties;
        this.gmailConnectionRepository = gmailConnectionRepository;
        this.tokenEncryptionService = tokenEncryptionService;
        this.oauth2ClientService = oauth2ClientService;
        // default target not used; we'll redirect to frontend callback with token
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        try {
            OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
            String email = oauth2User.getAttribute("email");
            if (email == null) {
                email = "";
            }

            // Find or create user
            final String userEmail = email;
            User user = userRepository.findByEmail(email)
                    .orElseGet(() -> {
                        User newUser = new User();
                        newUser.setEmail(userEmail);
                        newUser.setPasswordHash(""); // OAuth2 users don't have password
                        newUser.setActive(true); // Auto-activate OAuth2 users
                        return userRepository.save(newUser);
                    });

            // Ensure user is active
            if (!user.isActive()) {
                user.setActive(true);
                userRepository.save(user);
            }

            // Save/update GmailConnection with OAuth2 tokens
            try {
                saveGmailConnection(user, email, userEmail);
            } catch (Exception e) {
                logger.warn("Failed to save GmailConnection for user " + user.getId(), e);
                // Continue anyway - don't break the login flow
            }

            // Generate JWT token
            String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());

            // Redirect to frontend callback page with token in URL fragment
            // Token in fragment is not sent to the server and is accessible to browser JS
            String frontendUrl = appProperties.getFrontendUrl();
            if (frontendUrl == null || frontendUrl.isEmpty()) {
                frontendUrl = "http://localhost:3000";
            }
            String redirect = frontendUrl + "/auth/oauth-callback#token=" + token + "&email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
            logger.info("OAuth2 success - redirecting to frontend callback: " + redirect);

            // Some environments / proxies may strip URL fragments on Location redirects.
            // To ensure the browser ends up with the fragment, return a small HTML page
            // that performs a client-side navigation to the desired URL (preserves fragment).
            String safeToken = java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
            String safeEmail = java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
            String target = frontendUrl + "/auth/oauth-callback#token=" + safeToken + "&email=" + safeEmail;

            String escTarget = target.replace("'", "\\'");

            String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Redirecting...</title></head>" +
                    "<body><script>" +
                    "(function(){try{window.location.replace('" + escTarget + "');}catch(e){window.location.href='" + escTarget + "';}})();" +
                    "</script><p>Redirecting to application...</p></body></html>";

            response.setContentType("text/html;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(html);
            response.getWriter().flush();
        } catch (Exception e) {
            logger.error("OAuth2 authentication error", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication failed");
        }
    }

    private void saveGmailConnection(User user, String email, String principalName) {
        try {
            // Get OAuth2AuthorizedClient to extract tokens
            OAuth2AuthorizedClient authorizedClient = oauth2ClientService.loadAuthorizedClient("google", principalName);
            if (authorizedClient == null) {
                logger.warn("No OAuth2AuthorizedClient found for user " + user.getId());
                return;
            }

            OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
            if (accessToken == null) {
                logger.warn("No access token found in OAuth2AuthorizedClient for user " + user.getId());
                return;
            }

            String accessTokenValue = accessToken.getTokenValue();
            String refreshTokenValue = "";
            
            if (authorizedClient.getRefreshToken() != null) {
                refreshTokenValue = authorizedClient.getRefreshToken().getTokenValue();
            }

            // Find or create GmailConnection
            Optional<GmailConnection> existing = gmailConnectionRepository.findByUserId(user.getId());
            GmailConnection conn = existing.orElseGet(GmailConnection::new);
            
            conn.setUser(user);
            conn.setGmailAddress(email);
            conn.setAccessTokenEncrypted(tokenEncryptionService.encrypt(accessTokenValue));
            conn.setRefreshTokenEncrypted(tokenEncryptionService.encrypt(refreshTokenValue));
            
            // Set token expiry
            if (accessToken.getExpiresAt() != null) {
                conn.setTokenExpiresAt(accessToken.getExpiresAt());
            } else {
                conn.setTokenExpiresAt(Instant.now().plusSeconds(3600));
            }

            gmailConnectionRepository.save(conn);
            logger.info("GmailConnection saved for user " + user.getId() + " with email " + email);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save GmailConnection", e);
        }
    }
}


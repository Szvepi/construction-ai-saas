package com.buildassist.security;

import com.buildassist.config.AppProperties;
import com.buildassist.model.User;
import com.buildassist.repository.UserRepository;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final AppProperties appProperties;

    public OAuth2AuthenticationSuccessHandler(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.appProperties = appProperties;
        // default target not used; we'll redirect to frontend callback with token
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

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

            // Generate JWT token
            String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());

            // Redirect to frontend callback page with token in URL fragment
            // Token in fragment is not sent to the server and is accessible to browser JS
            String frontendUrl = appProperties.getFrontendUrl();
            if (frontendUrl == null || frontendUrl.isEmpty()) {
                frontendUrl = "http://localhost:3000";
            }
            String redirect = frontendUrl + "/auth/oauth-callback#token=" + token + "&email=" + java.net.URLEncoder.encode(email, java.nio.charset.StandardCharsets.UTF_8);
            response.sendRedirect(redirect);
        } catch (Exception e) {
            logger.error("OAuth2 authentication error", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication failed");
        }
    }
}


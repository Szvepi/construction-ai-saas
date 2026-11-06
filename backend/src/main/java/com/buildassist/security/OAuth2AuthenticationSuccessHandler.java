package com.buildassist.security;

import com.buildassist.model.User;
import com.buildassist.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final FrontendRedirectService redirects;

    public OAuth2AuthenticationSuccessHandler(
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            FrontendRedirectService redirects) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redirects = redirects;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {
        try {
            OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
            String email = oauth2User.getAttribute("email");
            if (email == null || email.isBlank()) {
                throw new IllegalStateException("Google authentication did not provide an email address");
            }

            User user = userRepository.findByEmail(email).orElseGet(() -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setPasswordHash("");
                newUser.setActive(true);
                return userRepository.save(newUser);
            });
            if (!user.isActive()) {
                response.sendRedirect(redirects.googleLoginFailureUrl());
                return;
            }

            String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());
            String safeToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
            String safeEmail = URLEncoder.encode(email, StandardCharsets.UTF_8);
            String target = redirects.frontendOrigin() + "/auth/oauth-callback#token="
                    + safeToken + "&email=" + safeEmail;
            String escapedTarget = target.replace("\\", "\\\\").replace("'", "\\'");
            String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>Redirecting...</title></head>"
                    + "<body><script>window.location.replace('" + escapedTarget
                    + "');</script><p>Redirecting to application...</p></body></html>";
            response.setContentType("text/html;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(html);
            response.getWriter().flush();
        } catch (Exception ex) {
            log.error("Google OAuth success handling failed category={}", ex.getClass().getSimpleName());
            response.sendRedirect(redirects.googleLoginFailureUrl());
        }
    }
}

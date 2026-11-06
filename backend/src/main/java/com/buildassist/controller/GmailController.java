package com.buildassist.controller;

import com.buildassist.dto.GmailDtos.GmailConnectResponse;
import com.buildassist.dto.GmailDtos.GmailStatusResponse;
import com.buildassist.security.FrontendRedirectService;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.GmailOAuthException;
import com.buildassist.service.GmailService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Gmail", description = "Gmail OAuth and connection status")
@RestController
@RequestMapping("/api/gmail")
public class GmailController {

    private static final Logger log = LoggerFactory.getLogger(GmailController.class);
    private final GmailService gmailService;
    private final FrontendRedirectService redirects;

    public GmailController(GmailService gmailService, FrontendRedirectService redirects) {
        this.gmailService = gmailService;
        this.redirects = redirects;
    }

    @GetMapping("/connect")
    public GmailConnectResponse connect() {
        return gmailService.getAuthorizationUrl(SecurityUtils.currentUserId());
    }

    @SecurityRequirements
    @GetMapping("/callback")
    public void callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletResponse response) throws IOException {
        try {
            if (error != null && !error.isBlank()) {
                gmailService.validateOAuthState(state);
                log.warn("Gmail OAuth authorization was declined category={}", safeCategory(error));
                response.sendRedirect(redirects.gmailConnectResultUrl(false));
                return;
            }
            gmailService.handleOAuthCallback(code, state);
            response.sendRedirect(redirects.gmailConnectResultUrl(true));
        } catch (Exception ex) {
            String category = ex instanceof GmailOAuthException gmailException
                    ? gmailException.getCategory()
                    : ex.getClass().getSimpleName();
            log.warn("Gmail OAuth callback failed category={}", category);
            response.sendRedirect(redirects.gmailConnectResultUrl(false));
        }
    }

    @GetMapping("/status")
    public GmailStatusResponse status() {
        return gmailService.getConnectionStatus(SecurityUtils.currentUserId());
    }

    private static String safeCategory(String value) {
        return value.matches("[A-Za-z0-9_-]{1,64}") ? value : "google_oauth_error";
    }
}

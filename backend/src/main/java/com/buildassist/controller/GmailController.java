package com.buildassist.controller;

import com.buildassist.dto.GmailDtos.GmailConnectResponse;
import com.buildassist.dto.GmailDtos.GmailStatusResponse;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.GmailService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Gmail", description = "Gmail OAuth and connection status")
@RestController
@RequestMapping("/api/gmail")
public class GmailController {

    private final GmailService gmailService;

    public GmailController(GmailService gmailService) {
        this.gmailService = gmailService;
    }

    @GetMapping("/connect")
    public GmailConnectResponse connect() {
        return gmailService.getAuthorizationUrl(SecurityUtils.currentUserId());
    }

    @SecurityRequirements
    @GetMapping("/callback")
    public void callback(
            @RequestParam String code,
            @RequestParam(required = false) String state) {
        gmailService.handleOAuthCallback(code, state);
    }

    @GetMapping("/status")
    public GmailStatusResponse status() {
        return gmailService.getConnectionStatus(SecurityUtils.currentUserId());
    }
}

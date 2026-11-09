package com.buildassist.controller;

import com.buildassist.dto.QuoteSetupDtos.QuoteSetupStatus;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.QuoteSetupValidationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Quote setup", description = "Readiness for AI quote generation")
@RestController
@RequestMapping("/api/quote-setup")
public class QuoteSetupController {

    private final QuoteSetupValidationService quoteSetupValidationService;

    public QuoteSetupController(QuoteSetupValidationService quoteSetupValidationService) {
        this.quoteSetupValidationService = quoteSetupValidationService;
    }

    @GetMapping
    public QuoteSetupStatus getStatus() {
        return quoteSetupValidationService.getStatus(SecurityUtils.currentUserId());
    }
}

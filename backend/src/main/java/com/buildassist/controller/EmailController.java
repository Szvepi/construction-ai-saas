package com.buildassist.controller;

import com.buildassist.dto.EmailDtos;
import com.buildassist.dto.EmailDtos.BackfillResponse;
import com.buildassist.dto.EmailDtos.EmailDetailResponse;
import com.buildassist.dto.EmailDtos.EmailSearchResponse;
import com.buildassist.dto.EmailDtos.EmailSummaryResponse;
import com.buildassist.dto.EmailDtos.RefreshEmailsResponse;
import com.buildassist.model.EmailCategory;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.EmailService;
import com.buildassist.service.GmailInboxSyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Emails", description = "Inbox list, detail, and manual refresh")
@RestController
@RequestMapping("/api/emails")
public class EmailController {

    private final EmailService emailService;
    private final GmailInboxSyncService gmailInboxSyncService;

    public EmailController(EmailService emailService, GmailInboxSyncService gmailInboxSyncService) {
        this.emailService = emailService;
        this.gmailInboxSyncService = gmailInboxSyncService;
    }

    @GetMapping
    public List<EmailSummaryResponse> list() {
        return emailService.listEmails(SecurityUtils.currentUserId());
    }

    @GetMapping("/search")
    public EmailSearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) EmailCategory category,
            @RequestParam(required = false) String draftStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "desc") String direction) {
        return emailService.searchEmails(
                SecurityUtils.currentUserId(), q, category, draftStatus, page, size, direction);
    }

    @GetMapping("/{id}")
    public EmailDetailResponse get(@PathVariable Long id) {
        return emailService.getEmail(SecurityUtils.currentUserId(), id);
    }

    @PostMapping("/refresh")
    public RefreshEmailsResponse refresh() {
        return gmailInboxSyncService.refresh(SecurityUtils.currentUserId());
    }

    @PostMapping("/backfill")
    public BackfillResponse backfill() {
        return gmailInboxSyncService.backfill(SecurityUtils.currentUserId());
    }

    @PatchMapping("/{id}/category")
    public EmailDtos.UpdateCategoryResponse updateCategory(
            @PathVariable Long id,
            @RequestBody EmailDtos.UpdateCategoryRequest request) {
        return emailService.updateCategory(SecurityUtils.currentUserId(), id, request.category());
    }
}

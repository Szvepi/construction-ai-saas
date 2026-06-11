package com.buildassist.controller;

import com.buildassist.dto.DraftDtos.GenerateDraftResponse;
import com.buildassist.dto.DraftDtos.SendDraftRequest;
import com.buildassist.dto.DraftDtos.SendDraftResponse;
import com.buildassist.dto.DraftDtos.UpdateDraftRequest;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.DraftService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Drafts", description = "AI reply generation and send")
@RestController
@RequestMapping("/api/emails/{emailId}/drafts")
public class DraftController {

    private final DraftService draftService;

    public DraftController(DraftService draftService) {
        this.draftService = draftService;
    }

    @PostMapping("/generate")
    public GenerateDraftResponse generate(@PathVariable Long emailId) {
        return draftService.generateDraft(SecurityUtils.currentUserId(), emailId);
    }

    @PutMapping("/{draftId}")
    public void update(
            @PathVariable Long emailId,
            @PathVariable Long draftId,
            @Valid @RequestBody UpdateDraftRequest request) {
        draftService.updateDraft(
            SecurityUtils.currentUserId(), emailId, draftId, request);
    }

    @PostMapping("/{draftId}/send")
    public SendDraftResponse send(
            @PathVariable Long emailId,
            @PathVariable Long draftId,
            @Valid @RequestBody SendDraftRequest request) {
        return draftService.sendDraft(
            SecurityUtils.currentUserId(), emailId, draftId, request.draftBody());
    }
}

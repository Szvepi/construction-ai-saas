package com.buildassist.controller;

import com.buildassist.dto.DraftDtos.SendDraftRequest;
import com.buildassist.dto.DraftDtos.SendDraftResponse;
import com.buildassist.dto.DraftDtos.UpdateDraftRequest;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.AiDraftService;
import com.buildassist.service.DraftService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Drafts", description = "AI reply generation and send")
@RestController
@RequestMapping("/api/emails/{emailId}/drafts")
public class DraftController {

    private final DraftService draftService;

    private final AiDraftService aiDraftService;

    public DraftController(DraftService draftService, AiDraftService aiDraftService) {
        this.draftService = draftService;
        this.aiDraftService = aiDraftService;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@PathVariable Long emailId) {
        aiDraftService.generateAndSaveDraft(emailId, SecurityUtils.currentUserId());
        return ResponseEntity.ok().build();
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

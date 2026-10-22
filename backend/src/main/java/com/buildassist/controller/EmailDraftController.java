package com.buildassist.controller;

import com.buildassist.dto.DraftDtos;
import com.buildassist.security.SecurityUtils;
import com.buildassist.service.AiDraftService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Email Drafts", description = "Two-phase draft analysis and finalization")
@RestController
@RequestMapping("/api/drafts")
public class EmailDraftController {

    private final AiDraftService aiDraftService;

    public EmailDraftController(AiDraftService aiDraftService) {
        this.aiDraftService = aiDraftService;
    }

    @PostMapping("/analyze/{emailId}")
    public ResponseEntity<DraftDtos.AnalysisResponse> analyze(
            @PathVariable Long emailId,
            @RequestBody(required = false) DraftDtos.AnalyzeRequest request) {
        DraftDtos.AnalysisResponse response = aiDraftService.analyzeEmail(
                emailId,
                SecurityUtils.currentUserId(),
                request != null ? request.additionalNotes() : null);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{draftId}/save")
    public ResponseEntity<DraftDtos.GenerateDraftResponse> saveDraft(
            @PathVariable Long draftId,
            @Valid @RequestBody DraftDtos.SaveDraftRequest request) {
        DraftDtos.GenerateDraftResponse response = aiDraftService.saveDraft(draftId, request, SecurityUtils.currentUserId());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{draftId}/preview")
    public ResponseEntity<DraftDtos.GenerateDraftResponse> previewDraft(
            @PathVariable Long draftId,
            @Valid @RequestBody DraftDtos.FinalizeDraftRequest request) {
        DraftDtos.GenerateDraftResponse response = aiDraftService.previewDraft(draftId, request, SecurityUtils.currentUserId());
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/{draftId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> previewPdf(@PathVariable Long draftId) {
        byte[] pdf = aiDraftService.generatePdf(draftId, SecurityUtils.currentUserId());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"arajanlat-" + draftId + ".pdf\"")
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdf);
    }

    @PostMapping("/{draftId}/finalize")
    public ResponseEntity<DraftDtos.GenerateDraftResponse> finalizeDraft(
            @PathVariable Long draftId,
            @Valid @RequestBody DraftDtos.FinalizeDraftRequest request) {
        DraftDtos.GenerateDraftResponse response = aiDraftService.finalizeDraft(draftId, request, SecurityUtils.currentUserId());
        return ResponseEntity.ok(response);
    }
}

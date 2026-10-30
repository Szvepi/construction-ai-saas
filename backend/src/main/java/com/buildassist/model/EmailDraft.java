package com.buildassist.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "email_drafts")
public class EmailDraft {

    public enum DraftStatus {
        DRAFT,
        PENDING_REVIEW,
        READY,
        SENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "draft_version", nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private Email email;

    @Column(name = "draft_body", nullable = false, columnDefinition = "TEXT")
    private String draftBody;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "additional_notes", columnDefinition = "TEXT")
    private String additionalNotes;

    @Column(name = "include_additional_notes", nullable = false)
    private boolean includeAdditionalNotes = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftStatus status = DraftStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "line_items", columnDefinition = "TEXT")
    private String lineItemsJson;

    @Column(name = "review_warnings", columnDefinition = "TEXT")
    private String reviewWarningsJson;

    @Column(name = "unmapped_requests", columnDefinition = "TEXT")
    private String unmappedRequestsJson;

    @Column(name = "requested_work_items", columnDefinition = "TEXT")
    private String requestedWorkItemsJson;

    @Column(name = "coverage_decisions", columnDefinition = "TEXT")
    private String coverageDecisionsJson;

    @Column(name = "acknowledged_warnings", columnDefinition = "TEXT")
    private String acknowledgedWarningsJson;

    @Column(name = "analysis_fingerprint", length = 64)
    private String analysisFingerprint;

    @Column(name = "quote_snapshot", columnDefinition = "TEXT")
    private String quoteSnapshotJson;
}

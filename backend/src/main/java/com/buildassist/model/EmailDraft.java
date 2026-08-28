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
        SENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private Email email;

    @Column(name = "draft_body", nullable = false, columnDefinition = "TEXT")
    private String draftBody;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DraftStatus status = DraftStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "unmapped_requests", columnDefinition = "TEXT")
    private String unmappedRequestsJson;

    public java.util.List<String> getUnmappedRequests() {
        if (this.unmappedRequestsJson == null || this.unmappedRequestsJson.isBlank()) {
            return java.util.List.of();
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(this.unmappedRequestsJson, mapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class));
        } catch (Exception ex) {
            return java.util.List.of();
        }
    }
}


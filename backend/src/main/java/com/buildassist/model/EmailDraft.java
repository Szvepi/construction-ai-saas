package com.buildassist.model;

import com.buildassist.dto.AiDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.List;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "email_id", nullable = false)
    private Email email;

    @Column(name = "draft_body", nullable = false, columnDefinition = "TEXT")
    private String draftBody;

    @Column(name = "client_name")
    private String clientName;

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

    public List<AiDtos.DraftLineItem> getLineItems() {
        if (this.lineItemsJson == null || this.lineItemsJson.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(this.lineItemsJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, AiDtos.DraftLineItem.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    public List<String> getReviewWarnings() {
        if (this.reviewWarningsJson == null || this.reviewWarningsJson.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(this.reviewWarningsJson, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    public List<String> getUnmappedRequests() {
        if (this.unmappedRequestsJson == null || this.unmappedRequestsJson.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(this.unmappedRequestsJson, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }
}


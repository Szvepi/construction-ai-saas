package com.buildassist.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "emails")
@Data
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gmail_connection_id", nullable = false)
    private GmailConnection gmailConnection;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    @Column(length = 500)
    private String subject;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;

    @Column(name = "is_replied", nullable = false)
    private boolean replied;

    // Gmail befogadás időpontja.
    @Column(name = "email_received_at", nullable = false)
    private OffsetDateTime emailReceivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 50)
    private EmailCategory category = EmailCategory.OTHER;

    @Column(name = "classification_metadata", columnDefinition = "TEXT")
    private String classificationMetadata;

    @Column(name = "classification_status", nullable = false, length = 20)
    private String classificationStatus = "LEGACY";

    @Column(name = "classification_source", nullable = false, length = 20)
    private String classificationSource = "LEGACY";

    @Column(name = "classification_version", length = 32)
    private String classificationVersion;

    @Column(name = "classification_reason", length = 500)
    private String classificationReason;

    @Column(name = "classification_evidence", length = 500)
    private String classificationEvidence;

    @Column(name = "classification_attempts", nullable = false)
    private int classificationAttempts;

    @Column(name = "classification_retry_at")
    private java.time.Instant classificationRetryAt;

    @Column(name = "classification_last_attempt_at")
    private java.time.Instant classificationLastAttemptAt;

    @Column(name = "classification_queue_type", nullable = false, length = 24)
    private String classificationQueueType = "HISTORICAL";

    @Column(name = "classification_origin", nullable = false, length = 24)
    private String classificationOrigin = "HISTORICAL";

    @Column(name = "classification_rule_id", length = 64)
    private String classificationRuleId;

    @Column(name = "classification_queued_at", nullable = false)
    private java.time.Instant classificationQueuedAt;

    @Column(name = "classification_started_at")
    private java.time.Instant classificationStartedAt;

    @Column(name = "classification_completed_at")
    private java.time.Instant classificationCompletedAt;

    @Column(name = "classification_active_attempt_id")
    private Long classificationActiveAttemptId;

    @PrePersist
    private void initializeClassificationQueuedAt() {
        if (classificationQueuedAt == null) {
            classificationQueuedAt = syncedAt != null
                    ? syncedAt.toInstant()
                    : emailReceivedAt != null ? emailReceivedAt.toInstant() : java.time.Instant.now();
        }
    }
}

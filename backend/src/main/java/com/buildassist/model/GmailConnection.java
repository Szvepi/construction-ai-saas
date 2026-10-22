package com.buildassist.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "gmail_connections")
public class GmailConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "gmail_address", nullable = false)
    private String gmailAddress;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String refreshTokenEncrypted;

    @Column(name = "token_expires_at", nullable = false)
    private Instant tokenExpiresAt;

    @Column(name = "backfill_page_token", columnDefinition = "TEXT")
    private String backfillPageToken;

    @Column(name = "backfill_query", columnDefinition = "TEXT")
    private String backfillQuery;

    @Column(name = "backfill_completed", nullable = false)
    private boolean backfillCompleted;

    @Column(name = "backfill_lock_id")
    private java.util.UUID backfillLockId;

    @Column(name = "backfill_lock_until")
    private Instant backfillLockUntil;

    @Column(name = "backfill_cutoff")
    private Instant backfillCutoff;

    @Column(name = "backfill_policy_months")
    private Integer backfillPolicyMonths;

    @Column(name = "history_sync_cursor", columnDefinition = "TEXT")
    private String historySyncCursor;

    @Column(name = "history_sync_phase", length = 32)
    private String historySyncPhase;

    @Column(name = "history_sync_baseline_id", columnDefinition = "TEXT")
    private String historySyncBaselineId;

    @Column(name = "history_sync_cutoff")
    private Instant historySyncCutoff;

    @Column(name = "history_sync_policy_months")
    private Integer historySyncPolicyMonths;

    @Column(name = "history_sync_query", columnDefinition = "TEXT")
    private String historySyncQuery;

    @Column(name = "history_sync_page_token", columnDefinition = "TEXT")
    private String historySyncPageToken;

    @Column(name = "history_sync_pending_page_token", columnDefinition = "TEXT")
    private String historySyncPendingPageToken;

    @Column(name = "history_sync_pending_history_id", columnDefinition = "TEXT")
    private String historySyncPendingHistoryId;

    @Column(name = "history_sync_message_ids", columnDefinition = "TEXT")
    private String historySyncMessageIds;

    @Column(name = "history_sync_message_offset", nullable = false)
    private int historySyncMessageOffset;

    @Column(name = "history_sync_lock_id")
    private java.util.UUID historySyncLockId;

    @Column(name = "history_sync_lock_until")
    private Instant historySyncLockUntil;

}

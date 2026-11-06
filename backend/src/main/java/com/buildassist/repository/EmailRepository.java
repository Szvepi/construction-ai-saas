package com.buildassist.repository;

import com.buildassist.model.Email;
import java.time.Instant;
import java.time.OffsetDateTime;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {

    interface SearchRow {
        Long getId();
        String getSubject();
        String getFromAddress();
        Instant getReceivedAt();
        Boolean getReplied();
        String getCategory();
        String getDraftStatus();
        String getClassificationStatus();
        String getClassificationReason();
        String getClassificationEvidence();
    }

    interface CategoryCount {
        String getCategory();
        Long getCount();
    }

    List<Email> findByGmailConnectionIdOrderByEmailReceivedAtDesc(Long gmailConnectionId);

    Optional<Email> findByIdAndGmailConnectionUserId(Long id, Long userId);

    List<Email> findByGmailConnectionId(Long gmailConnectionId);

    boolean existsByGmailConnectionIdAndGmailMessageId(Long gmailConnectionId, String gmailMessageId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO emails (
                gmail_connection_id, gmail_message_id, subject, from_address, body_text,
                email_received_at, synced_at, is_replied, category, classification_metadata,
                classification_status, classification_source, classification_retry_at
            ) VALUES (
                :gmailConnectionId, :gmailMessageId, :subject, :fromAddress, :bodyText,
                :receivedAt, NOW(), FALSE, :category, :classificationMetadata,
                'PENDING', 'PENDING', NOW()
            )
            ON CONFLICT (gmail_connection_id, gmail_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("gmailMessageId") String gmailMessageId,
            @Param("subject") String subject,
            @Param("fromAddress") String fromAddress,
            @Param("bodyText") String bodyText,
            @Param("receivedAt") OffsetDateTime receivedAt,
            @Param("category") String category,
            @Param("classificationMetadata") String classificationMetadata);

    @Query(value = """
            SELECT * FROM emails
            WHERE classification_source <> 'MANUAL'
              AND (
                    (classification_status IN ('PENDING', 'RETRY')
                     AND (classification_retry_at IS NULL OR classification_retry_at <= :now))
                    OR (classification_status = 'PROCESSING'
                        AND classification_last_attempt_at <= :staleBefore)
              )
            ORDER BY email_received_at ASC, id ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Email> lockNextClassificationDue(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT email FROM Email email
            WHERE email.gmailConnection.id = :gmailConnectionId
              AND email.classificationSource <> 'MANUAL'
              AND email.classificationStatus IN ('LEGACY', 'CLASSIFIED', 'REVIEW', 'FAILED')
              AND (
                    email.classificationStatus = 'LEGACY'
                    OR email.classificationVersion IS NULL
                    OR email.classificationVersion <> :classificationVersion
              )
            ORDER BY email.emailReceivedAt ASC, email.id ASC
            """)
    List<Email> findReclassificationCandidates(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("classificationVersion") String classificationVersion,
            org.springframework.data.domain.Pageable pageable);

    String SEARCH_FROM_WHERE = """
            FROM emails e
            LEFT JOIN LATERAL (
                SELECT d.status
                FROM email_drafts d
                WHERE d.email_id = e.id
                ORDER BY d.created_at DESC, d.id DESC
                LIMIT 1
            ) latest ON TRUE
            WHERE e.gmail_connection_id = :gmailConnectionId
              AND (CAST(:searchPattern AS VARCHAR) IS NULL
                   OR LOWER(e.subject) LIKE :searchPattern ESCAPE '!'
                   OR LOWER(e.from_address) LIKE :searchPattern ESCAPE '!')
              AND (CAST(:category AS VARCHAR) IS NULL OR e.category = :category)
              AND (
                  CAST(:draftStatus AS VARCHAR) IS NULL
                  OR (
                      CAST(:draftStatus AS VARCHAR) = 'REVIEW'
                      AND e.category = 'QUOTE_REQUEST'
                      AND latest.status IN ('DRAFT', 'PENDING_REVIEW')
                  )
                  OR (
                      CAST(:draftStatus AS VARCHAR) <> 'REVIEW'
                      AND latest.status = :draftStatus
                  )
              )
            """;

    String SEARCH_SELECT = """
            SELECT e.id AS "id",
                   e.subject AS "subject",
                   e.from_address AS "fromAddress",
                   e.email_received_at AS "receivedAt",
                   e.is_replied AS "replied",
                   e.category AS "category",
                   latest.status AS "draftStatus",
                   e.classification_status AS "classificationStatus",
                   e.classification_reason AS "classificationReason",
                   e.classification_evidence AS "classificationEvidence"
            """ + SEARCH_FROM_WHERE;

    String SEARCH_COUNT = "SELECT COUNT(*)\n" + SEARCH_FROM_WHERE;

    @Query(value = SEARCH_SELECT + " ORDER BY e.email_received_at DESC, e.id DESC",
            countQuery = SEARCH_COUNT,
            nativeQuery = true)
    Page<SearchRow> searchEmailsDescending(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("searchPattern") String searchPattern,
            @Param("category") String category,
            @Param("draftStatus") String draftStatus,
            Pageable pageable);

    @Query(value = SEARCH_SELECT + " ORDER BY e.email_received_at ASC, e.id ASC",
            countQuery = SEARCH_COUNT,
            nativeQuery = true)
    Page<SearchRow> searchEmailsAscending(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("searchPattern") String searchPattern,
            @Param("category") String category,
            @Param("draftStatus") String draftStatus,
            Pageable pageable);

    @Query(value = """
            SELECT e.category AS "category", COUNT(*) AS "count"
            FROM emails e
            WHERE e.gmail_connection_id = :gmailConnectionId
              AND (CAST(:searchPattern AS VARCHAR) IS NULL
                   OR LOWER(e.subject) LIKE :searchPattern ESCAPE '!'
                   OR LOWER(e.from_address) LIKE :searchPattern ESCAPE '!')
            GROUP BY e.category
            """,
            nativeQuery = true)
    List<CategoryCount> countSearchEmailsByCategory(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("searchPattern") String searchPattern);

    @Query(value = """
            SELECT COUNT(*)
            FROM emails e
            LEFT JOIN LATERAL (
                SELECT d.status
                FROM email_drafts d
                WHERE d.email_id = e.id
                ORDER BY d.created_at DESC, d.id DESC
                LIMIT 1
            ) latest ON TRUE
            WHERE e.gmail_connection_id = :gmailConnectionId
              AND (CAST(:searchPattern AS VARCHAR) IS NULL
                   OR LOWER(e.subject) LIKE :searchPattern ESCAPE '!'
                   OR LOWER(e.from_address) LIKE :searchPattern ESCAPE '!')
              AND e.category = 'QUOTE_REQUEST'
              AND latest.status IN ('DRAFT', 'PENDING_REVIEW')
            """,
            nativeQuery = true)
    long countReviewEmails(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("searchPattern") String searchPattern);
}

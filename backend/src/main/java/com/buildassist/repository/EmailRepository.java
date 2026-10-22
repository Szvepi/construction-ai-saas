package com.buildassist.repository;

import com.buildassist.model.Email;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {

    interface SearchRow {
        Long getId();
        String getSubject();
        String getFromAddress();
        OffsetDateTime getReceivedAt();
        Boolean getReplied();
        String getCategory();
        String getDraftStatus();
    }

    interface CategoryCount {
        String getCategory();
        Long getCount();
    }

    List<Email> findByGmailConnectionIdOrderByEmailReceivedAtDesc(Long gmailConnectionId);

    Optional<Email> findByIdAndGmailConnectionUserId(Long id, Long userId);

    List<Email> findByGmailConnectionId(Long gmailConnectionId);

    boolean existsByGmailConnectionIdAndGmailMessageId(Long gmailConnectionId, String gmailMessageId);

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
              AND (CAST(:draftStatus AS VARCHAR) IS NULL OR latest.status = :draftStatus)
            """;

    String SEARCH_SELECT = """
            SELECT e.id AS "id",
                   e.subject AS "subject",
                   e.from_address AS "fromAddress",
                   e.email_received_at AS "receivedAt",
                   e.is_replied AS "replied",
                   e.category AS "category",
                   latest.status AS "draftStatus"
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
              AND (CAST(:draftStatus AS VARCHAR) IS NULL OR latest.status = :draftStatus)
            GROUP BY e.category
            """,
            nativeQuery = true)
    List<CategoryCount> countSearchEmailsByCategory(
            @Param("gmailConnectionId") Long gmailConnectionId,
            @Param("searchPattern") String searchPattern,
            @Param("draftStatus") String draftStatus);
}

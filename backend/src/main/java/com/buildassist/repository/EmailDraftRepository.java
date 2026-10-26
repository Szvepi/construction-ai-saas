package com.buildassist.repository;

import com.buildassist.model.EmailDraft;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDraftRepository extends JpaRepository<EmailDraft, Long> {

    Optional<EmailDraft> findTopByEmailIdOrderByCreatedAtDesc(Long emailId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select draft from EmailDraft draft where draft.id = :draftId")
    Optional<EmailDraft> findByIdForUpdate(@Param("draftId") Long draftId);
}

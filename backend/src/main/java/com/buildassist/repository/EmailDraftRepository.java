package com.buildassist.repository;

import com.buildassist.model.EmailDraft;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailDraftRepository extends JpaRepository<EmailDraft, Long> {

    Optional<EmailDraft> findTopByEmailIdOrderByCreatedAtDesc(Long emailId);
}

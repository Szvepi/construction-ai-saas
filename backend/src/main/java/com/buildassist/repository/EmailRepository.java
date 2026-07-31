package com.buildassist.repository;

import com.buildassist.model.Email;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {

    List<Email> findByGmailConnectionIdOrderByReceivedAtDesc(Long gmailConnectionId);

    Optional<Email> findByIdAndGmailConnectionUserId(Long id, Long userId);

    List<Email> findByGmailConnectionId(Long gmailConnectionId);

    boolean existsByGmailConnectionIdAndGmailMessageId(Long gmailConnectionId, String gmailMessageId);
}


package com.buildassist.repository;

import com.buildassist.model.Email;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailRepository extends JpaRepository<Email, Long> {

    List<Email> findByGmailConnectionIdOrderByReceivedAtDesc(Long gmailConnectionId);

    Optional<Email> findByIdAndGmailConnectionUserId(Long id, Long userId);
}

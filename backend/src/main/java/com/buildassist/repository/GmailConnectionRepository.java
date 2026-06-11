package com.buildassist.repository;

import com.buildassist.model.GmailConnection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GmailConnectionRepository extends JpaRepository<GmailConnection, Long> {

    Optional<GmailConnection> findByUserId(Long userId);
}

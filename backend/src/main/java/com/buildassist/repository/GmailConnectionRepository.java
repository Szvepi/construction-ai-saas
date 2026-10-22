package com.buildassist.repository;

import com.buildassist.model.GmailConnection;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GmailConnectionRepository extends JpaRepository<GmailConnection, Long> {

    Optional<GmailConnection> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT connection FROM GmailConnection connection WHERE connection.user.id = :userId")
    Optional<GmailConnection> findByUserIdForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT connection FROM GmailConnection connection WHERE connection.id = :connectionId")
    Optional<GmailConnection> findByIdForUpdate(@Param("connectionId") Long connectionId);
}

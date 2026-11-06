package com.buildassist.repository;

import com.buildassist.model.GmailConnection;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface GmailConnectionRepository extends JpaRepository<GmailConnection, Long> {

    Optional<GmailConnection> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT connection FROM GmailConnection connection WHERE connection.user.id = :userId")
    Optional<GmailConnection> findByUserIdForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT connection FROM GmailConnection connection WHERE connection.id = :connectionId")
    Optional<GmailConnection> findByIdForUpdate(@Param("connectionId") Long connectionId);

    @Query("SELECT connection.user.id FROM GmailConnection connection "
            + "WHERE connection.gmailSyncAuthRequired = false "
            + "AND connection.user.active = true "
            + "AND (connection.gmailSyncRetryAt IS NULL OR connection.gmailSyncRetryAt <= :now) "
            + "AND (connection.historySyncLockUntil IS NULL OR connection.historySyncLockUntil <= :now) "
            + "ORDER BY connection.id")
    List<Long> findUserIdsDueForGmailSync(@Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE GmailConnection connection SET connection.accessTokenEncrypted = :token, "
            + "connection.tokenExpiresAt = :expiresAt WHERE connection.id = :connectionId")
    int updateAccessToken(
            @Param("connectionId") Long connectionId,
            @Param("token") String token,
            @Param("expiresAt") Instant expiresAt);
}

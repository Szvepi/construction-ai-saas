package com.buildassist.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;

@Entity
@Table(name = "emails")
@Data
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gmail_connection_id", nullable = false)
    private GmailConnection gmailConnection;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    private String subject;

    @Column(name = "from_address")
    private String fromAddress;

    @Column(name = "body_text", columnDefinition = "TEXT")
    private String bodyText;

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "is_replied", nullable = false)
    private boolean replied;

    // Gmail befogadás időpontja.
    @Column(name = "email_received_at", nullable = false)
    private OffsetDateTime emailReceivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private EmailCategory category = EmailCategory.OTHER;

}

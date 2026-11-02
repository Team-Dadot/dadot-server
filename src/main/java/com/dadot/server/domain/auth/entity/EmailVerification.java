package com.dadot.server.domain.auth.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {

    @Id
    @Column(length = 36, columnDefinition = "char(36)")
    private String id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VerificationPurpose purpose;

    @Column(
            name = "code_digest",
            nullable = false,
            columnDefinition = "char(64)"
    )
    private String codeDigest;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(
            name = "expires_at",
            nullable = false,
            columnDefinition = "datetime(6)"
    )
    private LocalDateTime expiresAt;

    @Column(name = "verified_at", columnDefinition = "datetime(6)")
    private LocalDateTime verifiedAt;

    @Column(name = "invalidated_at", columnDefinition = "datetime(6)")
    private LocalDateTime invalidatedAt;

    @Column(
            name = "sent_at",
            nullable = false,
            columnDefinition = "datetime(6)"
    )
    private LocalDateTime sentAt;

    public static EmailVerification issue(
            String id,
            Long userId,
            String email,
            VerificationPurpose purpose,
            String digest,
            LocalDateTime now
    ) {
        EmailVerification result = new EmailVerification();

        result.id = id;
        result.userId = userId;
        result.email = email;
        result.purpose = purpose;
        result.codeDigest = digest;
        result.sentAt = now;
        result.expiresAt = now.plusMinutes(10);

        return result;
    }
}
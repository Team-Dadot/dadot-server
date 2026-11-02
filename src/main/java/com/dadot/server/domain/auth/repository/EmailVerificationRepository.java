package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.entity.EmailVerification;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationRepository
        extends JpaRepository<EmailVerification, String> {

    Optional<EmailVerification> findFirstByEmailAndPurposeOrderBySentAtDesc(
            String email,
            VerificationPurpose purpose
    );

    long countByEmailAndPurposeAndSentAtGreaterThan(
            String email,
            VerificationPurpose purpose,
            LocalDateTime since
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailVerification e
            set e.invalidatedAt = :now
            where e.email = :email
              and e.purpose = :purpose
              and e.invalidatedAt is null
            """)
    int invalidatePrevious(
            @Param("email") String email,
            @Param("purpose") VerificationPurpose purpose,
            @Param("now") LocalDateTime now
    );
}
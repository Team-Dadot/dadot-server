package com.dadot.server.domain.appconfig.service;

import com.dadot.server.domain.appconfig.dto.AppConfigResponse;
import com.dadot.server.domain.appconfig.dto.AppConfigResponse.SignupTerm;
import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class SignupTermsService {

    // SHA-256("dadot:signup-terms:v1")
    // 약관 게시 트랜잭션도 같은 행을 잠가야 한다.
    private static final String LOCK_HASH =
            "db99e235c84abf94e7c3d11b65b25a90606d982cb8dce3ac9eee3e920c18d969";

    private final JdbcTemplate jdbc;
    private final AuthRequestGuardRepository guards;
    private final Clock clock;
    private final String privacyUrl;
    private final String inquiryUrl;

    public SignupTermsService(
            JdbcTemplate jdbc,
            AuthRequestGuardRepository guards,
            Clock clock,
            @Value("${app.links.privacy-policy-url}")
            String privacyUrl,
            @Value("${app.links.inquiry-form-url}")
            String inquiryUrl
    ) {
        this.jdbc = jdbc;
        this.guards = guards;
        this.clock = clock;
        this.privacyUrl = requireHttps(privacyUrl);
        this.inquiryUrl = requireHttps(inquiryUrl);
    }

    public AppConfigResponse config() {
        try {
            var current = current(clock.instant());

            return new AppConfigResponse(
                    privacyUrl,
                    current.isEmpty() ? null : current.getFirst().url(),
                    inquiryUrl,
                    !current.isEmpty(),
                    current
            );
        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    @Transactional(
            propagation = Propagation.MANDATORY,
            noRollbackFor = BusinessException.class
    )
    public AcceptedTerms validateAndLock(List<Long> acceptedIds) {
        guards.lock(
                LOCK_HASH,
                clock.instant().plus(Duration.ofDays(2))
        );

        Instant acceptedAt = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        var terms = current(acceptedAt);

        if (terms.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.SIGNUP_TERMS_UNAVAILABLE
            );
        }

        if (acceptedIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.TERMS_NOT_ACCEPTED
            );
        }

        if (acceptedIds.size() != 1
                || acceptedIds.getFirst() != terms.getFirst().termsId()) {
            throw new BusinessException(
                    ErrorCode.TERMS_CHANGED
            );
        }

        return new AcceptedTerms(
                terms.getFirst().termsId(),
                acceptedAt
        );
    }

    private List<SignupTerm> current(Instant now) {
        return jdbc.query(
                """
                select
                    id,
                    code,
                    version,
                    title,
                    content_url,
                    is_required,
                    effective_at
                from service_terms
                where code = 'SERVICE_TERMS'
                  and published_at <= ?
                  and effective_at <= ?
                order by effective_at desc
                limit 1
                """,
                (rs, row) -> new SignupTerm(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("version"),
                        rs.getString("title"),
                        requireHttps(rs.getString("content_url")),
                        rs.getBoolean("is_required"),
                        rs.getObject(
                                "effective_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC)
                ),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        );
    }

    private static String requireHttps(String value) {
        URI uri = URI.create(value);

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null) {
            throw new IllegalStateException(
                    "App links must be absolute HTTPS URLs"
            );
        }

        return value;
    }

    public record AcceptedTerms(
            long termsId,
            Instant acceptedAt
    ) {
    }
}
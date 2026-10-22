-- Dadot: 회원, 인증, 가입 약관
-- 모든 DATETIME 값은 애플리케이션에서 UTC로 저장한다.
-- AI 작업 관련 테이블과 연결은 해당 기능의 후속 migration에서 추가한다.


-- 1. 회원
CREATE TABLE users (
                       id BIGINT NOT NULL AUTO_INCREMENT,
                       nickname VARCHAR(20) NOT NULL,
                       profile_image_key VARCHAR(512) NULL,

                       role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER',
                       status ENUM('ACTIVE', 'DELETING') NOT NULL DEFAULT 'ACTIVE',

                       auth_version INT NOT NULL DEFAULT 0,
                       personalization_version BIGINT NOT NULL DEFAULT 0,

                       created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                       updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                       PRIMARY KEY (id),

                       CONSTRAINT ck_users_versions CHECK (
                           auth_version >= 0
                               AND personalization_version >= 0
                               AND CHAR_LENGTH(TRIM(nickname)) BETWEEN 1 AND 20
                           )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 2. 로그인 수단
-- EMAIL: provider_subject에 정규화한 이메일 저장
-- KAKAO / APPLE: 제공자가 검증한 고유 식별자 저장
CREATE TABLE auth_identities (
                                 id BIGINT NOT NULL AUTO_INCREMENT,
                                 user_id BIGINT NOT NULL,

                                 provider ENUM('EMAIL', 'KAKAO', 'APPLE') NOT NULL,
                                 provider_subject VARCHAR(255) NOT NULL,

                                 email VARCHAR(254) NULL,
                                 password_hash VARCHAR(255) NULL,

                                 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                 PRIMARY KEY (id),

                                 CONSTRAINT uk_identity_provider_subject
                                     UNIQUE (provider, provider_subject),

                                 CONSTRAINT uk_identity_user_provider
                                     UNIQUE (user_id, provider),

                                 CONSTRAINT ck_identity_password CHECK (
                                     (
                                         provider = 'EMAIL'
                                             AND email IS NOT NULL
                                             AND password_hash IS NOT NULL
                                         )
                                         OR
                                     (
                                         provider <> 'EMAIL'
                                             AND password_hash IS NULL
                                         )
                                     ),

                                 CONSTRAINT fk_identity_user
                                     FOREIGN KEY (user_id)
                                         REFERENCES users (id)
                                         ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 3. 로그인 세션
-- JWT 원문은 저장하지 않는다.
CREATE TABLE auth_sessions (
                               id CHAR(36) NOT NULL,
                               user_id BIGINT NOT NULL,

                               created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                               expires_at DATETIME(6) NOT NULL,
                               revoked_at DATETIME(6) NULL,

                               PRIMARY KEY (id),

                               CONSTRAINT uk_session_id_user
                                   UNIQUE (id, user_id),

                               INDEX idx_session_user_state (
        user_id,
        revoked_at,
        expires_at
    ),

                               CONSTRAINT ck_session_expiry CHECK (
                                   expires_at > created_at
                                   ),

                               CONSTRAINT fk_session_user
                                   FOREIGN KEY (user_id)
                                       REFERENCES users (id)
                                       ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 4. 이메일 인증번호 발급 이력
-- code_digest: 인증번호의 서버 secret 기반 HMAC
-- 가입 전에는 user_id가 NULL이다.
CREATE TABLE email_verifications (
                                     id CHAR(36) NOT NULL,
                                     user_id BIGINT NULL,

                                     email VARCHAR(254) NOT NULL,

                                     purpose ENUM(
        'SIGN_UP',
        'PASSWORD_RESET',
        'PASSWORD_CHANGE'
    ) NOT NULL,

                                     code_digest CHAR(64) NOT NULL,
                                     failed_attempts INT NOT NULL DEFAULT 0,

                                     expires_at DATETIME(6) NOT NULL,
                                     verified_at DATETIME(6) NULL,
                                     invalidated_at DATETIME(6) NULL,
                                     sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                     PRIMARY KEY (id),

                                     CONSTRAINT uk_verification_id_purpose_email
                                         UNIQUE (id, purpose, email),

                                     CONSTRAINT uk_verification_id_user
                                         UNIQUE (id, user_id),

                                     INDEX idx_verification_email_purpose_sent (
        email,
        purpose,
        sent_at
    ),

                                     INDEX idx_verification_user_purpose_sent (
        user_id,
        purpose,
        sent_at
    ),

                                     CONSTRAINT ck_verification_bounds CHECK (
                                         failed_attempts BETWEEN 0 AND 5
                                             AND expires_at > sent_at
                                         ),

                                     CONSTRAINT fk_verification_user
                                         FOREIGN KEY (user_id)
                                             REFERENCES users (id)
                                             ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 5. 인증 완료 후 발급하는 일회성 증빙
-- 이메일: verificationToken
-- 신규 소셜 가입: registrationToken
-- 토큰 원문 대신 고엔트로피 난수 토큰의 SHA-256 해시를 저장한다.
CREATE TABLE auth_proofs (
                             id CHAR(36) NOT NULL,
                             user_id BIGINT NULL,
                             verification_id CHAR(36) NULL,

                             purpose ENUM(
        'SIGN_UP',
        'PASSWORD_RESET',
        'PASSWORD_CHANGE'
    ) NOT NULL,

                             provider ENUM(
        'EMAIL',
        'KAKAO',
        'APPLE'
    ) NOT NULL DEFAULT 'EMAIL',

                             provider_subject VARCHAR(255) NULL,
                             subject_email VARCHAR(254) NULL,

                             token_hash CHAR(64) NOT NULL,

                             issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                             expires_at DATETIME(6) NOT NULL,
                             consumed_at DATETIME(6) NULL,
                             invalidated_at DATETIME(6) NULL,

                             PRIMARY KEY (id),

                             CONSTRAINT uk_proof_verification
                                 UNIQUE (verification_id),

                             CONSTRAINT uk_proof_token
                                 UNIQUE (token_hash),

                             CONSTRAINT uk_proof_verification_subject
                                 UNIQUE (verification_id, purpose, subject_email),

                             INDEX idx_proof_provider_subject_issued (
        provider,
        provider_subject,
        issued_at
    ),

                             INDEX idx_proof_expires (expires_at),

                             CONSTRAINT ck_proof_expiry CHECK (
                                 expires_at > issued_at
                                 ),

                             CONSTRAINT ck_proof_source CHECK (
                                 (
                                     provider = 'EMAIL'
                                         AND verification_id IS NOT NULL
                                         AND subject_email IS NOT NULL
                                         AND provider_subject IS NULL
                                     )
                                     OR
                                 (
                                     provider IN ('KAKAO', 'APPLE')
                                         AND purpose = 'SIGN_UP'
                                         AND verification_id IS NULL
                                         AND user_id IS NULL
                                         AND provider_subject IS NOT NULL
                                         AND CHAR_LENGTH(TRIM(provider_subject)) > 0
                                     )
                                 ),

                             CONSTRAINT ck_proof_invalidation CHECK (
                                 invalidated_at IS NULL
                                     OR invalidated_at >= issued_at
                                 ),

                             CONSTRAINT ck_proof_subject CHECK (
                                 (purpose = 'SIGN_UP' AND user_id IS NULL)
                                     OR
                                 (purpose <> 'SIGN_UP' AND user_id IS NOT NULL)
                                 ),

                             CONSTRAINT fk_proof_user
                                 FOREIGN KEY (user_id)
                                     REFERENCES users (id)
                                     ON DELETE RESTRICT,

                             CONSTRAINT fk_proof_verification_subject
                                 FOREIGN KEY (
                                              verification_id,
                                              purpose,
                                              subject_email
                                     )
                                     REFERENCES email_verifications (
                                                                     id,
                                                                     purpose,
                                                                     email
                                         )
                                     ON DELETE RESTRICT,

                             CONSTRAINT fk_proof_verification_user
                                 FOREIGN KEY (verification_id, user_id)
                                     REFERENCES email_verifications (id, user_id)
                                     ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 6. 서비스 이용약관 버전
-- published_at이 NULL이면 미게시 초안이다.
-- 게시된 버전은 수정하지 않고 새 버전을 추가한다.
CREATE TABLE service_terms (
                               id BIGINT NOT NULL AUTO_INCREMENT,

                               code VARCHAR(40) NOT NULL DEFAULT 'SERVICE_TERMS',
                               version VARCHAR(40) NOT NULL,
                               title VARCHAR(200) NOT NULL,

                               content_url VARCHAR(2048) NOT NULL,
                               content_hash CHAR(64) NOT NULL,

                               is_required BOOLEAN NOT NULL DEFAULT TRUE,

                               published_at DATETIME(6) NULL,
                               effective_at DATETIME(6) NOT NULL,
                               created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                               PRIMARY KEY (id),

                               CONSTRAINT uk_terms_code_version
                                   UNIQUE (code, version),

                               CONSTRAINT uk_terms_code_effective
                                   UNIQUE (code, effective_at),

                               INDEX idx_terms_active (
        code,
        published_at,
        effective_at
    ),

                               CONSTRAINT ck_terms_scope CHECK (
                                   code = 'SERVICE_TERMS'
                                       AND is_required = 1
                                   ),

                               CONSTRAINT ck_terms_text CHECK (
                                   CHAR_LENGTH(TRIM(version)) BETWEEN 1 AND 40
                                       AND CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 200
                                   ),

                               CONSTRAINT ck_terms_url CHECK (
                                   content_url LIKE 'https://%'
                                   ),

                               CONSTRAINT ck_terms_hash CHECK (
                                   CHAR_LENGTH(content_hash) = 64
                                   ),

                               CONSTRAINT ck_terms_publication CHECK (
                                   published_at IS NULL
                                       OR published_at <= effective_at
                                   )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 7. 회원별 약관 동의 이력
-- agreed_at은 서버가 기록한다.
CREATE TABLE user_agreements (
                                 user_id BIGINT NOT NULL,
                                 terms_id BIGINT NOT NULL,
                                 agreed_at DATETIME(6) NOT NULL,

                                 PRIMARY KEY (user_id, terms_id),

                                 INDEX idx_agreement_terms (terms_id),

                                 CONSTRAINT fk_agreement_user
                                     FOREIGN KEY (user_id)
                                         REFERENCES users (id)
                                         ON DELETE RESTRICT,

                                 CONSTRAINT fk_agreement_terms
                                     FOREIGN KEY (terms_id)
                                         REFERENCES service_terms (id)
                                         ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 8. 온보딩 진행 상태
-- 회원가입 시 초기 행을 생성한다.
CREATE TABLE user_onboarding (
                                 user_id BIGINT NOT NULL,

                                 current_step VARCHAR(40) NOT NULL DEFAULT 'COMPANIES',
                                 draft_payload JSON NULL,

                                 notification_guidance_completed_at DATETIME(6) NULL,
                                 completed_at DATETIME(6) NULL,

                                 version BIGINT NOT NULL DEFAULT 1,
                                 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                 PRIMARY KEY (user_id),

                                 CONSTRAINT ck_onboarding_version CHECK (
                                     version >= 1
                                     ),

                                 CONSTRAINT fk_onboarding_user
                                     FOREIGN KEY (user_id)
                                         REFERENCES users (id)
                                         ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;


-- 9. 중복 요청 방지 및 응답 복구
-- 같은 Idempotency-Key로 재시도해도 회원/세션을 중복 생성하지 않는다.
-- 인증 토큰이 포함된 응답은 encrypted_response에 암호화하여 저장한다.
-- AI 기능 구현 시 task_id와 관련 FK/인덱스/CHECK를 추가한다.
CREATE TABLE api_idempotency_requests (
                                          id BIGINT NOT NULL AUTO_INCREMENT,
                                          user_id BIGINT NULL,

                                          scope_hash CHAR(64) NOT NULL,
                                          operation VARCHAR(150) NOT NULL,
                                          key_hash CHAR(64) NOT NULL,
                                          body_hmac CHAR(64) NOT NULL,

                                          status ENUM(
        'PROCESSING',
        'SUCCEEDED',
        'FAILED'
    ) NOT NULL DEFAULT 'PROCESSING',

                                          response_http_status SMALLINT NULL,
                                          response_payload JSON NULL,
                                          encrypted_response TEXT NULL,
                                          response_key_version VARCHAR(40) NULL,

                                          created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                          expires_at DATETIME(6) NOT NULL,

                                          PRIMARY KEY (id),

                                          CONSTRAINT uk_idempotency_scope_operation_key
                                              UNIQUE (scope_hash, operation, key_hash),

                                          INDEX idx_idempotency_expires (expires_at),

                                          CONSTRAINT ck_idempotency_expiry CHECK (
                                              expires_at > created_at
                                              ),

                                          CONSTRAINT fk_idempotency_user
                                              FOREIGN KEY (user_id)
                                                  REFERENCES users (id)
                                                  ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
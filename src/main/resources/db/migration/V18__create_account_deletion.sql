-- 탈퇴 진행 이력과 응답 재확인은 분리된 수명으로 관리한다.
-- PROCESSING은 만료 시각이 지나도 삭제하지 않는다.
-- 성공 후에만 비밀값·회원 FK를 제거한다.

CREATE TABLE account_deletions (
                                   id CHAR(36) NOT NULL PRIMARY KEY,
                                   user_id BIGINT NULL,

                                   actor_hash CHAR(64) NOT NULL,
                                   key_hash CHAR(64) NOT NULL,
                                   body_hmac CHAR(64) NOT NULL,

                                   status ENUM('PROCESSING', 'SUCCEEDED')
        NOT NULL DEFAULT 'PROCESSING',

                                   provider ENUM('EMAIL', 'KAKAO', 'APPLE') NULL,
                                   encrypted_credentials TEXT NULL,
                                   key_version VARCHAR(40) NULL,
                                   provider_revoked BOOLEAN NOT NULL DEFAULT FALSE,

                                   lease_owner CHAR(36) NULL,
                                   lease_until DATETIME(6) NULL,
                                   lease_version BIGINT NOT NULL DEFAULT 0,

                                   attempts INT NOT NULL DEFAULT 0,
                                   available_at DATETIME(6) NOT NULL,

                                   last_error_code ENUM(
        'PROVIDER_UNAVAILABLE',
        'STORAGE_UNAVAILABLE',
        'INTERNAL_FAILURE'
    ) NULL,

                                   created_at DATETIME(6) NOT NULL,
                                   expires_at DATETIME(6) NOT NULL,
                                   completed_at DATETIME(6) NULL,

                                   UNIQUE KEY uk_deletion_user (user_id),
                                   UNIQUE KEY uk_deletion_actor (actor_hash),

                                   KEY idx_deletion_ready (
        status,
        available_at,
        lease_until
    ),

                                   KEY idx_deletion_expiry (
        status,
        expires_at
    ),

                                   CONSTRAINT fk_deletion_user
                                       FOREIGN KEY (user_id)
                                           REFERENCES users(id)
                                           ON DELETE RESTRICT,

                                   CONSTRAINT ck_deletion_lifetime CHECK (
                                       expires_at > created_at
                                           AND lease_version >= 0
                                           AND attempts >= 0
                                       ),

                                   CONSTRAINT ck_deletion_lease CHECK (
                                       (lease_owner IS NULL AND lease_until IS NULL)
                                           OR
                                       (lease_owner IS NOT NULL AND lease_until IS NOT NULL)
                                       ),

                                   CONSTRAINT ck_deletion_state CHECK (
                                       (
                                           status = 'PROCESSING'
                                               AND user_id IS NOT NULL
                                               AND provider IS NOT NULL
                                               AND encrypted_credentials IS NOT NULL
                                               AND key_version IS NOT NULL
                                               AND completed_at IS NULL
                                           )
                                           OR
                                       (
                                           status = 'SUCCEEDED'
                                               AND user_id IS NULL
                                               AND provider IS NULL
                                               AND encrypted_credentials IS NULL
                                               AND key_version IS NULL
                                               AND completed_at IS NOT NULL
                                               AND lease_owner IS NULL
                                               AND lease_until IS NULL
                                           )
                                       )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_bin;

-- 가입 전 소셜 응답도 제공자 subject의 HMAC으로
-- 정확히 제거할 수 있게 한다.
ALTER TABLE api_idempotency_requests
    ADD COLUMN subject_hash CHAR(64) NULL,
    ADD INDEX idx_idempotency_subject (subject_hash);
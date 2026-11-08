-- Access-token sessions retain their existing one-hour lifetime and JWT contract.
-- Refresh token plaintext is never stored. Retry responses use existing AES-GCM encryption.
CREATE TABLE auth_refresh_families (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    auth_version INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    UNIQUE KEY uk_refresh_family_user (id, user_id),
    KEY idx_refresh_family_expiry (expires_at),
    KEY idx_refresh_family_user (user_id, revoked_at),
    CONSTRAINT fk_refresh_family_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_family_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_family_version CHECK (auth_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE auth_refresh_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    family_id CHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    session_id CHAR(36) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    active_slot TINYINT NULL DEFAULT 1,
    consumed_at DATETIME(6) NULL,
    request_key_hash CHAR(64) NULL,
    replay_response TEXT NULL,
    replay_key_version VARCHAR(20) NULL,
    replay_until DATETIME(6) NULL,
    UNIQUE KEY uk_refresh_active (family_id, active_slot),
    UNIQUE KEY uk_refresh_session (session_id),
    KEY idx_refresh_token_expiry (expires_at),
    CONSTRAINT fk_refresh_token_family FOREIGN KEY (family_id, user_id)
        REFERENCES auth_refresh_families(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (session_id, user_id)
        REFERENCES auth_sessions(id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_token_consumed CHECK (
        (consumed_at IS NULL AND active_slot IS NOT NULL AND active_slot = 1)
        OR (consumed_at IS NOT NULL AND active_slot IS NULL)
    ),
    CONSTRAINT ck_refresh_replay CHECK (
        (request_key_hash IS NULL AND replay_response IS NULL AND replay_key_version IS NULL AND replay_until IS NULL)
        OR (consumed_at IS NOT NULL AND request_key_hash IS NOT NULL AND replay_response IS NOT NULL
            AND replay_key_version IS NOT NULL AND replay_until > consumed_at)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

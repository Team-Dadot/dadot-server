-- 발송 예약은 짧은 트랜잭션으로 확정하고 SMTP를 기다리는 동안 연결을 반환한다.
-- 인증번호는 기존 AES-GCM 키로 암호화한다. 발송 확정 후 행을 제거한다.
CREATE TABLE email_verification_deliveries (
    request_key_hash CHAR(64) NOT NULL PRIMARY KEY,
    body_hmac CHAR(64) NOT NULL,
    verification_id CHAR(36) NOT NULL,
    user_id BIGINT NULL,
    identity_stamp CHAR(64) NULL,
    email VARCHAR(254) NOT NULL,
    purpose ENUM('SIGN_UP', 'PASSWORD_RESET', 'PASSWORD_CHANGE') NOT NULL,
    encrypted_code VARCHAR(256) NOT NULL,
    code_key_version VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    lease_owner CHAR(36) NOT NULL,
    lease_until DATETIME(6) NOT NULL,
    sent_at DATETIME(6) NULL,
    UNIQUE KEY uk_email_delivery_verification (verification_id),
    UNIQUE KEY uk_email_delivery_address (email, purpose),
    KEY idx_email_delivery_expiry (expires_at),
    CONSTRAINT fk_email_delivery_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_email_delivery_bounds CHECK (expires_at > created_at AND lease_until <= expires_at),
    CONSTRAINT ck_email_delivery_identity CHECK (
        (user_id IS NULL AND identity_stamp IS NULL) OR (user_id IS NOT NULL AND identity_stamp IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

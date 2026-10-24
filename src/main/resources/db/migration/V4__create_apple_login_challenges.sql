CREATE TABLE apple_login_challenges (
                                        nonce_hash CHAR(64) NOT NULL PRIMARY KEY,
                                        issued_at DATETIME(6) NOT NULL,
                                        expires_at DATETIME(6) NOT NULL,
                                        consumed_at DATETIME(6) NULL,
                                        request_hash CHAR(64) NULL,
                                        failed_at DATETIME(6) NULL,

                                        INDEX idx_apple_challenge_expires (expires_at),

                                        CONSTRAINT ck_apple_challenge_expiry
                                            CHECK (expires_at > issued_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
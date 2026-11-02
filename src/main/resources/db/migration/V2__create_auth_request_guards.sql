CREATE TABLE auth_request_guards (
                                     guard_hash CHAR(64) NOT NULL,
                                     request_count INT NOT NULL DEFAULT 0,
                                     expires_at DATETIME(6) NOT NULL,

                                     PRIMARY KEY (guard_hash),

                                     INDEX idx_auth_guard_expiry (expires_at),

                                     CONSTRAINT ck_auth_guard_count CHECK (
                                         request_count >= 0
                                         )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
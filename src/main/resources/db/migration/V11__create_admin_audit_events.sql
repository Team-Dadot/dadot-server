CREATE TABLE admin_audit_events (
                                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                    actor_user_id BIGINT NULL,
                                    action VARCHAR(100) NOT NULL,
                                    target_type VARCHAR(100) NOT NULL,
                                    target_id VARCHAR(100) NOT NULL,
                                    reason TEXT NULL,
                                    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                    INDEX idx_admin_audit_target (
        target_type,
        target_id,
        occurred_at
    ),

                                    CONSTRAINT fk_admin_audit_actor
                                        FOREIGN KEY (actor_user_id)
                                            REFERENCES users(id)
                                            ON DELETE SET NULL
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_bin;
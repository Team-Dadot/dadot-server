-- 로그아웃 시 현재 세션의 기기 연결을 해제하기 위한 테이블.
-- 기기 등록과 푸시 발송 API는 해당 기능 개발 시 추가한다.

CREATE TABLE user_devices (
                              installation_id CHAR(36) NOT NULL,
                              user_id BIGINT NOT NULL,
                              session_id CHAR(36) NOT NULL,
                              platform ENUM('IOS', 'ANDROID') NOT NULL,
                              permission ENUM('NOT_DETERMINED', 'GRANTED', 'DENIED')
        NOT NULL DEFAULT 'NOT_DETERMINED',
                              push_token_ciphertext TEXT NULL,
                              push_token_digest CHAR(64) NULL,
                              updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                              PRIMARY KEY (installation_id),

                              CONSTRAINT uk_device_push_token
                                  UNIQUE (push_token_digest),

                              INDEX idx_device_user (user_id),
                              INDEX idx_device_session_user (session_id, user_id),

                              CONSTRAINT ck_device_token CHECK (
                                  (push_token_ciphertext IS NULL AND push_token_digest IS NULL)
                                      OR
                                  (push_token_ciphertext IS NOT NULL AND push_token_digest IS NOT NULL)
                                  ),

                              CONSTRAINT ck_device_permission CHECK (
                                  permission = 'GRANTED' OR push_token_ciphertext IS NULL
                                  ),

                              CONSTRAINT fk_device_session_user
                                  FOREIGN KEY (session_id, user_id)
                                      REFERENCES auth_sessions (id, user_id)
                                      ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
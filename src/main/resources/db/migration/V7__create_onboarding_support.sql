-- user_onboarding은 V1에서 생성했으므로 재생성하지 않는다.
CREATE TABLE user_companies (
                                user_id BIGINT NOT NULL,
                                company_id BIGINT NOT NULL,
                                active_slot TINYINT NULL,
                                job_id BIGINT NULL,
                                custom_job_name VARCHAR(50) NULL,
                                interview_date DATE NULL,
                                first_added_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                activated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                excluded_at DATETIME(6) NULL,
                                version BIGINT NOT NULL DEFAULT 1,

                                PRIMARY KEY (user_id, company_id),

                                CONSTRAINT uk_user_company_slot UNIQUE (user_id, active_slot),

                                CONSTRAINT ck_company_slot CHECK (
                                    active_slot IS NULL OR active_slot BETWEEN 1 AND 5
                                    ),

                                CONSTRAINT ck_company_one_job CHECK (
                                    (job_id IS NOT NULL AND custom_job_name IS NULL)
                                        OR (
                                        job_id IS NULL
                                            AND custom_job_name IS NOT NULL
                                            AND CHAR_LENGTH(TRIM(custom_job_name)) BETWEEN 1 AND 50
                                        )
                                    ),

                                CONSTRAINT ck_company_activation CHECK (
                                    (active_slot IS NOT NULL AND excluded_at IS NULL)
                                        OR (active_slot IS NULL AND excluded_at IS NOT NULL)
                                    ),

                                CONSTRAINT ck_company_version CHECK (version >= 1),

                                CONSTRAINT fk_user_company_user FOREIGN KEY (user_id)
                                    REFERENCES users (id) ON DELETE RESTRICT,

                                CONSTRAINT fk_user_company_company FOREIGN KEY (company_id)
                                    REFERENCES companies (id) ON DELETE RESTRICT,

                                CONSTRAINT fk_user_company_job FOREIGN KEY (job_id)
                                    REFERENCES jobs (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE user_preferences (
                                  user_id BIGINT NOT NULL,
                                  selected_company_id BIGINT NULL,
                                  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                  PRIMARY KEY (user_id),

                                  CONSTRAINT fk_preference_user FOREIGN KEY (user_id)
                                      REFERENCES users (id) ON DELETE RESTRICT,

                                  CONSTRAINT fk_preference_company FOREIGN KEY (
                                                                                user_id, selected_company_id
                                      )
                                      REFERENCES user_companies (user_id, company_id)
                                      ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE notification_settings (
                                       user_id BIGINT NOT NULL,
                                       enabled BOOLEAN NOT NULL DEFAULT FALSE,
                                       scheduled_time TIME NULL,
                                       time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Seoul',
                                       version BIGINT NOT NULL DEFAULT 1,
                                       updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                       PRIMARY KEY (user_id),

                                       CONSTRAINT ck_notification_version CHECK (version >= 1),

                                       CONSTRAINT ck_notification_time CHECK (
                                           CHAR_LENGTH(time_zone) > 0
                                           ),

                                       CONSTRAINT fk_notification_user FOREIGN KEY (user_id)
                                           REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
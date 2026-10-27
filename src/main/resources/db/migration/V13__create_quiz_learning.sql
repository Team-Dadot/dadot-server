CREATE TABLE quiz_sets (
                           id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                           user_id BIGINT NOT NULL,
                           company_id BIGINT NOT NULL,
                           business_date DATE NOT NULL,
                           mode ENUM('NORMAL', 'FOCUS') NOT NULL,
                           status ENUM('IN_PROGRESS', 'COMPLETED', 'EXPIRED') NOT NULL DEFAULT 'IN_PROGRESS',
                           question_count TINYINT NOT NULL DEFAULT 5,
                           accuracy_before_correct INT NOT NULL DEFAULT 0,
                           accuracy_before_total INT NOT NULL DEFAULT 0,
                           accuracy_after_correct INT NULL,
                           accuracy_after_total INT NULL,
                           created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                           completed_at DATETIME(6) NULL,
                           UNIQUE KEY uk_quiz_set_day (user_id, company_id, business_date),
                           UNIQUE KEY uk_quiz_set_owner (id, user_id, company_id),
                           CONSTRAINT ck_quiz_set_size CHECK (question_count = 5),
                           CONSTRAINT ck_accuracy_before CHECK (
                               accuracy_before_correct >= 0 AND accuracy_before_total >= accuracy_before_correct
                               ),
                           CONSTRAINT ck_accuracy_after CHECK (
                               (accuracy_after_correct IS NULL AND accuracy_after_total IS NULL)
                                   OR (accuracy_after_correct IS NOT NULL AND accuracy_after_total IS NOT NULL
                                   AND accuracy_after_correct >= 0 AND accuracy_after_total >= accuracy_after_correct)
                               ),
                           CONSTRAINT ck_quiz_completion CHECK (
                               (status = 'COMPLETED' AND completed_at IS NOT NULL AND accuracy_after_total IS NOT NULL)
                                   OR (status <> 'COMPLETED' AND completed_at IS NULL AND accuracy_after_total IS NULL)
                               ),
                           CONSTRAINT fk_quiz_set_preparation FOREIGN KEY (user_id, company_id)
                               REFERENCES user_companies(user_id, company_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE quiz_set_items (
                                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                set_id BIGINT NOT NULL,
                                user_id BIGINT NOT NULL,
                                company_id BIGINT NOT NULL,
                                question_id BIGINT NOT NULL,
                                quiz_version_id BIGINT NOT NULL,
                                position TINYINT NOT NULL,
                                selected_from_incorrect BOOLEAN NOT NULL DEFAULT FALSE,
                                UNIQUE KEY uk_quiz_item_position (set_id, position),
                                UNIQUE KEY uk_quiz_item_question (set_id, question_id),
                                UNIQUE KEY uk_quiz_item_owner (id, user_id, company_id, question_id, quiz_version_id),
                                CONSTRAINT ck_quiz_position CHECK (position BETWEEN 1 AND 5),
                                CONSTRAINT ck_quiz_item_selection CHECK (selected_from_incorrect IN (0, 1)),
                                CONSTRAINT fk_quiz_item_set FOREIGN KEY (set_id, user_id, company_id)
                                    REFERENCES quiz_sets(id, user_id, company_id) ON DELETE RESTRICT,
                                CONSTRAINT fk_quiz_item_version FOREIGN KEY (quiz_version_id, question_id, company_id)
                                    REFERENCES quiz_question_versions(id, question_id, company_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE quiz_answers (
                              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                              set_item_id BIGINT NOT NULL,
                              user_id BIGINT NOT NULL,
                              company_id BIGINT NOT NULL,
                              question_id BIGINT NOT NULL,
                              quiz_version_id BIGINT NOT NULL,
                              selected_option_id BIGINT NOT NULL,
                              is_correct BOOLEAN NOT NULL,
                              request_key_hash CHAR(64) NOT NULL,
                              request_body_hmac CHAR(64) NOT NULL,
                              submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                              UNIQUE KEY uk_quiz_answer_item (set_item_id),
                              UNIQUE KEY uk_quiz_answer_key (user_id, request_key_hash),
                              INDEX idx_quiz_answer_history (user_id, company_id, question_id, submitted_at, id),
                              CONSTRAINT ck_quiz_answer_correct CHECK (is_correct IN (0, 1)),
                              CONSTRAINT fk_quiz_answer_item FOREIGN KEY (
                                                                          set_item_id, user_id, company_id, question_id, quiz_version_id
                                  ) REFERENCES quiz_set_items(id, user_id, company_id, question_id, quiz_version_id)
                                  ON DELETE RESTRICT,
                              CONSTRAINT fk_quiz_answer_option FOREIGN KEY (selected_option_id, quiz_version_id)
                                  REFERENCES quiz_options(id, quiz_version_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
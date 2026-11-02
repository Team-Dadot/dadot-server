ALTER TABLE ai_tasks
DROP CONSTRAINT ck_task_inputs,
    MODIFY COLUMN type ENUM ('INTERVIEW_GENERATION', 'INTERVIEW_EVALUATION', 'DISCLOSURE_SYNC', 'QUIZ_GENERATION') NOT NULL,
    ADD CONSTRAINT ck_task_inputs CHECK (
        (type = 'INTERVIEW_GENERATION' AND context_id IS NOT NULL AND company_id IS NOT NULL
            AND policy_version_id IS NOT NULL AND source_attempt_id IS NULL)
        OR (type = 'INTERVIEW_EVALUATION' AND context_id IS NOT NULL AND company_id IS NOT NULL
            AND policy_version_id IS NOT NULL AND source_attempt_id IS NOT NULL)
        OR (type IN ('DISCLOSURE_SYNC', 'QUIZ_GENERATION') AND company_id IS NOT NULL
            AND context_id IS NULL AND source_attempt_id IS NULL AND policy_version_id IS NULL)
    ),
    ADD UNIQUE INDEX uk_ai_task_company (id, company_id),
    ADD INDEX idx_ai_task_type_queue (type, status, available_at, lease_until);

ALTER TABLE ai_task_runs
    ADD COLUMN batch_no INT NULL,
    ADD CONSTRAINT ck_ai_task_batch_no CHECK (batch_no IS NULL OR batch_no >= 0);

CREATE TABLE quiz_generation_jobs (
                                      task_id CHAR(36) NOT NULL PRIMARY KEY,
                                      company_id BIGINT NOT NULL,
                                      active_company_id BIGINT NULL,
                                      requested_count INT NOT NULL,
                                      sources_json JSON NOT NULL,
                                      model VARCHAR(100) NOT NULL,
                                      prompt_hash CHAR(64) NOT NULL,
                                      max_output_tokens INT NOT NULL,
                                      questions_per_batch INT NOT NULL,
                                      total_batches INT NOT NULL,
                                      next_batch INT NOT NULL DEFAULT 0,
                                      attempt INT NOT NULL DEFAULT 0,
                                      rejected_count INT NOT NULL DEFAULT 0,
                                      created_count INT NOT NULL DEFAULT 0,
                                      UNIQUE KEY uk_quiz_generation_company (active_company_id),
                                      UNIQUE KEY uk_quiz_generation_task_company (task_id, company_id),
                                      CONSTRAINT fk_quiz_generation_task FOREIGN KEY (task_id, company_id)
                                          REFERENCES ai_tasks (id, company_id) ON DELETE RESTRICT,
                                      CONSTRAINT ck_quiz_generation_active CHECK (active_company_id IS NULL OR active_company_id = company_id),
                                      CONSTRAINT ck_quiz_generation_counts CHECK (
                                          requested_count BETWEEN 1 AND 100 AND total_batches BETWEEN 1 AND 1000
                                              AND next_batch BETWEEN 0 AND total_batches AND attempt BETWEEN 0 AND 3
                                              AND rejected_count >= 0 AND created_count BETWEEN 0 AND requested_count
                                              AND max_output_tokens BETWEEN 1024 AND 8192 AND questions_per_batch BETWEEN 1 AND 5
                                          )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE quiz_generation_batches (
                                         task_id CHAR(36) NOT NULL,
                                         batch_no INT NOT NULL,
                                         input_json JSON NOT NULL,
                                         PRIMARY KEY (task_id, batch_no),
                                         CONSTRAINT fk_quiz_generation_batch_job FOREIGN KEY (task_id)
                                             REFERENCES quiz_generation_jobs (task_id) ON DELETE RESTRICT,
                                         CONSTRAINT ck_quiz_generation_batch_no CHECK (batch_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE quiz_generation_candidates (
                                            task_id CHAR(36) NOT NULL,
                                            company_id BIGINT NOT NULL,
                                            batch_no INT NOT NULL,
                                            candidate_no INT NOT NULL,
                                            question_hash CHAR(64) NOT NULL,
                                            content_json JSON NOT NULL,
                                            selected_question_id BIGINT NULL,
                                            selected_version_id BIGINT NULL,
                                            PRIMARY KEY (task_id, batch_no, candidate_no),
                                            UNIQUE KEY uk_quiz_generation_candidate_hash (task_id, question_hash),
                                            CONSTRAINT fk_quiz_generation_candidate_job FOREIGN KEY (task_id, company_id)
                                                REFERENCES quiz_generation_jobs (task_id, company_id) ON DELETE RESTRICT,
                                            CONSTRAINT fk_quiz_generation_candidate_batch FOREIGN KEY (task_id, batch_no)
                                                REFERENCES quiz_generation_batches (task_id, batch_no) ON DELETE RESTRICT,
                                            CONSTRAINT fk_quiz_generation_candidate_question FOREIGN KEY (selected_version_id, selected_question_id, company_id)
                                                REFERENCES quiz_question_versions (id, question_id, company_id) ON DELETE RESTRICT,
                                            CONSTRAINT ck_quiz_generation_candidate CHECK (
                                                batch_no >= 0 AND candidate_no BETWEEN 0 AND 4
                                                    AND ((selected_question_id IS NULL AND selected_version_id IS NULL)
                                                    OR (selected_question_id IS NOT NULL AND selected_version_id IS NOT NULL))
                                                )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE quiz_generation_control (
                                         id TINYINT NOT NULL PRIMARY KEY,
                                         next_call_at DATETIME(6) NOT NULL,
                                         CONSTRAINT ck_quiz_generation_control CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO quiz_generation_control (id, next_call_at) VALUES (1, '1970-01-01 00:00:00');
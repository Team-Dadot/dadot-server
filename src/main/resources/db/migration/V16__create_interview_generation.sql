CREATE TABLE ai_contexts (
                             id CHAR(36) PRIMARY KEY,
                             user_id BIGINT NOT NULL,
                             company_id BIGINT NOT NULL,
                             job_id_snapshot BIGINT,
                             job_is_custom BOOLEAN NOT NULL,
                             job_name_snapshot VARCHAR(200) NOT NULL,
                             personalization_version BIGINT NOT NULL,
                             invalidated_at DATETIME(6),
                             created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE ai_context_materials (
                                      context_id CHAR(36) NOT NULL,
                                      material_version_id BIGINT NOT NULL,
                                      user_id BIGINT NOT NULL,
                                      PRIMARY KEY (context_id, material_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_question_keys (
                                         id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                         user_id BIGINT NOT NULL,
                                         company_id BIGINT NOT NULL,
                                         question_fingerprint CHAR(64) NOT NULL,
                                         draft_version BIGINT NOT NULL DEFAULT 0,
                                         created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                         deleted_at DATETIME(6),
                                         CONSTRAINT ck_question_draft_version CHECK (draft_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_questions (
                                     id BIGINT PRIMARY KEY,
                                     user_id BIGINT NOT NULL,
                                     company_id BIGINT NOT NULL,
                                     generation_task_id CHAR(36) UNIQUE NOT NULL,
                                     context_id CHAR(36) NOT NULL,
                                     generation_policy_version_id BIGINT NOT NULL,
                                     question_text TEXT NOT NULL,
                                     question_fingerprint CHAR(64) NOT NULL,
                                     created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                     CONSTRAINT ck_interview_question_text
                                         CHECK (CHAR_LENGTH(TRIM(question_text)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_question_evidence (
                                             question_id BIGINT NOT NULL,
                                             user_id BIGINT NOT NULL,
                                             company_id BIGINT NOT NULL,
                                             section_id BIGINT NOT NULL,
                                             quotation TEXT NOT NULL,
                                             PRIMARY KEY (question_id, section_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_drafts (
                                  question_id BIGINT PRIMARY KEY,
                                  user_id BIGINT NOT NULL,
                                  company_id BIGINT NOT NULL,
                                  draft_text TEXT NOT NULL,
                                  version BIGINT NOT NULL DEFAULT 1,
                                  saved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                  CONSTRAINT ck_draft_content
                                      CHECK (CHAR_LENGTH(draft_text) <= 2000 AND version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_daily_questions (
                                           user_id BIGINT NOT NULL,
                                           company_id BIGINT NOT NULL,
                                           business_date DATE NOT NULL,
                                           question_id BIGINT NOT NULL,
                                           question_slot TINYINT NOT NULL,
                                           admitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                           CONSTRAINT ck_interview_daily_question_slot
                                               CHECK (question_slot BETWEEN 1 AND 3),
                                           PRIMARY KEY (user_id, company_id, business_date, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_submissions (
                                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                       user_id BIGINT NOT NULL,
                                       company_id BIGINT NOT NULL,
                                       question_id BIGINT NOT NULL,
                                       business_date DATE NOT NULL,
                                       daily_slot TINYINT NOT NULL,
                                       attempt_number TINYINT NOT NULL,
                                       request_key_hash CHAR(64) NOT NULL,
                                       request_body_hmac CHAR(64) NOT NULL,
                                       submitted_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                       CONSTRAINT ck_interview_daily_slot CHECK (daily_slot BETWEEN 1 AND 9),
                                       CONSTRAINT ck_interview_attempt_number CHECK (attempt_number BETWEEN 1 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_attempts (
                                    id BIGINT PRIMARY KEY,
                                    user_id BIGINT NOT NULL,
                                    company_id BIGINT NOT NULL,
                                    context_id CHAR(36) NOT NULL,
                                    evaluation_policy_version_id BIGINT NOT NULL,
                                    answer_text TEXT NOT NULL,
                                    CONSTRAINT ck_interview_answer_text CHECK (
                                        CHAR_LENGTH(TRIM(answer_text)) > 0
                                            AND CHAR_LENGTH(answer_text) <= 2000
                                        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE UNIQUE INDEX uk_ctx_id_user_company
    ON ai_contexts(id, user_id, company_id);
CREATE UNIQUE INDEX uk_ctx_id_user
    ON ai_contexts(id, user_id);

CREATE UNIQUE INDEX uk_iq_key_id_user_company
    ON interview_question_keys(id, user_id, company_id);
CREATE UNIQUE INDEX uk_iq_key_id_user_company_fp
    ON interview_question_keys(id, user_id, company_id, question_fingerprint);
CREATE UNIQUE INDEX uk_iq_key_user_company_fp
    ON interview_question_keys(user_id, company_id, question_fingerprint);
CREATE INDEX idx_iq_key_user_company_deleted
    ON interview_question_keys(user_id, company_id, deleted_at);

CREATE UNIQUE INDEX uk_iq_id_user_company
    ON interview_questions(id, user_id, company_id);
CREATE UNIQUE INDEX uk_iq_id_user_company_fp
    ON interview_questions(id, user_id, company_id, question_fingerprint);
CREATE UNIQUE INDEX uk_iq_user_company_fp
    ON interview_questions(user_id, company_id, question_fingerprint);
CREATE INDEX idx_iq_user_company_created
    ON interview_questions(user_id, company_id, created_at);

CREATE INDEX idx_idraft_user_company_saved
    ON interview_drafts(user_id, company_id, saved_at);
CREATE UNIQUE INDEX uk_idaily_user_company_day_slot
    ON interview_daily_questions(user_id, company_id, business_date, question_slot);

CREATE UNIQUE INDEX uk_isub_user_company_day_slot
    ON interview_submissions(user_id, company_id, business_date, daily_slot);
CREATE UNIQUE INDEX uk_isub_question_attempt
    ON interview_submissions(question_id, attempt_number);
CREATE UNIQUE INDEX uk_isub_user_key
    ON interview_submissions(user_id, request_key_hash);
CREATE UNIQUE INDEX uk_isub_id_user_company
    ON interview_submissions(id, user_id, company_id);
CREATE INDEX idx_isub_question_submitted
    ON interview_submissions(question_id, submitted_at);

CREATE UNIQUE INDEX uk_iattempt_id_user_company
    ON interview_attempts(id, user_id, company_id);
CREATE UNIQUE INDEX uk_iattempt_id_ctx_user_policy
    ON interview_attempts(id, context_id, user_id, evaluation_policy_version_id);

ALTER TABLE ai_contexts
    ADD FOREIGN KEY (user_id, company_id)
        REFERENCES user_companies(user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE ai_context_materials
    ADD FOREIGN KEY (context_id, user_id)
        REFERENCES ai_contexts(id, user_id) ON DELETE RESTRICT;

ALTER TABLE ai_context_materials
    ADD FOREIGN KEY (material_version_id, user_id)
        REFERENCES material_versions(id, user_id) ON DELETE RESTRICT;

ALTER TABLE interview_question_keys
    ADD FOREIGN KEY (user_id, company_id)
        REFERENCES user_companies(user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_questions
    ADD FOREIGN KEY (id, user_id, company_id, question_fingerprint)
        REFERENCES interview_question_keys(id, user_id, company_id, question_fingerprint)
        ON DELETE RESTRICT;

ALTER TABLE interview_questions
    ADD FOREIGN KEY (context_id, user_id, company_id)
        REFERENCES ai_contexts(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_questions
    ADD FOREIGN KEY (
                     generation_task_id, context_id, user_id,
                     company_id, generation_policy_version_id
        )
        REFERENCES ai_tasks(id, context_id, user_id, company_id, policy_version_id)
        ON DELETE RESTRICT;

ALTER TABLE interview_questions
    ADD FOREIGN KEY (generation_policy_version_id)
        REFERENCES interview_policy_versions(id) ON DELETE RESTRICT;

ALTER TABLE interview_question_evidence
    ADD FOREIGN KEY (question_id, user_id, company_id)
        REFERENCES interview_questions(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_question_evidence
    ADD FOREIGN KEY (section_id, company_id)
        REFERENCES disclosure_sections(id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_drafts
    ADD FOREIGN KEY (question_id, user_id, company_id)
        REFERENCES interview_questions(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_daily_questions
    ADD FOREIGN KEY (question_id, user_id, company_id)
        REFERENCES interview_question_keys(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_submissions
    ADD FOREIGN KEY (user_id, company_id, business_date, question_id)
        REFERENCES interview_daily_questions(user_id, company_id, business_date, question_id)
        ON DELETE RESTRICT;

ALTER TABLE interview_submissions
    ADD FOREIGN KEY (question_id, user_id, company_id)
        REFERENCES interview_question_keys(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_attempts
    ADD FOREIGN KEY (id, user_id, company_id)
        REFERENCES interview_submissions(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_attempts
    ADD FOREIGN KEY (context_id, user_id, company_id)
        REFERENCES ai_contexts(id, user_id, company_id) ON DELETE RESTRICT;

ALTER TABLE interview_attempts
    ADD FOREIGN KEY (evaluation_policy_version_id)
        REFERENCES interview_policy_versions(id) ON DELETE RESTRICT;

ALTER TABLE ai_tasks
    ADD CONSTRAINT fk_interview_task_context
        FOREIGN KEY (context_id, user_id, company_id)
            REFERENCES ai_contexts(id, user_id, company_id) ON DELETE RESTRICT;

CREATE TABLE interview_generation_jobs (
                                           task_id CHAR(36) NOT NULL PRIMARY KEY,
                                           user_id BIGINT NOT NULL,
                                           company_id BIGINT NOT NULL,
                                           active_user_id BIGINT NULL,
                                           active_company_id BIGINT NULL,
                                           model VARCHAR(100) NOT NULL,
                                           prompt_hash CHAR(64) NOT NULL,
                                           sources_json JSON NOT NULL,
                                           sections_json JSON NOT NULL,
                                           scanned_sections INT NOT NULL,
                                           scanned_characters BIGINT NOT NULL,
                                           attempts TINYINT NOT NULL DEFAULT 0,

                                           UNIQUE KEY uk_interview_generation_active(
                                               active_user_id, active_company_id
                                               ),

                                           CONSTRAINT fk_interview_generation_job_task
                                               FOREIGN KEY (task_id, user_id, company_id)
                                                   REFERENCES ai_tasks(id, user_id, company_id) ON DELETE RESTRICT,

                                           CONSTRAINT ck_interview_generation_active CHECK (
                                               (active_user_id IS NULL AND active_company_id IS NULL)
                                                   OR (
                                                   active_user_id IS NOT NULL
                                                       AND active_company_id IS NOT NULL
                                                       AND active_user_id = user_id
                                                       AND active_company_id = company_id
                                                   )
                                               ),

                                           CONSTRAINT ck_interview_generation_counts CHECK (
                                               attempts BETWEEN 0 AND 3
                                                   AND scanned_sections > 0
                                                   AND scanned_characters > 0
                                               )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_generation_control (
                                              id TINYINT NOT NULL PRIMARY KEY,
                                              next_call_at DATETIME(6) NOT NULL,
                                              CONSTRAINT ck_interview_generation_control CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO interview_generation_control(id, next_call_at)
VALUES (1, '1970-01-01');
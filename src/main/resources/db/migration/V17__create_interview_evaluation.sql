CREATE TABLE interview_evaluations (
                                       id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                       attempt_id BIGINT NOT NULL UNIQUE,
                                       task_id CHAR(36) NOT NULL UNIQUE,
                                       user_id BIGINT NOT NULL,
                                       context_id CHAR(36) NOT NULL,
                                       policy_version_id BIGINT NOT NULL,
                                       summary_feedback VARCHAR(300) NOT NULL,
                                       overall_feedback TEXT NOT NULL,
                                       strengths JSON NOT NULL,
                                       improvements JSON NOT NULL,
                                       total_score TINYINT NOT NULL,
                                       evaluated_at DATETIME(6) NOT NULL,
                                       UNIQUE KEY uk_eval_id_policy(id, policy_version_id),
                                       UNIQUE KEY uk_eval_id_context(id, context_id),
                                       CONSTRAINT fk_eval_attempt FOREIGN KEY(attempt_id, context_id, user_id, policy_version_id)
                                           REFERENCES interview_attempts(id, context_id, user_id, evaluation_policy_version_id) ON DELETE RESTRICT,
                                       CONSTRAINT fk_eval_task FOREIGN KEY(task_id, attempt_id)
                                           REFERENCES ai_tasks(id, source_attempt_id) ON DELETE RESTRICT,
                                       CONSTRAINT ck_eval_total CHECK(total_score BETWEEN 0 AND 100),
                                       CONSTRAINT ck_eval_text CHECK(CHAR_LENGTH(TRIM(summary_feedback)) BETWEEN 1 AND 30
                                           AND CHAR_LENGTH(TRIM(overall_feedback)) > 0 AND CHAR_LENGTH(overall_feedback) <= 300
                                           AND CHAR_LENGTH(summary_feedback) <= 30),
                                       CONSTRAINT ck_eval_strengths CHECK(JSON_TYPE(strengths) = 'ARRAY' AND JSON_LENGTH(strengths) <= 2),
                                       CONSTRAINT ck_eval_improvements CHECK(JSON_TYPE(improvements) = 'ARRAY' AND JSON_LENGTH(improvements) <= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE evaluation_scores (
                                   evaluation_id BIGINT NOT NULL,
                                   criterion_id BIGINT NOT NULL,
                                   policy_version_id BIGINT NOT NULL,
                                   score TINYINT NOT NULL,
                                   feedback TEXT NOT NULL,
                                   reasoning TEXT NOT NULL,
                                   answer_quote VARCHAR(200) NOT NULL,
                                   PRIMARY KEY(evaluation_id, criterion_id),
                                   CONSTRAINT fk_eval_score_result FOREIGN KEY(evaluation_id, policy_version_id)
                                       REFERENCES interview_evaluations(id, policy_version_id) ON DELETE RESTRICT,
                                   CONSTRAINT fk_eval_score_criterion FOREIGN KEY(criterion_id, policy_version_id)
                                       REFERENCES evaluation_criteria(id, policy_version_id) ON DELETE RESTRICT,
                                   CONSTRAINT ck_eval_score CHECK(score BETWEEN 0 AND 25),
                                   CONSTRAINT ck_eval_score_text CHECK(CHAR_LENGTH(TRIM(feedback)) > 0 AND CHAR_LENGTH(feedback) <= 150
                                       AND CHAR_LENGTH(TRIM(reasoning)) > 0 AND CHAR_LENGTH(reasoning) <= 150
                                       AND CHAR_LENGTH(TRIM(answer_quote)) > 0 AND CHAR_LENGTH(answer_quote) <= 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE evaluation_material_links (
                                           evaluation_id BIGINT NOT NULL,
                                           context_id CHAR(36) NOT NULL,
                                           material_version_id BIGINT NOT NULL,
                                           display_order TINYINT NOT NULL,
                                           excerpt TEXT NOT NULL,
                                           connection_reason TEXT NOT NULL,
                                           PRIMARY KEY(evaluation_id, material_version_id),
                                           UNIQUE KEY uk_eval_material_order(evaluation_id, display_order),
                                           CONSTRAINT fk_eval_material_result FOREIGN KEY(evaluation_id, context_id)
                                               REFERENCES interview_evaluations(id, context_id) ON DELETE RESTRICT,
                                           CONSTRAINT fk_eval_material_context FOREIGN KEY(context_id, material_version_id)
                                               REFERENCES ai_context_materials(context_id, material_version_id) ON DELETE RESTRICT,
                                           CONSTRAINT ck_eval_material CHECK(display_order BETWEEN 1 AND 2
                                               AND CHAR_LENGTH(TRIM(excerpt)) > 0 AND CHAR_LENGTH(excerpt) <= 200
                                               AND CHAR_LENGTH(TRIM(connection_reason)) > 0 AND CHAR_LENGTH(connection_reason) <= 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_evaluation_jobs (
                                           task_id CHAR(36) PRIMARY KEY,
                                           model VARCHAR(100) NOT NULL,
                                           prompt_hash CHAR(64) NOT NULL,
                                           input_hash CHAR(64) NOT NULL,
                                           attempts TINYINT NOT NULL DEFAULT 0,
                                           run_no INT NOT NULL DEFAULT 0,
                                           created_at DATETIME(6) NOT NULL,
                                           CONSTRAINT fk_eval_job_task FOREIGN KEY(task_id) REFERENCES ai_tasks(id) ON DELETE CASCADE,
                                           CONSTRAINT ck_eval_job_attempt CHECK(attempts BETWEEN 0 AND 3 AND run_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_evaluation_control (
                                              id TINYINT PRIMARY KEY,
                                              next_call_at DATETIME(6) NOT NULL,
                                              CONSTRAINT ck_eval_control CHECK(id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO interview_evaluation_control(id, next_call_at) VALUES (1, '1970-01-01');
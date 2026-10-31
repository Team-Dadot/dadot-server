CREATE TABLE interview_policies (
                                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                    version BIGINT NOT NULL DEFAULT 1,
                                    policy_key VARCHAR(150) NOT NULL,
                                    type ENUM('GENERATION', 'EVALUATION') NOT NULL,
                                    company_id BIGINT NULL,
                                    job_id BIGINT NULL,
                                    active_version_no INT NULL,
                                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                    CONSTRAINT uk_policy_scope UNIQUE (policy_key),
                                    CONSTRAINT ck_interview_policies_revision CHECK (version >= 1),
                                    CONSTRAINT ck_policy_scope_key CHECK (
                                        policy_key = CONCAT(type, ':', COALESCE(company_id, 0), ':', COALESCE(job_id, 0))
                                        ),
                                    INDEX idx_policy_scope (type, company_id, job_id),
                                    CONSTRAINT fk_policy_company FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE RESTRICT,
                                    CONSTRAINT fk_policy_job FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE interview_policy_versions (
                                           id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                           policy_id BIGINT NOT NULL,
                                           version_no INT NOT NULL,
                                           status ENUM('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'SUSPENDED', 'SUPERSEDED') NOT NULL DEFAULT 'DRAFT',
                                           prompt_template LONGTEXT NOT NULL,
                                           rule_config JSON NOT NULL,
                                           review_note TEXT NULL,
                                           reviewed_by BIGINT NULL,
                                           reviewed_at DATETIME(6) NULL,
                                           published_at DATETIME(6) NULL COMMENT '최초 게시 시각. 재검수 중에도 보존하여 과거 버전 재게시를 차단.',
                                           created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                           CONSTRAINT uk_policy_version UNIQUE (policy_id, version_no),
                                           CONSTRAINT ck_policy_version CHECK (version_no > 0),
                                           CONSTRAINT fk_policy_version_parent FOREIGN KEY (policy_id) REFERENCES interview_policies(id) ON DELETE RESTRICT,
                                           CONSTRAINT fk_policy_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
                                           INDEX idx_policy_version_created (created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE interview_policies
    ADD CONSTRAINT fk_policy_active_version FOREIGN KEY (id, active_version_no)
        REFERENCES interview_policy_versions(policy_id, version_no) ON DELETE RESTRICT;

CREATE TABLE evaluation_criteria (
                                     id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                     policy_version_id BIGINT NOT NULL,
                                     position TINYINT NOT NULL,
                                     code VARCHAR(50) NOT NULL,
                                     name VARCHAR(100) NOT NULL,
                                     description TEXT NOT NULL,
                                     max_score TINYINT NOT NULL DEFAULT 25,
                                     CONSTRAINT uk_criterion_position UNIQUE (policy_version_id, position),
                                     CONSTRAINT uk_criterion_code UNIQUE (policy_version_id, code),
                                     CONSTRAINT uk_criterion_version UNIQUE (id, policy_version_id),
                                     CONSTRAINT ck_criterion_position CHECK (position BETWEEN 1 AND 4),
                                     CONSTRAINT ck_criterion_score CHECK (max_score = 25),
                                     CONSTRAINT ck_criterion_identity CHECK (
                                         (position = 1 AND code = 'COMPANY_UNDERSTANDING' AND name = '기업에 대한 이해') OR
                                         (position = 2 AND code = 'EXPERIENCE_SPECIFICITY' AND name = '경험의 구체성') OR
                                         (position = 3 AND code = 'ANSWER_LOGIC_STRUCTURE' AND name = '답변의 논리와 구성') OR
                                         (position = 4 AND code = 'EXPRESSION_CLARITY' AND name = '표현의 명확성')
                                         ),
                                     CONSTRAINT fk_criterion_policy_version FOREIGN KEY (policy_version_id)
                                         REFERENCES interview_policy_versions(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE ai_tasks
    ADD CONSTRAINT fk_task_policy_version FOREIGN KEY (policy_version_id)
        REFERENCES interview_policy_versions(id) ON DELETE RESTRICT;
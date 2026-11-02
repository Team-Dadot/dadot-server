CREATE TABLE quiz_questions (
                                id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                version BIGINT NOT NULL DEFAULT 1,
                                company_id BIGINT NOT NULL,
                                published_version_no INT NULL,
                                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                UNIQUE KEY uk_quiz_company (id, company_id),
                                INDEX idx_quiz_company_publication (
        company_id, published_version_no
    ),
                                INDEX idx_quiz_updated (updated_at, id),

                                CONSTRAINT ck_quiz_revision CHECK (version >= 1),

                                CONSTRAINT fk_quiz_company
                                    FOREIGN KEY (company_id)
                                        REFERENCES companies(id)
                                        ON DELETE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_bin;


CREATE TABLE quiz_question_versions (
                                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                                        question_id BIGINT NOT NULL,
                                        company_id BIGINT NOT NULL,
                                        version_no INT NOT NULL,

                                        status ENUM(
        'DRAFT',
        'IN_REVIEW',
        'APPROVED',
        'PUBLISHED',
        'SUSPENDED',
        'SUPERSEDED'
    ) NOT NULL DEFAULT 'DRAFT',

                                        question_text TEXT NOT NULL,
                                        explanation TEXT NOT NULL,
                                        plain_explanation TEXT NULL,
                                        review_note TEXT NULL,
                                        reviewed_by BIGINT NULL,
                                        reviewed_at DATETIME(6) NULL,
                                        published_at DATETIME(6) NULL,
                                        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                        UNIQUE KEY uk_quiz_version_number (
                                            question_id, version_no
                                            ),
                                        UNIQUE KEY uk_quiz_version_owner (
                                            id, question_id, company_id
                                            ),
                                        UNIQUE KEY uk_quiz_version_company (
                                            id, company_id
                                            ),
                                        INDEX idx_quiz_version_status (
        status, question_id, id
    ),

                                        CONSTRAINT ck_quiz_version_number CHECK (
                                            version_no > 0
                                            ),
                                        CONSTRAINT ck_quiz_question_text CHECK (
                                            CHAR_LENGTH(TRIM(question_text)) BETWEEN 1 AND 2000
                                            ),
                                        CONSTRAINT ck_quiz_explanation CHECK (
                                            CHAR_LENGTH(TRIM(explanation)) BETWEEN 1 AND 5000
                                            ),

                                        CONSTRAINT fk_quiz_version_parent
                                            FOREIGN KEY (question_id, company_id)
                                                REFERENCES quiz_questions(id, company_id)
                                                ON DELETE RESTRICT,

                                        CONSTRAINT fk_quiz_reviewer
                                            FOREIGN KEY (reviewed_by)
                                                REFERENCES users(id)
                                                ON DELETE SET NULL
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_bin;


ALTER TABLE quiz_questions
    ADD CONSTRAINT fk_quiz_publication
        FOREIGN KEY (id, published_version_no)
            REFERENCES quiz_question_versions(question_id, version_no)
            ON DELETE RESTRICT;


CREATE TABLE quiz_options (
                              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                              quiz_version_id BIGINT NOT NULL,
                              position TINYINT NOT NULL,
                              option_text TEXT NOT NULL,
                              is_correct BOOLEAN NOT NULL DEFAULT FALSE,

                              UNIQUE KEY uk_quiz_option_position (
                                  quiz_version_id, position
                                  ),
                              UNIQUE KEY uk_quiz_option_version (
                                  id, quiz_version_id
                                  ),

                              CONSTRAINT ck_quiz_option_correct CHECK (
                                  is_correct IN (0, 1)
                                  ),
                              CONSTRAINT ck_quiz_option CHECK (
                                  position BETWEEN 1 AND 4
                                      AND CHAR_LENGTH(TRIM(option_text)) BETWEEN 1 AND 1000
                                  ),

                              CONSTRAINT fk_quiz_option_version
                                  FOREIGN KEY (quiz_version_id)
                                      REFERENCES quiz_question_versions(id)
                                      ON DELETE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_bin;


CREATE TABLE quiz_evidence (
                               quiz_version_id BIGINT NOT NULL,
                               section_id BIGINT NOT NULL,
                               company_id BIGINT NOT NULL,
                               quotation TEXT NOT NULL,
                               explanation TEXT NULL,

                               PRIMARY KEY (quiz_version_id, section_id),
                               INDEX idx_quiz_evidence_section (
        section_id, quiz_version_id
    ),

                               CONSTRAINT ck_quiz_quotation CHECK (
                                   CHAR_LENGTH(TRIM(quotation)) BETWEEN 1 AND 5000
                                   ),

                               CONSTRAINT fk_quiz_evidence_version
                                   FOREIGN KEY (quiz_version_id, company_id)
                                       REFERENCES quiz_question_versions(id, company_id)
                                       ON DELETE RESTRICT,

                               CONSTRAINT fk_quiz_evidence_section
                                   FOREIGN KEY (section_id, company_id)
                                       REFERENCES disclosure_sections(id, company_id)
                                       ON DELETE RESTRICT
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_bin;
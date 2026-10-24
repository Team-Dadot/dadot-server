-- 온보딩의 실제 경험 수·소유권 조회에 필요한 ERD v0.9 기반 구조.
-- 경험/자소서 CRUD API는 후속 기능에서 구현한다.
CREATE TABLE personal_materials (
                                    id BIGINT NOT NULL AUTO_INCREMENT,
                                    user_id BIGINT NOT NULL,
                                    type ENUM('EXPERIENCE', 'COVER_LETTER') NOT NULL,
                                    current_version_no INT NULL,
                                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                    PRIMARY KEY (id),

                                    CONSTRAINT uk_material_owner_type UNIQUE (id, user_id, type),

                                    INDEX idx_material_user_type_updated (
        user_id, type, updated_at
    ),

                                    CONSTRAINT fk_material_user FOREIGN KEY (user_id)
                                        REFERENCES users (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE material_versions (
                                   id BIGINT NOT NULL AUTO_INCREMENT,
                                   material_id BIGINT NOT NULL,
                                   user_id BIGINT NOT NULL,
                                   material_type ENUM('EXPERIENCE', 'COVER_LETTER') NOT NULL,
                                   version_no INT NOT NULL,
                                   title VARCHAR(50) NULL,
                                   content_text TEXT NOT NULL,
                                   created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                   PRIMARY KEY (id),

                                   CONSTRAINT uk_material_version UNIQUE (material_id, version_no),

                                   CONSTRAINT uk_material_version_owner UNIQUE (id, user_id),

                                   CONSTRAINT ck_material_version CHECK (version_no > 0),

                                   CONSTRAINT ck_material_length CHECK (
                                       CHAR_LENGTH(TRIM(content_text)) > 0
                                           AND CHAR_LENGTH(content_text) <= 5000
                                       ),

                                   CONSTRAINT ck_experience_title CHECK (
                                       (
                                           material_type = 'EXPERIENCE'
                                               AND title IS NOT NULL
                                               AND CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 50
                                           )
                                           OR (material_type = 'COVER_LETTER' AND title IS NULL)
                                       ),

                                   CONSTRAINT fk_material_version_source FOREIGN KEY (
                                                                                      material_id, user_id, material_type
                                       )
                                       REFERENCES personal_materials (id, user_id, type)
                                       ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

ALTER TABLE personal_materials
    ADD CONSTRAINT fk_material_current_version FOREIGN KEY (
                                                            id, current_version_no
        )
        REFERENCES material_versions (material_id, version_no)
        ON DELETE RESTRICT;
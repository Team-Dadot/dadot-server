-- 기존 V9 완료 작업은 재실행하지 않는다.
-- 원문 모드의 신규 요청부터 새 상태 전이를 사용한다.
ALTER TABLE disclosure_sync_items
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN available_at DATETIME(6)
        NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ADD COLUMN content_result
        ENUM('CREATED', 'UPDATED', 'UNCHANGED') NULL,
    ADD CONSTRAINT ck_disclosure_item_attempts
        CHECK (attempts BETWEEN 0 AND 3),
    ADD INDEX idx_disclosure_item_ready(
        task_id, status, available_at, id
    );

CREATE UNIQUE INDEX uk_disclosure_content
    ON disclosure_versions(
                           disclosure_id, content_hash, extractor_version
        );

CREATE INDEX idx_disclosure_section_page
    ON disclosure_sections(disclosure_version_id, id);

-- 긴 본문은 추출기가 여러 절로 나눈다.
ALTER TABLE disclosure_sections
    ADD CONSTRAINT ck_disclosure_section_length
        CHECK (CHAR_LENGTH(content_text) BETWEEN 1 AND 8192);
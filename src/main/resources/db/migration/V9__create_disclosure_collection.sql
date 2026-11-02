-- ERD v0.9 공시·작업 저장 구조. 면접 도메인 FK는 해당 테이블 도입 시 추가한다.

CREATE TABLE `disclosures` (
                               `id` bigint PRIMARY KEY AUTO_INCREMENT,
                               `version` bigint NOT NULL DEFAULT 1 COMMENT 'API version/expectedVersion. 본문 버전 번호와 별개로 모든 관리 변경 시 원자 증가.',
                               `company_id` bigint NOT NULL,
                               `receipt_no` varchar(20) UNIQUE NOT NULL COMMENT 'DART 접수번호. 정정 공시는 새 접수번호와 별도 행.',
                               `report_name` varchar(300) NOT NULL,
                               `report_type` varchar(20) NOT NULL COMMENT 'ANNUAL / HALF_YEAR / QUARTERLY',
                               `disclosed_on` date NOT NULL,
                               `period_start` date,
                               `period_end` date,
                               `source_url` varchar(2048) NOT NULL,
                               `corrects_disclosure_id` bigint COMMENT '같은 기업의 정정 대상 공시. 자기 자신·순환 정정은 서비스에서 거절.',
                               `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
                               `updated_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
                               CONSTRAINT `ck_disclosures_revision` CHECK (version >= 1),
                               CONSTRAINT `ck_disclosure_period` CHECK (period_start IS NULL OR period_end IS NULL OR period_end >= period_start),
                               CONSTRAINT `ck_disclosure_type` CHECK (report_type IN ('ANNUAL', 'HALF_YEAR', 'QUARTERLY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE `disclosure_versions` (
                                       `id` bigint PRIMARY KEY AUTO_INCREMENT,
                                       `disclosure_id` bigint NOT NULL,
                                       `company_id` bigint NOT NULL,
                                       `version_no` int NOT NULL,
                                       `status` ENUM ('DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'SUSPENDED', 'SUPERSEDED') NOT NULL DEFAULT 'DRAFT',
                                       `original_object_key` varchar(512) COMMENT '원문 파일을 보관할 경우 object storage 위치.',
                                       `content_hash` char(64) NOT NULL,
                                       `extractor_version` varchar(100) NOT NULL,
                                       `revision_notice` text,
                                       `review_note` text,
                                       `reviewed_by` bigint,
                                       `reviewed_at` datetime(6),
                                       `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
                                       CONSTRAINT `ck_disclosure_version_no` CHECK (version_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE `disclosure_sections` (
                                       `id` bigint PRIMARY KEY AUTO_INCREMENT,
                                       `disclosure_version_id` bigint NOT NULL,
                                       `company_id` bigint NOT NULL,
                                       `section_key` varchar(200) NOT NULL,
                                       `heading` varchar(500),
                                       `content_text` longtext NOT NULL COMMENT '문단/표의 추출 본문. 퀴즈/면접의 근거 원문.',
                                       `locator_json` json COMMENT '원문 페이지·표·문단 위치.'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE `ai_tasks` (
                            `id` char(36) PRIMARY KEY,
                            `user_id` bigint NOT NULL COMMENT '개인 작업 소유자 또는 관리 작업 실행자.',
                            `company_id` bigint,
                            `type` ENUM ('INTERVIEW_GENERATION', 'INTERVIEW_EVALUATION', 'DISCLOSURE_SYNC') NOT NULL,
                            `status` ENUM ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED') NOT NULL DEFAULT 'PROCESSING',
                            `context_id` char(36),
                            `policy_version_id` bigint,
                            `source_attempt_id` bigint UNIQUE COMMENT '평가의 논리 작업은 답변별 1개. 실패 재요청은 같은 작업의 실행 run을 추가.',
                            `request_key_hash` char(64) NOT NULL,
                            `request_body_hmac` char(64) NOT NULL,
                            `request_parameters` json COMMENT '공시 수집 범위 등 비민감 옵션. 자소서/답변 원문·비밀키 저장 금지.',
                            `available_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
                            `lease_owner` varchar(100),
                            `lease_until` datetime(6),
                            `lease_version` bigint NOT NULL DEFAULT 0 COMMENT '워커 fencing token. 만료된 실행자가 결과를 덮어쓰지 못하게 CAS.',
                            `last_error_code` varchar(100),
                            `created_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
                            `completed_at` datetime(6),
                            CONSTRAINT `ck_task_inputs` CHECK ((type = 'INTERVIEW_GENERATION' AND context_id IS NOT NULL AND company_id IS NOT NULL AND policy_version_id IS NOT NULL AND source_attempt_id IS NULL) OR (type = 'INTERVIEW_EVALUATION' AND context_id IS NOT NULL AND company_id IS NOT NULL AND policy_version_id IS NOT NULL AND source_attempt_id IS NOT NULL) OR (type = 'DISCLOSURE_SYNC' AND company_id IS NOT NULL AND context_id IS NULL AND source_attempt_id IS NULL AND policy_version_id IS NULL)),
                            CONSTRAINT `ck_task_lease_version` CHECK (lease_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE `ai_task_runs` (
                                `id` bigint PRIMARY KEY AUTO_INCREMENT,
                                `task_id` char(36) NOT NULL,
                                `run_no` int NOT NULL,
                                `lease_version` bigint NOT NULL,
                                `status` ENUM ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED') NOT NULL DEFAULT 'PROCESSING',
                                `provider` varchar(100),
                                `model` varchar(100),
                                `provider_request_id` varchar(255),
                                `input_tokens` int,
                                `output_tokens` int,
                                `error_code` varchar(100),
                                `started_at` datetime(6) NOT NULL DEFAULT (CURRENT_TIMESTAMP(6)),
                                `finished_at` datetime(6),
                                CONSTRAINT `ck_task_run_no` CHECK (run_no > 0 AND lease_version >= 0),
                                CONSTRAINT `ck_task_tokens` CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE TABLE `disclosure_sync_items` (
                                         `id` bigint PRIMARY KEY AUTO_INCREMENT,
                                         `task_id` char(36) NOT NULL,
                                         `receipt_no` varchar(20) NOT NULL,
                                         `disclosure_version_id` bigint,
                                         `status` ENUM ('PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED') NOT NULL,
                                         `error_code` varchar(100),
                                         `processed_at` datetime(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;


CREATE UNIQUE INDEX `uk_disclosure_company` ON `disclosures` (`id`, `company_id`);

CREATE INDEX `idx_disclosure_company_date` ON `disclosures` (`company_id`, `disclosed_on`);

CREATE UNIQUE INDEX `uk_disclosure_version_number` ON `disclosure_versions` (`disclosure_id`, `version_no`);

CREATE UNIQUE INDEX `uk_disclosure_version_company` ON `disclosure_versions` (`id`, `company_id`);

CREATE INDEX `idx_disclosure_version_status` ON `disclosure_versions` (`company_id`, `status`);

CREATE UNIQUE INDEX `uk_disclosure_section_key` ON `disclosure_sections` (`disclosure_version_id`, `section_key`);

CREATE UNIQUE INDEX `uk_disclosure_section_company` ON `disclosure_sections` (`id`, `company_id`);

CREATE UNIQUE INDEX `uk_task_user_type_key` ON `ai_tasks` (`user_id`, `type`, `request_key_hash`);

CREATE UNIQUE INDEX `uk_task_owner_company` ON `ai_tasks` (`id`, `user_id`, `company_id`);

CREATE UNIQUE INDEX `uk_task_owner` ON `ai_tasks` (`id`, `user_id`);

CREATE UNIQUE INDEX `uk_task_context_policy` ON `ai_tasks` (`id`, `context_id`, `user_id`, `company_id`, `policy_version_id`);

CREATE UNIQUE INDEX `uk_task_attempt` ON `ai_tasks` (`id`, `source_attempt_id`);

CREATE INDEX `idx_task_ready` ON `ai_tasks` (`status`, `available_at`, `lease_until`);

CREATE UNIQUE INDEX `uk_task_run` ON `ai_task_runs` (`task_id`, `run_no`);

CREATE UNIQUE INDEX `uk_sync_task_receipt` ON `disclosure_sync_items` (`task_id`, `receipt_no`);

ALTER TABLE `disclosures` ADD FOREIGN KEY (`company_id`) REFERENCES `companies` (`id`) ON DELETE RESTRICT;

ALTER TABLE `disclosures` ADD FOREIGN KEY (`corrects_disclosure_id`, `company_id`) REFERENCES `disclosures` (`id`, `company_id`) ON DELETE RESTRICT;

ALTER TABLE `disclosure_versions` ADD FOREIGN KEY (`disclosure_id`, `company_id`) REFERENCES `disclosures` (`id`, `company_id`) ON DELETE RESTRICT;

ALTER TABLE `disclosure_versions` ADD FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`) ON DELETE SET NULL;

ALTER TABLE `disclosure_sections` ADD FOREIGN KEY (`disclosure_version_id`, `company_id`) REFERENCES `disclosure_versions` (`id`, `company_id`) ON DELETE RESTRICT;

ALTER TABLE `ai_tasks` ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT;

ALTER TABLE `ai_tasks` ADD FOREIGN KEY (`company_id`) REFERENCES `companies` (`id`) ON DELETE RESTRICT;

ALTER TABLE `ai_task_runs` ADD FOREIGN KEY (`task_id`) REFERENCES `ai_tasks` (`id`) ON DELETE RESTRICT;

ALTER TABLE `disclosure_sync_items` ADD FOREIGN KEY (`task_id`) REFERENCES `ai_tasks` (`id`) ON DELETE RESTRICT;

ALTER TABLE `disclosure_sync_items` ADD FOREIGN KEY (`disclosure_version_id`) REFERENCES `disclosure_versions` (`id`) ON DELETE RESTRICT;


ALTER TABLE api_idempotency_requests
    ADD COLUMN task_id CHAR(36) NULL,
    ADD INDEX idx_idempotency_task_user (task_id, user_id),
    ADD CONSTRAINT ck_idempotency_task_owner CHECK (task_id IS NULL OR user_id IS NOT NULL),
    ADD CONSTRAINT fk_idempotency_task_user FOREIGN KEY (task_id, user_id) REFERENCES ai_tasks(id, user_id) ON DELETE RESTRICT;

-- 목록 수집과 본문 추출을 분리하기 위한 작업 결과 구분. 원문 버전을 만들기 전에도 신규 건수 보존.
ALTER TABLE disclosure_sync_items ADD COLUMN disposition ENUM('CREATED', 'EXISTING') NULL;

-- 여러 서버가 동일 인증키를 사용할 때 공시 수집 워커를 직렬화하고 호출 간격/일시 중지를 공유한다.
CREATE TABLE disclosure_sync_control (
                                         id TINYINT NOT NULL PRIMARY KEY,
                                         next_call_at DATETIME(6) NOT NULL,
                                         CONSTRAINT ck_disclosure_sync_control CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO disclosure_sync_control(id, next_call_at)
VALUES (1, '1970-01-01 00:00:00');

CREATE INDEX idx_disclosure_sort ON disclosures(disclosed_on, id);
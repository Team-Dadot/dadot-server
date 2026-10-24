CREATE TABLE companies (
                           id BIGINT NOT NULL AUTO_INCREMENT,
                           dart_corp_code CHAR(8) NOT NULL,
                           name VARCHAR(200) NOT NULL,
                           industry_code VARCHAR(5) NULL,
                           industry VARCHAR(200) NULL,
                           logo_key VARCHAR(512) NULL,
                           is_service_company BOOLEAN NOT NULL DEFAULT FALSE,
                           created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                           updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                           PRIMARY KEY (id),

                           CONSTRAINT uk_company_dart_corp_code UNIQUE (dart_corp_code),

                           INDEX idx_company_service_name (is_service_company, name),

                           CONSTRAINT ck_company_industry_code CHECK (
                               industry_code IS NULL
                                   OR industry_code REGEXP '^[0-9]{2,5}$'
)
    ) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
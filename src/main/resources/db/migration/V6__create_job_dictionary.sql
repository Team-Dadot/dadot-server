CREATE TABLE job_categories (
                                id BIGINT NOT NULL AUTO_INCREMENT,
                                name VARCHAR(100) NOT NULL,
                                display_order INT NOT NULL DEFAULT 0,

                                PRIMARY KEY (id),
                                CONSTRAINT uk_job_category_name UNIQUE (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;

CREATE TABLE jobs (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      category_id BIGINT NOT NULL,
                      name VARCHAR(100) NOT NULL,
                      is_popular BOOLEAN NOT NULL DEFAULT FALSE,
                      is_active BOOLEAN NOT NULL DEFAULT TRUE,

                      PRIMARY KEY (id),
                      CONSTRAINT uk_job_category_name UNIQUE (category_id, name),
                      INDEX idx_job_active_popular (is_active, is_popular),
                      CONSTRAINT fk_job_category FOREIGN KEY (category_id)
                          REFERENCES job_categories (id) ON DELETE RESTRICT
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin;
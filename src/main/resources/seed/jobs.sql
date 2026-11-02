-- 화면 기준 초기안: 4개 분류, 24개 직무, 자주 찾는 직무 6개.
-- Flyway/앱 시작 시 자동 실행하지 않는다. V6 적용 후 명시적으로 실행한다.
-- 동일 이름 재등록 시 기존 ID·표시 순서·인기/활성 상태를 보존한다.
-- 분류명/직무명 변경은 이 파일 수정·재실행 대신 별도 데이터 변경으로 처리한다.
SET NAMES utf8mb4 COLLATE utf8mb4_bin;

START TRANSACTION;

INSERT INTO job_categories (name, display_order)
VALUES
    ('기획·전략', 1),
    ('마케팅·홍보', 2),
    ('영업·무역', 3),
    ('경영지원', 4)
    ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO jobs (category_id, name, is_popular)
SELECT c.id, initial.name, initial.is_popular
FROM job_categories c
         JOIN (
    SELECT '기획·전략' AS category_name, '경영기획·전략' AS name, TRUE AS is_popular
    UNION ALL SELECT '기획·전략', '사업기획', FALSE
    UNION ALL SELECT '기획·전략', '서비스기획', FALSE
    UNION ALL SELECT '기획·전략', '상품기획', FALSE
    UNION ALL SELECT '기획·전략', '사업개발', FALSE
    UNION ALL SELECT '마케팅·홍보', '마케팅', TRUE
    UNION ALL SELECT '마케팅·홍보', '디지털마케팅', FALSE
    UNION ALL SELECT '마케팅·홍보', '브랜드마케팅', FALSE
    UNION ALL SELECT '마케팅·홍보', '콘텐츠마케팅', FALSE
    UNION ALL SELECT '마케팅·홍보', '홍보', FALSE
    UNION ALL SELECT '영업·무역', '영업', TRUE
    UNION ALL SELECT '영업·무역', 'B2B영업', FALSE
    UNION ALL SELECT '영업·무역', 'B2C영업', FALSE
    UNION ALL SELECT '영업·무역', '해외영업', FALSE
    UNION ALL SELECT '영업·무역', '영업관리', FALSE
    UNION ALL SELECT '영업·무역', '무역', FALSE
    UNION ALL SELECT '경영지원', '인사·HR', TRUE
    UNION ALL SELECT '경영지원', '재무·회계', TRUE
    UNION ALL SELECT '경영지원', '구매·SCM', TRUE
    UNION ALL SELECT '경영지원', '총무', FALSE
    UNION ALL SELECT '경영지원', '법무', FALSE
    UNION ALL SELECT '경영지원', '교육·조직문화', FALSE
    UNION ALL SELECT '경영지원', '경영지원', FALSE
    UNION ALL SELECT '경영지원', '물류관리', FALSE
) initial ON initial.category_name = c.name
    ON DUPLICATE KEY UPDATE name = VALUES(name);

COMMIT;
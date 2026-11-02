-- 최종 초기 데이터: 4개 분류, 19개 직무, 자주 찾는 직무 6개.
-- Flyway/앱 시작 시 자동 실행하지 않는다. V6 적용 후 명시적으로 실행한다.
-- 기존 24개 직무를 새 체계로 변환하는 스크립트가 아니다.
-- 첫 실행 전 job_categories와 jobs가 모두 비어 있는지 확인한다.
-- 동일 이름 재등록 시 기존 ID·표시 순서·인기/활성 상태를 보존한다.
-- 자주 찾는 직무: 경영기획, 인사, 재무·회계, 구매·SCM, 영업, 마케팅.
SET NAMES utf8mb4 COLLATE utf8mb4_bin;

START TRANSACTION;

INSERT INTO job_categories (name, display_order)
VALUES
    ('경영·기획', 1),
    ('영업·마케팅', 2),
    ('개발·디자인', 3),
    ('연구·생산', 4)
    ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO jobs (category_id, name, is_popular)
SELECT c.id, initial.name, initial.is_popular
FROM job_categories c
         JOIN (
    SELECT '경영·기획' AS category_name, '경영기획' AS name, TRUE AS is_popular
    UNION ALL SELECT '경영·기획', '서비스기획', FALSE
    UNION ALL SELECT '경영·기획', '인사', TRUE
    UNION ALL SELECT '경영·기획', '재무·회계', TRUE
    UNION ALL SELECT '경영·기획', '구매·SCM', TRUE
    UNION ALL SELECT '영업·마케팅', '영업', TRUE
    UNION ALL SELECT '영업·마케팅', '해외영업', FALSE
    UNION ALL SELECT '영업·마케팅', '영업관리', FALSE
    UNION ALL SELECT '영업·마케팅', '마케팅', TRUE
    UNION ALL SELECT '영업·마케팅', '홍보', FALSE
    UNION ALL SELECT '개발·디자인', '서버개발', FALSE
    UNION ALL SELECT '개발·디자인', '앱개발', FALSE
    UNION ALL SELECT '개발·디자인', '데이터분석', FALSE
    UNION ALL SELECT '개발·디자인', 'UX·UI디자인', FALSE
    UNION ALL SELECT '연구·생산', '연구개발', FALSE
    UNION ALL SELECT '연구·생산', '생산관리', FALSE
    UNION ALL SELECT '연구·생산', '생산기술', FALSE
    UNION ALL SELECT '연구·생산', '품질관리', FALSE
    UNION ALL SELECT '연구·생산', '품질보증', FALSE
) initial ON initial.category_name = c.name
    ON DUPLICATE KEY UPDATE name = VALUES(name);

COMMIT;
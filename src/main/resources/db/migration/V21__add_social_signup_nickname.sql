-- 신규 소셜 가입의 약관 동의 전 이름을 해당 일회성 증빙에 연결한다.
-- 기존 증빙·이메일 가입·기존 회원 닉네임은 변경하지 않는다.
ALTER TABLE auth_proofs
    ADD COLUMN subject_nickname VARCHAR(20) NULL AFTER subject_email;

-- 기존 근거의 원문·출처는 유지하고, 새 생성 해석을 같은 근거 행에 별도로 보존한다.
-- 기존 질문은 NULL 그대로이며 운영 데이터 보정이나 AI 재생성은 하지 않는다.
ALTER TABLE interview_question_evidence
    ADD COLUMN interpretation TEXT NULL AFTER quotation;

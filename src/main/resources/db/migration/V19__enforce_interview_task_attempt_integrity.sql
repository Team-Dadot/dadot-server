ALTER TABLE ai_tasks
    ADD INDEX idx_task_attempt_context_policy (
        source_attempt_id, context_id, user_id, policy_version_id
    ),
    ADD CONSTRAINT fk_task_attempt_context_policy
        FOREIGN KEY (
            source_attempt_id, context_id, user_id, policy_version_id
        )
        REFERENCES interview_attempts (
            id, context_id, user_id, evaluation_policy_version_id
        )
        ON DELETE RESTRICT
        ON UPDATE RESTRICT;
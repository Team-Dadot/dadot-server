package com.dadot.server.domain.interview;

import org.springframework.jdbc.core.JdbcTemplate;

/** Removes only the current test company's evaluation graph, in FK order. */
final class InterviewTestDataCleanup {
    private InterviewTestDataCleanup() {}

    static void deleteEvaluationTasks(JdbcTemplate jdbc, long company) {
        jdbc.update("""
                delete l from evaluation_material_links l
                join interview_evaluations e on e.id = l.evaluation_id
                join ai_tasks t on t.id = e.task_id
                where t.company_id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, company);

        jdbc.update("""
                delete s from evaluation_scores s
                join interview_evaluations e on e.id = s.evaluation_id
                join ai_tasks t on t.id = e.task_id
                where t.company_id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, company);

        jdbc.update("""
                delete e from interview_evaluations e
                join ai_tasks t on t.id = e.task_id
                where t.company_id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, company);

        jdbc.update("""
                update api_idempotency_requests r
                join ai_tasks t on t.id = r.task_id
                set r.task_id = null
                where t.company_id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, company);

        jdbc.update("""
                delete r from ai_task_runs r
                join ai_tasks t on t.id = r.task_id
                where t.company_id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, company);

        jdbc.update("""
                delete j from interview_evaluation_jobs j
                join ai_tasks t on t.id = j.task_id
                where t.company_id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, company);

        jdbc.update("""
                delete from ai_tasks
                where company_id = ? and type = 'INTERVIEW_EVALUATION'
                """, company);
    }
}
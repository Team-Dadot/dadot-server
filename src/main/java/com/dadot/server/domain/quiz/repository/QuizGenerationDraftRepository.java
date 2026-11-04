package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.Lease;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;

/** 기업·작업 행을 잠근 짧은 쓰기 트랜잭션에서 호출한다. 게시 상태는 변경하지 않는다. */
@Repository
@RequiredArgsConstructor
public class QuizGenerationDraftRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final QuizBankRepository bank;

    public boolean hasEnoughCandidates(Lease lease) {
        var existing = new HashSet<String>();
        jdbc.query("select question_text from quiz_question_versions where company_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        existing.add(QuizGeneration.fingerprint(rs.getString(1))), lease.companyId());
        var hashes = jdbc.query("select question_hash from quiz_generation_candidates where task_id = ?",
                (rs, n) -> rs.getString(1), lease.taskId());
        return hashes.stream().filter(hash -> !existing.contains(hash)).distinct().count() >= lease.questionCount();
    }

    public int saveDrafts(Lease lease, Instant now) {
        return saveDrafts(lease.taskId(), lease.companyId(), lease.userId(), lease.questionCount(), now);
    }

    public int saveDrafts(String taskId, long companyId, long actorId, int requestedCount, Instant now) {
        var existing = new HashSet<String>();
        jdbc.query("select question_text from quiz_question_versions where company_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        existing.add(QuizGeneration.fingerprint(rs.getString(1))), companyId);
        var candidates = jdbc.query("""
                select batch_no, candidate_no, question_hash, content_json
                from quiz_generation_candidates where task_id = ?
                order by candidate_no, batch_no
                """, (rs, n) -> new Candidate(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4)), taskId);
        int created = 0;
        for (var candidate : candidates) {
            if (created >= requestedCount) break;
            if (!existing.add(candidate.hash())) continue;
            var content = QuizBank.Content.parse(mapper.readTree(candidate.json()));
            bank.validateReferences(content);
            long question = bank.createQuestion(companyId, now);
            long version = bank.createVersion(question, content, "AI generated draft; task=" + taskId, now);
            bank.audit(actorId, "QUIZ_AI_DRAFT_CREATED", version, null, now);
            jdbc.update("""
                    update quiz_generation_candidates set selected_question_id = ?, selected_version_id = ?
                    where task_id = ? and batch_no = ? and candidate_no = ?
                    """, question, version, taskId, candidate.batch(), candidate.number());
            created++;
        }
        return created;
    }

    private record Candidate(int batch, int number, String hash, String json) {}
}
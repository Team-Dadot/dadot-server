package com.dadot.server.domain.interview.model;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InterviewEvaluation {
    public static final String PROTOCOL = "interview-evaluation-v1";
    private InterviewEvaluation() {}

    public record EvidenceInput(long sectionId, long disclosureVersionId, String reportTitle,
                                String reportType, LocalDate disclosedOn, LocalDate periodStart, LocalDate periodEnd,
                                String heading, String content, String questionQuote) {}
    public record Input(String companyName, Job job, String question, String answer,
                        List<EvidenceInput> evidence, List<Material> experiences,
                        List<InterviewPolicy.Criterion> rubric) {
        @Override public String toString() { return "EvaluationInput[redacted]"; }
    }
    public record Score(String criterionCode, int score, String feedback, String reason, String answerQuote) {}
    public record Link(long materialVersionId, String excerpt, String connectionReason) {}
    public record Generated(String summaryFeedback, String overallFeedback, List<Score> criteria,
                            List<String> strengths, List<String> improvements, List<Link> relatedMaterials) {
        @Override public String toString() { return "EvaluationOutput[redacted]"; }
    }
    public record Output(Generated generated, Metadata metadata) {}
    public record Claim(String taskId, String owner, long fence, long runId, int runNo, int attempt) {}
    public record Lease(Claim claim, String model, String promptHash, String rules, Input input) {
        @Override public String toString() { return "EvaluationLease[taskId=" + claim.taskId() + "]"; }
    }
    public record CriterionView(String criterionCode, String label, int score, int maxScore,
                                String feedback, String reason) {}
    public record MaterialView(String sourceType, long sourceId, int sourceVersion, String displayText,
                               String excerpt, String connectionReason) {}
    public record Result(String summaryFeedback, String overallFeedback, List<CriterionView> criteria,
                         List<String> strengths, List<String> improvements, List<MaterialView> relatedMaterials,
                         long rubricVersionId, int totalScore, int maxTotalScore, int scorePercent,
                         boolean completionDecision, String readinessStatus, String readinessLabel) {}
    public record View(Attempt attempt, String question, String status, String policyStatus, Result result,
                       Problem error, Instant evaluatedAt, Usage usage) {}

    public static Generated validate(JsonNode node, Input input) {
        fields(node, Set.of("summaryFeedback", "overallFeedback", "criteria", "strengths", "improvements", "relatedMaterials"));
        String summary = text(node.get("summaryFeedback"), 30);
        String overall = text(node.get("overallFeedback"), 300);
        JsonNode scores = node.get("criteria");
        if (!scores.isArray() || scores.size() != 4) throw invalid();
        var criteria = new ArrayList<Score>();
        for (int i = 0; i < 4; i++) {
            JsonNode score = scores.get(i);
            fields(score, Set.of("criterionCode", "score", "feedback", "reason", "answerQuote"));
            String code = text(score.get("criterionCode"), 50);
            if (!code.equals(InterviewPolicy.CODES.get(i))) throw invalid();
            JsonNode value = score.get("score");
            if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 25) throw invalid();
            String quote = text(score.get("answerQuote"), 200);
            if (!input.answer().contains(quote)) throw invalid();
            criteria.add(new Score(code, value.intValue(), text(score.get("feedback"), 150),
                    text(score.get("reason"), 150), quote));
        }
        var links = new ArrayList<Link>();
        JsonNode related = node.get("relatedMaterials");
        if (!related.isArray() || related.size() > 2) throw invalid();
        var used = new HashSet<Long>();
        for (JsonNode item : related) {
            fields(item, Set.of("materialVersionId", "excerpt", "connectionReason"));
            JsonNode version = item.get("materialVersionId");
            if (!version.isIntegralNumber() || !version.canConvertToLong()
                    || version.longValue() < 1 || version.longValue() > InterviewPolicy.MAX_ID) throw invalid();
            long id = version.longValue();
            String excerpt = text(item.get("excerpt"), 200);
            Material source = input.experiences().stream().filter(m -> m.versionId() == id)
                    .findFirst().orElseThrow(InterviewEvaluation::invalid);
            if (!used.add(id) || !source.content().contains(excerpt)) throw invalid();
            links.add(new Link(id, excerpt, text(item.get("connectionReason"), 200)));
        }
        return new Generated(summary, overall, List.copyOf(criteria), strings(node.get("strengths"), 120),
                strings(node.get("improvements"), 180), List.copyOf(links));
    }

    public static int total(Generated value) {
        return value.criteria().stream().mapToInt(Score::score).sum();
    }

    public static Failure invalid() { return new Failure("AI_INVALID_OUTPUT", false); }

    private static List<String> strings(JsonNode value, int maximum) {
        if (!value.isArray() || value.size() > 2) throw invalid();
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (JsonNode item : value) {
            String text = text(item, maximum);
            if (!seen.add(text.strip())) throw invalid();
            result.add(text);
        }
        return List.copyOf(result);
    }

    private static String text(JsonNode node, int maximum) {
        if (node == null || !node.isTextual()) throw invalid();
        String value = node.asText();
        if (value.isBlank() || value.codePointCount(0, value.length()) > maximum) throw invalid();
        for (int offset = 0; offset < value.length();) {
            int point = value.codePointAt(offset);
            if ((point >= 0xD800 && point <= 0xDFFF)
                    || (Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')) throw invalid();
            offset += Character.charCount(point);
        }
        return value;
    }

    private static void fields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()
                || !node.propertyNames().containsAll(names)) throw invalid();
    }
}
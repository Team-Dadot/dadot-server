package com.dadot.server.domain.quiz.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class QuizGeneration {
    public static final String PROMPT_VERSION = "quiz-bank-v1";
    // 생성 검증 정책이 바뀌면 버전도 변경해 진행 중 작업과 구분한다.
    public static final String CONTENT_POLICY_VERSION = "no-numeric-recall-v1";
    // 새 AI 생성 문항에만 적용한다. 기존 문제은행의 저장·조회 계약은 유지한다.
    public static final int MAX_QUESTION_LENGTH = 45;
    public static final int MAX_OPTION_LENGTH = 20;
    public static final int MAX_EXPLANATION_LENGTH = 120;
    private static final Pattern QUESTION_ENDING =
            Pattern.compile("(?:요|까|십시오|바랍니다)[?？.。!！]?$");
    private static final Pattern NORMALIZE = Pattern.compile("[\\p{Z}\\s\\p{P}]+");
    private static final Pattern OPTION_WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern OPTION_PUNCTUATION = Pattern.compile(
            "[\\p{P}&&[^.,]]|(?<!\\p{Nd})[.,]|[.,](?!\\p{Nd})");
    private static final Pattern NUMERIC_QUESTION = Pattern.compile(
            "몇[십백천만억조]*(?:퍼센트|개월|시간|명|원|달러|개|건|대|곳|회|년|월|일|분|초|톤|배|위|%)"
                    + "|(?:매출액?|영업이익|순이익|자산|부채|자본금|투자액|판매량|생산량|수송인원|승객수|직원수|비율|비중|점유율|규모)"
                    + ".{0,12}(?:얼마|어느정도|어느수준)"
                    + "|(?:수치|숫자|금액|비율|수량|연도)(?:은|는|이|가)?(?:얼마|몇|무엇)"
                    + "|(?:설립|창립|상장|준공|개장)(?:된|한)?(?:연도|일자|시기)(?:은|는|이|가)?(?:언제|몇|무엇|어느)"
                    + "|(?:설립|창립|상장|준공|개장).{0,8}언제"
                    + "|계산하면|계산한값|합산하면|더하면|빼면|곱하면|나누면");
    private static final String QUANTITY_VALUE =
            "[+-]?(?:[0-9]+(?:[.,][0-9]+)*|[영공일이삼사오육칠팔구십백천만억조]+"
                    + "|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)[십백천만억조]*";
    private static final String QUANTITY_UNIT =
            "(?:퍼센트|개월|시간|달러|유로|킬로그램|킬로미터|제곱미터|미터|리터|kg|km|m2|ha|명|원|개|건|대|곳|회|년|월|일|분|초|톤|배|위|%)?";
    private static final Pattern NUMERIC_OPTION = Pattern.compile(
            "(?:약|대략|최대|최소|전년대비|전년동기대비)?"
                    + QUANTITY_VALUE + QUANTITY_UNIT
                    + "(?:(?:~|∼|–|-|부터)" + QUANTITY_VALUE + QUANTITY_UNIT + "(?:까지)?)?"
                    + "(?:이상|이하|초과|미만|내외|정도|가량|수준|증가|감소|초|중|말)?");

    private QuizGeneration() {}

    public record Request(List<Long> disclosureVersionIds, int questionCount) {
        public Request {
            if (disclosureVersionIds == null || disclosureVersionIds.isEmpty()
                    || disclosureVersionIds.size() > 3 || questionCount < 1 || questionCount > 100) {
                throw QuizBank.invalid();
            }
            var unique = new HashSet<Long>();
            for (Long id : disclosureVersionIds) {
                if (id == null || !unique.add(QuizBank.id(id))) throw QuizBank.invalid();
            }
            disclosureVersionIds = disclosureVersionIds.stream().sorted().toList();
        }

        public static Request parse(JsonNode body) {
            fields(body, Set.of("disclosureVersionIds"), Set.of("questionCount"));
            var ids = body.get("disclosureVersionIds");
            if (!ids.isArray() || ids.isEmpty() || ids.size() > 3) throw QuizBank.invalid();
            var values = new ArrayList<Long>();
            for (var id : ids) values.add(number(id));
            long count = body.has("questionCount") ? number(body.get("questionCount")) : 100;
            if (count > 100) throw QuizBank.invalid();
            return new Request(values, (int) count);
        }
    }

    public record Accepted(String taskId, String status) {}
    public record Source(long disclosureVersionId, long disclosureId, String reportTitle,
                         String reportType, LocalDate submittedDate, String contentHash) {}
    public record Section(long sectionId, long disclosureVersionId, String reportTitle,
                          String reportType, LocalDate submittedDate, String heading,
                          String kind, String text) {}
    public record Input(List<Section> primary, List<Section> context) {
        public Input {
            primary = List.copyOf(primary);
            context = List.copyOf(context);
        }
        public List<Section> all() {
            var rows = new ArrayList<>(primary);
            rows.addAll(context);
            return List.copyOf(rows);
        }
    }
    public record Plan(List<Source> sources, List<Input> batches, int sectionCount) {}
    public record Metadata(String requestId, String model, Integer inputTokens, Integer outputTokens) {}
    public record Output(JsonNode questions, Metadata metadata) {}
    public record Validated(List<QuizBank.Content> questions, int rejectedCount, int incompleteCount) {
        public Validated(List<QuizBank.Content> questions, int rejectedCount) {
            this(questions, rejectedCount, 0);
        }
    }
    public record Lease(String taskId, long companyId, long userId, String owner, long fence,
                        long runId, int batchNo, int attempt, int questionCount, int batchCount,
                        int questionsPerBatch, int maxOutputTokens, String model,
                        String promptVersion, Input input) {}
    public record Result(String taskId, String status, long companyId, int requestedCount,
                         int processedBatches, int totalBatches, int candidateCount, int rejectedCount,
                         int createdCount, boolean targetReached, String shortfallReason,
                         List<Long> questionIds, List<Source> sources, String errorCode,
                         Instant createdAt, Instant completedAt) {}

    public static Validated validate(JsonNode questions, long companyId, Input input, int maximum) {
        if (questions == null || !questions.isArray() || questions.size() > maximum) {
            throw new Failure("AI_INVALID_OUTPUT", false, 0, null, "QUESTION_ARRAY_OR_COUNT");
        }
        var sources = input.all().stream().collect(java.util.stream.Collectors.toMap(
                Section::sectionId, s -> s, (a, b) -> a));
        var primary = input.primary().stream().map(Section::sectionId).collect(java.util.stream.Collectors.toSet());
        var accepted = new ArrayList<QuizBank.Content>();
        var seen = new HashSet<String>();
        int rejected = 0;
        int incomplete = 0;
        for (var item : questions) {
            try {
                fields(item, Set.of("question", "options", "correctOptionPosition", "explanation", "evidence"), Set.of());
                var node = item.deepCopy();
                if (!(node instanceof tools.jackson.databind.node.ObjectNode object)) throw QuizBank.invalid();
                object.put("companyId", companyId);
                var content = QuizBank.Content.parse(object);
                if (content.evidence().size() > 3) throw QuizBank.invalid();
                validText(content.question(), MAX_QUESTION_LENGTH);
                validText(content.explanation(), MAX_EXPLANATION_LENGTH);
                var options = new HashSet<String>();
                for (var option : content.options()) {
                    validText(option.text(), MAX_OPTION_LENGTH);
                    if (!options.add(normalizeOption(option.text()))) throw QuizBank.invalid();
                }
                if (numericRecall(content)) throw QuizBank.invalid();
                boolean primaryEvidence = false;
                for (var evidence : content.evidence()) {
                    validText(evidence.quote(), 250);
                    var source = sources.get(evidence.sectionId());
                    if (source == null || source.disclosureVersionId() != evidence.disclosureVersionId()
                            || !source.text().contains(evidence.quote())) throw QuizBank.invalid();
                    primaryEvidence |= primary.contains(evidence.sectionId());
                }
                if (!primaryEvidence) throw QuizBank.invalid();
                if (!QUESTION_ENDING.matcher(content.question().stripTrailing()).find()) {
                    incomplete++;
                    throw QuizBank.invalid();
                }
                if (!seen.add(fingerprint(content.question()))) throw QuizBank.invalid();
                accepted.add(content);
            } catch (BusinessException | IllegalArgumentException ex) {
                rejected++;
            }
        }
        return new Validated(List.copyOf(accepted), rejected, incomplete);
    }

    public static String uuid(String value) {
        try {
            if (value == null || !value.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                throw QuizBank.invalid();
            }
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ex) {
            throw QuizBank.invalid();
        }
    }

    public static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw QuizBank.invalid();
        for (String name : required) if (!node.has(name)) throw QuizBank.invalid();
        for (String name : node.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw QuizBank.invalid();
        }
    }

    public static long number(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw QuizBank.invalid();
        return QuizBank.id(node.longValue());
    }

    public static void validText(String value, int maximum) {
        QuizBank.text(value, maximum);
        if (value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw QuizBank.invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw QuizBank.invalid();
            } else if (Character.isLowSurrogate(c) || c == 0) throw QuizBank.invalid();
        }
    }

    public static String normalize(String value) {
        return NORMALIZE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String normalizeOption(String value) {
        String compact = OPTION_WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)).replaceAll("");
        // 보기의 숫자 사이 .와 ,만 보존한다. 기존 문항 fingerprint 정규화는 유지한다.
        return OPTION_PUNCTUATION.matcher(compact).replaceAll("");
    }

    private static boolean numericRecall(QuizBank.Content content) {
        // 판별에만 정규화한다. 원문·인용·저장 문구 및 기존 fingerprint는 바꾸지 않는다.
        if (NUMERIC_QUESTION.matcher(compact(content.question())).find()) return true;
        // 숫자가 들어간 5G·3M 등의 이름은 수량만으로 된 보기와 구분한다.
        return content.options().stream()
                .filter(option -> NUMERIC_OPTION.matcher(compact(option.text())).matches())
                .count() >= 3;
    }

    private static String compact(String value) {
        return OPTION_WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    public static String fingerprint(String question) {
        return sha256(normalize(question));
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static final class Failure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final long retryAfterSeconds;
        private final Metadata metadata;
        private final String detailCode;

        public Failure(String code, boolean retryable) {
            this(code, retryable, 0, null);
        }
        public Failure(String code, boolean retryable, long retryAfterSeconds, Metadata metadata) {
            this(code, retryable, retryAfterSeconds, metadata, null);
        }

        public Failure(String code, boolean retryable, long retryAfterSeconds, Metadata metadata, String detailCode) {
            super(code);
            this.detailCode = detailCode;
            this.code = code;
            this.retryable = retryable;
            this.retryAfterSeconds = retryAfterSeconds;
            this.metadata = metadata;
        }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public long retryAfterSeconds() { return retryAfterSeconds; }
        public Metadata metadata() { return metadata; }
        public String detailCode() { return detailCode; }
    }
}

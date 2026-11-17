package com.dadot.server.domain.interview.support;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 공시 적격성·핵심 질문·새 AI 질문의 허용 범위를 같은 정책으로 관리한다. */
public final class InterviewQuestionPolicy {
    private static final String VERSION = "business-core-questions-v2-recency";
    private static final List<String> EXCLUDED_HEADINGS = List.of(
            "재무제표", "회계정책", "회계추정", "작성기준", "작성의기준", "주석", "감사보고서",
            "법적고지", "위험고지", "투자위험", "우발채무", "재무위험", "금융상품",
            "financialstatements", "accountingpolic", "notestothefinancial");
    private static final List<String> BOILERPLATE = List.of(
            "한국채택국제회계기준", "기업회계기준", "국제회계기준", "재무제표작성",
            "회계정책을적용", "회계정책은다음", "회계정책의변경", "동일한회계정책", "회계정책을변경",
            "투자판단의책임", "투자자의책임", "법적책임을지지", "이용에따른책임", "미래예측정보", "미래예측진술");
    private static final List<String> BUSINESS_WORDS = List.of(
            "사업", "제품", "서비스", "판매", "고객", "시장", "물류", "생산", "제조", "유통", "수출", "매출", "채널",
            "연구개발", "투자", "계약", "자동화", "직원", "인력", "교육", "채용", "조직");
    private static final List<String> UNSUPPORTED_QUESTION_WORDS = List.of(
            "재무제표", "회계정책", "회계처리", "공시", "보고서", "경쟁사", "경쟁업체", "약점", "단점", "리스크");
    private static final List<String> BRIDGES = List.of("비추어", "바탕으로", "고려하여", "고려해", "상황에서");

    private enum Topic { OVERVIEW, PRODUCT, GROWTH, PEOPLE }

    public record Core(String id, String type, String question, Pattern intent, List<Topic> topics) {
        public Map<String, String> prompt() {
            return Map.of("id", id, "type", type, "question", question);
        }
    }

    private static final Pattern MOTIVATION = Pattern.compile(
            "(?:지원|선택|관심).*(?:이유|계기|동기|기준)|왜.*(?:지원|선택|관심)");
    private static final Pattern INTEREST = Pattern.compile(
            "(?:회사|사업|제품|서비스|활동|변화|분야).*(?:관심|인상|궁금|생각|의견)|(?:관심|인상|궁금).*(?:사업|제품|서비스|활동|분야)");
    private static final Pattern STRENGTH = Pattern.compile("매력|강점");
    private static final Pattern ROLE = Pattern.compile("(?:입사|지원한직무|이직무).*(?:업무|역할|배우|일)");
    private static final Pattern CONTRIBUTION = Pattern.compile(
            "(?:강점|경험).*(?:활용|기여|도움)|(?:활용|기여|도움).*(?:강점|경험)");
    private static final List<Core> CORES = List.of(
            new Core("motivation-1", "회사 지원동기", "우리 회사에 지원한 이유는 무엇인가요?", MOTIVATION,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT, Topic.GROWTH)),
            new Core("motivation-2", "회사 지원동기", "우리 회사에 관심을 갖게 된 계기는 무엇인가요?", MOTIVATION,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT, Topic.GROWTH)),
            new Core("interest-1", "사업 관심", "우리 회사의 사업 중 어떤 부분이 인상 깊었나요?", INTEREST,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT, Topic.GROWTH)),
            new Core("interest-2", "사업 관심", "우리 회사의 제품이나 서비스 중 무엇이 궁금한가요?", INTEREST,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT)),
            new Core("strength-1", "강점 인식", "우리 회사 사업에서 매력적으로 느낀 점은 무엇인가요?", STRENGTH,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT)),
            new Core("strength-2", "강점 인식", "우리 회사 제품에서 어떤 점이 강점이라고 생각하나요?", STRENGTH,
                    List.of(Topic.PRODUCT)),
            new Core("role-1", "직무 이해", "입사 후 어떤 일을 해보고 싶나요?", ROLE,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT, Topic.PEOPLE)),
            new Core("role-2", "직무 이해", "입사 후 어떤 업무를 배우고 싶나요?", ROLE,
                    List.of(Topic.OVERVIEW, Topic.PRODUCT, Topic.PEOPLE)),
            new Core("contribution-1", "기여", "본인의 어떤 강점을 회사에서 활용하고 싶나요?", CONTRIBUTION,
                    List.of(Topic.OVERVIEW, Topic.GROWTH, Topic.PEOPLE)),
            new Core("contribution-2", "기여", "본인의 경험을 우리 회사에서 어떻게 활용하고 싶나요?", CONTRIBUTION,
                    List.of(Topic.OVERVIEW, Topic.GROWTH, Topic.PEOPLE))
    );

    private InterviewQuestionPolicy() {}

    public static String signature() {
        return VERSION + EXCLUDED_HEADINGS + BOILERPLATE + BUSINESS_WORDS
                + UNSUPPORTED_QUESTION_WORDS + BRIDGES + CORES;
    }

    public static boolean eligible(Section section) {
        String heading = normalize(section.heading());
        String body = normalize(section.content());
        if (section.content() == null || section.content().isBlank()
                || EXCLUDED_HEADINGS.stream().anyMatch(heading::contains)
                || BOILERPLATE.stream().anyMatch(body::contains)) return false;
        if (body.contains("추정") && body.contains("실제") && body.contains("다를")
                && (body.contains("보고기간말") || body.contains("가정"))) return false;
        return body.codePoints().filter(Character::isLetter).count() >= 4
                && BUSINESS_WORDS.stream().anyMatch(body::contains);
    }

    public static Input prepare(Input original) {
        var eligible = original.sections().stream().filter(InterviewQuestionPolicy::eligible).toList();
        if (eligible.isEmpty()) throw new Failure("CONTENT_UNAVAILABLE", false);
        Input clean = withSections(original, eligible);
        Core core = core(clean);
        return withSections(clean, eligible.stream().filter(s -> matches(core, s)).toList());
    }

    public static Core core(Input input) {
        List<String> history = input.previousQuestions() == null ? List.of() : input.previousQuestions();
        return CORES.stream().filter(c -> input.sections().stream().anyMatch(s -> eligible(s) && matches(c, s)))
                // 횟수보다 최근 출제 순서를 우선한다. 표현이 달라도 같은 의도는 같은 유형으로 본다.
                .min(Comparator.comparingInt((Core c) -> mostRecentPosition(history, c.type())).reversed()
                        .thenComparingLong(c -> history.stream().filter(q -> typeOf(q).equals(c.type())).count())
                        .thenComparing(c -> history.stream().anyMatch(q -> normalize(q).equals(normalize(c.question())))))
                .orElseThrow(() -> new Failure("CONTENT_UNAVAILABLE", false));
    }

    public static Generated validateQuestion(Generated generated, Input input) {
        String text = normalize(generated.question());
        String job = input.job() == null ? "" : normalize(input.job().jobName());
        if (usesJobName(generated.question(), text, job)) throw unsuitable("QUESTION_POLICY_JOB_NAME");
        if (UNSUPPORTED_QUESTION_WORDS.stream().anyMatch(text::contains)) {
            throw unsuitable("QUESTION_POLICY_UNSUPPORTED_CONTEXT");
        }
        List<String> history = input.previousQuestions() == null ? List.of() : input.previousQuestions();
        String type = typeOf(generated.question());
        if (history.stream().anyMatch(q -> questionKey(q).equals(questionKey(generated.question())))
                || (!history.isEmpty() && !type.isEmpty() && type.equals(typeOf(history.getFirst())))) {
            throw unsuitable("QUESTION_POLICY_RECENT_DUPLICATE");
        }
        Core core = core(input);
        if ((core.type().equals("회사 지원동기") || core.type().equals("직무 이해"))
                && BRIDGES.stream().anyMatch(text::contains)) throw unsuitable("QUESTION_POLICY_FORCED_BRIDGE");
        if (!core.intent().matcher(text).find() || !core.type().equals(type)) {
            throw unsuitable("QUESTION_POLICY_CORE_MISMATCH");
        }
        return generated;
    }

    private static Failure unsuitable(String reason) {
        return new Failure("QUESTION_GENERATION_FAILED", false, 0, null, reason);
    }

    private static boolean usesJobName(String question, String compact, String job) {
        if (job.length() < 2) return false;
        if (compact.contains(job + "직무") || compact.contains(job + "업무") || compact.contains(job + "담당")) return true;
        // '개발' 지원자에게 '연구개발 활동'을 묻는 정상 사업 질문까지 부분 일치로 막지 않는다.
        var token = new StringBuilder();
        job.codePoints().forEach(cp -> {
            if (!token.isEmpty()) token.append("\\s*");
            token.append(Pattern.quote(new String(Character.toChars(cp))));
        });
        String raw = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return Pattern.compile("(?U)(?<![\\p{L}\\p{N}])" + token
                + "(?=$|[\\s,.?!。？！]|[은는이가을를에의와과로])").matcher(raw).find();
    }

    private static Input withSections(Input input, List<Section> sections) {
        return new Input(input.companyName(), input.job(), input.sources(), sections,
                input.experiences(), input.previousQuestions());
    }

    private static boolean matches(Core core, Section section) {
        String text = normalize(section.heading()) + normalize(section.content());
        return core.topics().stream().anyMatch(topic -> switch (topic) {
            case OVERVIEW -> text.contains("사업");
            case PRODUCT -> List.of("제품", "서비스", "판매", "고객", "시장", "물류", "생산", "제조", "유통", "수출", "매출", "채널")
                    .stream().anyMatch(text::contains);
            case GROWTH -> List.of("연구개발", "투자", "계약", "자동화", "신규사업").stream().anyMatch(text::contains);
            case PEOPLE -> List.of("직원", "인력", "교육", "채용", "조직").stream().anyMatch(text::contains);
        });
    }

    private static String typeOf(String question) {
        String text = normalize(question);
        if (CONTRIBUTION.matcher(text).find()) return "기여";
        if (ROLE.matcher(text).find()) return "직무 이해";
        if (MOTIVATION.matcher(text).find()) return "회사 지원동기";
        if (STRENGTH.matcher(text).find()) return "강점 인식";
        if (INTEREST.matcher(text).find()) return "사업 관심";
        return "";
    }

    private static int mostRecentPosition(List<String> history, String type) {
        for (int i = 0; i < history.size(); i++) {
            if (type.equals(typeOf(history.get(i)))) return i;
        }
        return history.size();
    }

    private static String questionKey(String question) {
        return normalize(question).replaceAll("[\\p{P}\\p{S}]", "");
    }

    private static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", "");
    }
}

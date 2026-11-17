package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.support.InterviewQuestionPolicy;
import com.dadot.server.domain.interview.support.InterviewSourcePlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewQuestionPolicyTest {
    private static final Section AUTOMATION = new Section(1, 1, "주요계약 및 연구개발활동",
            "TES물류기술연구소는 AI와 로봇을 물류센터 현장에 적용해 자동화와 지능화를 추진합니다.");
    private static final Section ACCOUNTING = new Section(2, 2, "연결재무제표 작성기준",
            "보고기간말 경영진의 최선의 판단을 기준으로 한 추정치와 가정이 실제 환경과 다를 경우 실제 결과는 다를 수 있습니다.");

    @Test
    void rejectsTheScreenshotAccountingParagraphEvenForAnAccountingApplicant() {
        assertFalse(InterviewQuestionPolicy.eligible(ACCOUNTING));
        var input = input("회계", List.of(ACCOUNTING), List.of());
        assertEquals("CONTENT_UNAVAILABLE", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.prepare(input)).code());
    }

    @Test
    void rejectsTheScreenshotAutomationToHrMotivationBridge() {
        var input = InterviewQuestionPolicy.prepare(input("인사", List.of(AUTOMATION), List.of()));
        var generated = generated("회사가 물류 자동화를 추진하는 점에 비추어, 인사 직무에 관심을 가진 이유를 말씀해 주세요.");
        assertEquals("QUESTION_POLICY_JOB_NAME", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated, input)).reason());
    }

    @Test
    void allowsPlainMotivationWithoutForcingAnAutomationPrefix() {
        var input = InterviewQuestionPolicy.prepare(input("인사", List.of(AUTOMATION), List.of()));
        var generated = generated("우리 회사에 지원한 이유는 무엇인가요?");
        assertSame(generated, InterviewQuestionPolicy.validateQuestion(generated, input));
        assertEquals("회사 지원동기", InterviewQuestionPolicy.core(input).type());
    }

    @Test
    void permitsAnAutomationInterestQuestionWhenThatCoreTypeIsSelected() {
        var input = InterviewQuestionPolicy.prepare(input("인사", List.of(AUTOMATION),
                List.of("우리 회사에 지원한 이유는 무엇인가요?")));
        assertEquals("사업 관심", InterviewQuestionPolicy.core(input).type());
        assertDoesNotThrow(() -> InterviewQuestionPolicy.validateQuestion(
                generated("우리 회사의 물류 자동화 활동 중 어떤 점이 인상 깊었나요?"), input));
    }

    @Test
    void doesNotConfuseABusinessCompoundWithTheApplicantsJobName() {
        var input = InterviewQuestionPolicy.prepare(input("개발", List.of(AUTOMATION),
                List.of("우리 회사에 지원한 이유는 무엇인가요?")));
        assertDoesNotThrow(() -> InterviewQuestionPolicy.validateQuestion(
                generated("우리 회사의 연구개발 활동 중 어떤 점이 인상 깊었나요?"), input));
        assertEquals("QUESTION_POLICY_JOB_NAME", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated("개발 직무에서 어떤 사업에 관심이 있나요?"), input)).reason());
    }

    @ParameterizedTest
    @CsvSource({
            "'재무제표 작성기준','회계 추정과 가정에 관한 설명입니다.'",
            "'주석','회사의 제품 매출과 회계정책을 설명합니다.'",
            "'사업의 개요','회사는 한국채택국제회계기준에 따라 재무제표를 작성합니다.'",
            "'위험 고지','이 사업에 대한 투자판단의 책임은 투자자에게 있습니다.'",
            "'시장','단위: 백만원'"
    })
    void excludesBoilerplateByHeadingOrActualContent(String heading, String content) {
        assertFalse(InterviewQuestionPolicy.eligible(new Section(1, 1, heading, content)));
    }

    @Test
    void doesNotBlacklistBusinessInformationJustBecauseItMentionsAFiscalYear() {
        assertTrue(InterviewQuestionPolicy.eligible(new Section(1, 1, "주요 제품 및 서비스",
                "이번 회계연도에는 메모리 제품을 국내외 고객에게 판매했습니다.")));
        assertTrue(InterviewQuestionPolicy.eligible(new Section(2, 1, "주요 제품 및 서비스",
                "회사는 회계정책 관리 소프트웨어 제품을 고객에게 판매합니다.")));
    }

    @Test
    void doesNotReintroduceExcludedNeighborsOrForceOneSectionFromEveryReport() {
        var good = new Section(3, 1, "주요 제품", "회사는 메모리 제품을 국내외 고객에게 판매합니다.");
        var selected = new InterviewSourcePlanner().select(List.of(List.of(ACCOUNTING, good),
                List.of(new Section(4, 2, ACCOUNTING.heading(), ACCOUNTING.content())),
                List.of(new Section(5, 3, ACCOUNTING.heading(), ACCOUNTING.content()))), "회계", List.of());
        assertEquals(List.of(good), selected);
    }

    @Test
    void rotatesCoreTypesFromRecentHistoryAndPreservesExperienceInput() {
        var section = new Section(3, 1, "사업의 개요", "회사는 제품을 판매하고 신규 사업에 투자하며 직원 교육을 운영합니다.");
        var history = new ArrayList<String>();
        var types = new HashSet<String>();
        for (int i = 0; i < 5; i++) {
            var original = input("생산기술", List.of(section), history);
            var prepared = InterviewQuestionPolicy.prepare(original);
            var core = InterviewQuestionPolicy.core(prepared);
            types.add(core.type());
            assertEquals(original.job(), prepared.job());
            assertEquals(original.experiences(), prepared.experiences());
            assertEquals(original.previousQuestions(), prepared.previousQuestions());
            assertDoesNotThrow(() -> InterviewQuestionPolicy.validateQuestion(generated(core.question()), prepared));
            history.add(0, core.question());
        }
        assertEquals(5, types.size());
    }

    @Test
    void rejectsChangedIntentUnsupportedComparisonAndForcedBridge() {
        var input = InterviewQuestionPolicy.prepare(input("인사", List.of(AUTOMATION), List.of()));
        assertEquals("QUESTION_POLICY_CORE_MISMATCH", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated("입사 후 어떤 일을 해보고 싶나요?"), input)).reason());
        assertEquals("QUESTION_POLICY_UNSUPPORTED_CONTEXT", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated("경쟁사보다 우리 회사에 지원한 이유는 무엇인가요?"), input)).reason());
        assertEquals("QUESTION_POLICY_FORCED_BRIDGE", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated("자동화 상황에 비추어 우리 회사에 지원한 이유는 무엇인가요?"), input)).reason());
    }

    private Input input(String job, List<Section> sections, List<String> history) {
        return new Input("회사", new Job(null, job, true), List.of(), sections,
                List.of(new Material(1, 1, 1, "팀 활동", "학교 팀 활동에서 역할을 나눴습니다.")), List.copyOf(history));
    }

    @Test
    void doesNotAcceptAMotivationQuestionDisguisedAsBusinessInterest() {
        var prepared = InterviewQuestionPolicy.prepare(input("인사", List.of(AUTOMATION),
                List.of("우리 회사에 지원한 이유는 무엇인가요?")));
        assertEquals("사업 관심", InterviewQuestionPolicy.core(prepared).type());
        assertEquals("QUESTION_POLICY_RECENT_DUPLICATE", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(
                        generated("우리 회사 사업에 관심을 갖게 된 이유는 무엇인가요?"), prepared)).reason());
    }

    private Section allTopics() {
        return new Section(3, 1, "사업의 개요",
                "회사는 제품을 판매하고 신규 사업에 투자하며 직원 교육을 운영합니다.");
    }

    @Test
    void avoidsTheScreenshotMotivationParaphraseEvenWhenOtherTypesWereUsedMoreOften() {
        var history = List.of("SK하이닉스에 지원한 이유를 말씀해 주세요.",
                "입사 후 어떤 일을 해보고 싶나요?", "본인의 경험을 어떻게 활용하고 싶나요?",
                "우리 회사 제품의 강점은 무엇인가요?", "어떤 사업이 인상 깊었나요?",
                "입사 후 어떤 업무를 배우고 싶나요?", "본인의 강점을 어떻게 활용하고 싶나요?",
                "우리 회사 사업의 매력은 무엇인가요?", "어떤 제품이 궁금한가요?");
        var prepared = InterviewQuestionPolicy.prepare(input("인사", List.of(allTopics()), history));
        assertEquals("사업 관심", InterviewQuestionPolicy.core(prepared).type());
        var failure = assertThrows(Failure.class, () -> InterviewQuestionPolicy.validateQuestion(
                generated("SK하이닉스에 지원한 이유는 무엇인가요?"), prepared));
        assertEquals("QUESTION_POLICY_RECENT_DUPLICATE", failure.reason());
    }

    @Test
    void choosesTheOldestTypeWhenEveryTypeOccursTwiceInTheTenQuestionWindow() {
        var history = List.of("우리 회사에 지원한 이유는 무엇인가요?",
                "우리 회사 사업에서 인상 깊은 점은 무엇인가요?", "우리 회사 제품의 강점은 무엇인가요?",
                "입사 후 어떤 일을 해보고 싶나요?", "본인의 경험을 어떻게 활용하고 싶나요?",
                "우리 회사에 관심을 갖게 된 계기는 무엇인가요?", "어떤 제품이 궁금한가요?",
                "우리 회사 사업의 매력은 무엇인가요?", "입사 후 어떤 업무를 배우고 싶나요?",
                "본인의 강점을 어떻게 활용하고 싶나요?");
        var prepared = InterviewQuestionPolicy.prepare(input("인사", List.of(allTopics()), history));
        assertEquals("기여", InterviewQuestionPolicy.core(prepared).type());
    }

    @Test
    void cyclesAllFiveExistingTypesForNinetyGenerationsWithTheRealHistoryWindow() {
        var history = new ArrayList<String>();
        var cycle = new HashSet<String>();
        String previousType = "";
        for (int i = 0; i < 90; i++) {
            var prepared = InterviewQuestionPolicy.prepare(input("인사", List.of(allTopics()),
                    history.stream().limit(10).toList()));
            var core = InterviewQuestionPolicy.core(prepared);
            assertNotEquals(previousType, core.type());
            assertTrue(cycle.add(core.type()), "유형이 한 순환 안에서 반복됨: " + i);
            if (cycle.size() == 5) cycle.clear();
            // 문장 표면이 달라도 유형별 이력이 유지되는지 확인한다.
            history.add(0, core.question().replace("우리 회사", "SK하이닉스"));
            previousType = core.type();
        }
    }

    @Test
    void alternatesAvailableTypesWhenOnlyPeopleRelatedEvidenceExists() {
        var people = new Section(4, 1, "직원 교육", "직원 교육을 운영하고 인력 채용을 진행합니다.");
        var history = new ArrayList<String>();
        String previousType = "";
        for (int i = 0; i < 12; i++) {
            var prepared = InterviewQuestionPolicy.prepare(input("인사", List.of(people),
                    history.stream().limit(10).toList()));
            var core = InterviewQuestionPolicy.core(prepared);
            assertTrue(List.of("직무 이해", "기여").contains(core.type()));
            assertNotEquals(previousType, core.type());
            history.add(0, core.question());
            previousType = core.type();
        }
    }

    @Test
    void rejectsAnOlderExactQuestionDespitePunctuationChanges() {
        var history = List.of("어떤 제품이 궁금한가요?", "SK하이닉스에 지원한 이유는 무엇인가요?");
        var prepared = InterviewQuestionPolicy.prepare(input("인사", List.of(AUTOMATION), history));
        assertEquals("QUESTION_POLICY_RECENT_DUPLICATE", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(
                        generated("SK하이닉스에 지원한 이유는 무엇인가요？"), prepared)).reason());
    }

    private Generated generated(String question) {
        return new Generated(question, List.of(new Quote(1, 1, AUTOMATION.content(),
                "이 회사는 물류센터에 AI와 로봇을 적용하고 있어요.")));
    }
}

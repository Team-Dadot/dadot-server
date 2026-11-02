package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Job;
import com.dadot.server.domain.interview.model.InterviewGeneration.Material;
import com.dadot.server.domain.interview.model.InterviewPolicy;

import java.util.List;
import java.util.stream.IntStream;

public final class EvaluationFixtures {
    private EvaluationFixtures() {}
    public static List<InterviewPolicy.Criterion> rubric() {
        return IntStream.range(0, 4).mapToObj(i -> new InterviewPolicy.Criterion(
                InterviewPolicy.CODES.get(i), InterviewPolicy.LABELS.get(i), 25, "실제 답변의 근거와 구체성을 평가한다.")).toList();
    }
    public static Input input() {
        return new Input("평가기업", new Job(null, "백엔드", true), "서비스 확장에 어떻게 대응하겠습니까?",
                "저는 API 캐시를 도입해 응답 시간을 줄였습니다.",
                List.of(new EvidenceInput(1, 2, "사업보고서", "ANNUAL", java.time.LocalDate.of(2026, 3, 20),
                        java.time.LocalDate.of(2025, 1, 1), java.time.LocalDate.of(2025, 12, 31),
                        "서비스", "서비스를 확대합니다.", "서비스를 확대합니다.")),
                List.of(new Material(11, 21, 1, "캐시 개선", "응답 시간을 30% 줄였습니다.")), rubric());
    }
    public static Generated output(Input input, int score) {
        List<Score> criteria = InterviewPolicy.CODES.stream().map(code -> new Score(code, score,
                "질문의 요지와 수행한 행동을 설명했습니다.", "답변에 구체적인 행동이 드러납니다.",
                input.answer().substring(0, Math.min(input.answer().length(), 20)))).toList();
        List<Link> links = input.experiences().isEmpty() ? List.of() : List.of(new Link(
                input.experiences().getFirst().versionId(), input.experiences().getFirst().content(),
                "이 경험의 실제 결과를 다음 답변에서 연결해 주세요."));
        return new Generated("본인 역할과 결과를 연결해 주세요.", "행동의 설명은 좋지만 본인 담당 범위와 결과를 함께 보완해 주세요.",
                criteria, List.of("수행한 행동을 설명했습니다."), List.of("본인 담당 범위와 실제 결과를 보완해 주세요."), links);
    }
}
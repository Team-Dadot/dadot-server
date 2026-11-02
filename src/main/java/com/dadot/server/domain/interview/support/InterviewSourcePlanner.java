package com.dadot.server.domain.interview.support;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

@Component
public class InterviewSourcePlanner {
    private record Ranked(Section section, int rank) {}

    // 모든 저장 절을 점수화한다. 모델에는 관련 절과 인접 문맥을 제공한다.
    public List<Section> select(
            List<List<Section>> reports,
            String jobName,
            List<Material> materials
    ) {
        var terms = new HashSet<String>();

        String searchText = jobName + " " + String.join(
                " ",
                materials.stream().map(Material::title).toList()
        );

        for (String value : searchText.toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{N}]+")) {
            if (value.length() >= 2 && value.length() <= 30) {
                terms.add(value);
            }
        }

        var selected = new ArrayList<Section>();
        var included = new HashSet<Long>();

        for (List<Section> report : reports) {
            var ranked = report.stream()
                    .map(s -> new Ranked(s, rank(s, terms)))
                    .sorted(
                            Comparator.comparingInt(Ranked::rank).reversed()
                                    .thenComparingLong(r -> r.section().sectionId())
                    )
                    .toList();

            int characters = 0;
            int anchors = 0;

            for (Ranked candidate : ranked) {
                if (anchors >= 3) break;

                Section anchor = candidate.section();
                int index = report.indexOf(anchor);

                for (int i : new int[]{index, index - 1, index + 1}) {
                    if (i < 0 || i >= report.size()) continue;

                    Section section = report.get(i);

                    if (included.contains(section.sectionId())
                            || characters + section.content().length() > 16_384) {
                        continue;
                    }

                    included.add(section.sectionId());
                    selected.add(section);
                    characters += section.content().length();
                }

                anchors++;
            }
        }

        return List.copyOf(selected);
    }

    private int rank(Section section, HashSet<String> terms) {
        String heading = section.heading() == null
                ? ""
                : section.heading().toLowerCase(Locale.ROOT);
        String content = section.content().toLowerCase(Locale.ROOT);
        int score = 0;

        for (String term : terms) {
            if (heading.contains(term)) score += 20;
            if (content.contains(term)) score += 5;
        }

        for (String theme : List.of(
                "사업의 내용", "주요 제품", "주요제품",
                "연구개발", "시장", "전략", "경쟁"
        )) {
            if (heading.contains(theme)) score += 4;
            if (content.contains(theme)) score++;
        }

        return score;
    }
}
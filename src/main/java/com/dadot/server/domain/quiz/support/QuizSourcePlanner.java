package com.dadot.server.domain.quiz.support;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration.Input;
import com.dadot.server.domain.quiz.model.QuizGeneration.Section;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Component
public class QuizSourcePlanner {
    // 모든 블록은 primary로 한 번씩 읽는다. context는 문맥 보완용 중복 입력이다.
    private static final int PRIMARY_LIMIT = 24576;
    private static final int INPUT_LIMIT = 32768;

    public List<Input> plan(List<List<Section>> reports, int maxBatches) {
        if (reports.isEmpty() || reports.size() > 3 || reports.stream().anyMatch(List::isEmpty)) {
            throw QuizBank.unavailable();
        }
        var cursors = new int[reports.size()];
        var batches = new ArrayList<Input>();
        while (true) {
            var primary = new ArrayList<Section>();
            var adjacent = new ArrayList<Section>();
            int characters = 0;
            boolean advanced;
            do {
                advanced = false;
                for (int i = 0; i < reports.size(); i++) {
                    var rows = reports.get(i);
                    int index = cursors[i];
                    if (index >= rows.size()) continue;
                    var section = rows.get(index);
                    int size = section.text().length();
                    if (size == 0 || size > 8192) throw QuizBank.unavailable();
                    if (primary.size() >= 40 || characters + size > PRIMARY_LIMIT) continue;
                    primary.add(section);
                    if (index > 0) adjacent.add(rows.get(index - 1));
                    if (index + 1 < rows.size()) adjacent.add(rows.get(index + 1));
                    characters += size;
                    cursors[i]++;
                    advanced = true;
                }
            } while (advanced);
            if (primary.isEmpty()) break;
            var context = new LinkedHashMap<Long, Section>();
            var primaryIds = primary.stream().map(Section::sectionId).collect(java.util.stream.Collectors.toSet());
            for (var section : adjacent) {
                if (!primaryIds.contains(section.sectionId()) && !context.containsKey(section.sectionId())
                        && characters + section.text().length() <= INPUT_LIMIT) {
                    context.put(section.sectionId(), section);
                    characters += section.text().length();
                }
            }
            batches.add(new Input(primary, List.copyOf(context.values())));
            if (batches.size() > maxBatches) throw QuizBank.invalid();
        }
        if (batches.isEmpty()) throw QuizBank.unavailable();
        return List.copyOf(batches);
    }
}
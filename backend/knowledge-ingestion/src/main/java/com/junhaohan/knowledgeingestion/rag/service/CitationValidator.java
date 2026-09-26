package com.junhaohan.knowledgeingestion.rag.service;

import com.junhaohan.knowledgeingestion.rag.model.RagCitation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 校验模型回答中的引用编号，只保留本次证据对应的引用。 */
@Component
public class CitationValidator {

    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\[(\\d+)]");

    /** 清除无效引用，并按回答中的首次出现顺序返回有效引用。 */
    public ValidatedAnswer validate(String answer, List<RagCitation> availableCitations) {
        if (answer == null || answer.isBlank()) {
            return new ValidatedAnswer("", List.of());
        }

        Map<Integer, RagCitation> byRef = new HashMap<>();
        for (RagCitation citation : availableCitations) {
            byRef.put(citation.ref(), citation);
        }

        Set<Integer> usedRefs = new LinkedHashSet<>();
        StringBuffer cleanedAnswer = new StringBuffer();
        Matcher matcher = REFERENCE_PATTERN.matcher(answer);
        while (matcher.find()) {
            RagCitation citation = null;
            try {
                citation = byRef.get(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 超出整数范围的编号不属于本次证据。
            }
            if (citation == null) {
                matcher.appendReplacement(cleanedAnswer, "");
            } else {
                usedRefs.add(citation.ref());
                matcher.appendReplacement(cleanedAnswer, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(cleanedAnswer);

        List<RagCitation> citations = new ArrayList<>();
        for (int ref : usedRefs) {
            citations.add(byRef.get(ref));
        }
        return new ValidatedAnswer(cleanedAnswer.toString().strip(), List.copyOf(citations));
    }

    /** 已清理引用编号的回答及其有效出处。 */
    public record ValidatedAnswer(String answer, List<RagCitation> citations) {
    }
}
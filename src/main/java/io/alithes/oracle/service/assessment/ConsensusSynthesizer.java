package io.alithes.oracle.service.assessment;

import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.jev.JevResponse;
import io.alithes.oracle.client.jev.NoulQuestion;
import io.alithes.oracle.client.jev.QuestionSpec;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.SynthesisProperties;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.service.ResolutionEngine;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Evaluates top chronological evidence reports together in a single Jev call to determine
 * whether the latest news clearly establishes YES or NO.
 */
@Component
public class ConsensusSynthesizer {

    private static final DateTimeFormatter SYNTHESIS_DATE_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final DecisionEngine decisionEngine;
    private final SynthesisProperties synthesisProps;

    @Autowired
    public ConsensusSynthesizer(DecisionEngine decisionEngine, OracleProperties props) {
        this(decisionEngine, Objects.requireNonNull(props, "props must not be null").synthesis());
    }

    public ConsensusSynthesizer(DecisionEngine decisionEngine, SynthesisProperties synthesisProps) {
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine must not be null");
        this.synthesisProps = Objects.requireNonNull(synthesisProps, "synthesisProps must not be null");
    }

    public ConsensusSynthesis synthesize(Question q, List<ResolutionEngine.EvidenceWithAssessment> items) {
        Objects.requireNonNull(q, "question must not be null");
        List<ResolutionEngine.EvidenceWithAssessment> safeItems = items == null ? List.of() : items;

        List<ResolutionEngine.EvidenceWithAssessment> topItems = safeItems.stream()
                .filter(i -> i != null && i.evidence() != null && i.assessment() != null && !i.assessment().injectionFlag())
                .sorted(Comparator.comparingDouble(
                        (ResolutionEngine.EvidenceWithAssessment i) -> i.assessment().pRelevant()
                                * Math.max(i.assessment().pConfirms(), i.assessment().pDenies())).reversed())
                .limit(synthesisProps.maxReports())
                .sorted(Comparator.comparing(
                        (ResolutionEngine.EvidenceWithAssessment i) -> effectiveDate(i.evidence()),
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int maxExcerpt = synthesisProps.maxExcerptChars();
        List<Map<String, Object>> reports = new ArrayList<>(topItems.size());
        for (ResolutionEngine.EvidenceWithAssessment item : topItems) {
            Evidence ev = item.evidence();
            String excerpt = ev.excerpt() == null ? "" : ev.excerpt();
            if (excerpt.length() > maxExcerpt) {
                excerpt = excerpt.substring(0, maxExcerpt);
            }
            Instant date = effectiveDate(ev);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("date", date == null ? "" : SYNTHESIS_DATE_FORMAT.format(date));
            report.put("domain", ev.domain() == null ? "" : ev.domain());
            report.put("title", ev.title() == null ? "" : ev.title());
            report.put("fetchStatus", ev.fetchStatus().name());
            report.put("excerpt", excerpt);
            reports.add(report);
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", q.text());
        state.put("resolution_criteria", q.resolutionCriteria() == null ? "" : q.resolutionCriteria());
        state.put("reports", reports);

        Map<String, QuestionSpec> questions = new LinkedHashMap<>();
        questions.put("consensus_yes", new NoulQuestion(
                "Based on the most recent reports in `reports`, is the answer to `question` YES?"
        ));
        questions.put("consensus_no", new NoulQuestion(
                "Based on the most recent reports in `reports`, is the answer to `question` NO?"
        ));
        questions.put("consensus_clear", new NoulQuestion(
                "Do the most recent reports in `reports` give a clear, consistent answer to `question` "
                        + "(as opposed to being genuinely conflicting or inconclusive)?"
        ));

        JevResponse response = decisionEngine.evaluate(state, questions);
        return new ConsensusSynthesis(
                response.model() == null ? "" : response.model(),
                response.noulProbability("consensus_yes"),
                response.noulProbability("consensus_no"),
                response.noulProbability("consensus_clear")
        );
    }

    private static Instant effectiveDate(Evidence ev) {
        return ev.publishedAt() != null ? ev.publishedAt() : ev.seenAt();
    }
}

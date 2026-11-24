package io.alithes.oracle.service.resolution;

import io.alithes.oracle.domain.QuestionStatus;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rule 1: Marks the question {@link QuestionStatus#DISPUTED} when both confirming and denying
 * evidence sums exceed {@code disputeThreshold}.
 */
@Component
@Order(1)
public class ContradictionDisputeRule implements ResolutionRule {

    @Override
    public Optional<RuleOutcome> evaluate(ResolutionContext context) {
        ClusterSummary summary = context.summary();
        double threshold = context.policy().disputeThreshold();
        if (summary.confirmSum() >= threshold && summary.denySum() >= threshold) {
            String details = String.format(Locale.ROOT,
                    "Conflicting evidence above dispute threshold (confirmSum=%.3f, denySum=%.3f)",
                    summary.confirmSum(), summary.denySum());
            return Optional.of(new RuleOutcome.Resolved(
                    context.buildResolution(
                            QuestionStatus.DISPUTED,
                            context.scoring().disputedConfidence(),
                            details)));
        }
        return Optional.empty();
    }
}

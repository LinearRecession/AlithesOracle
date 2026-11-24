package io.alithes.oracle.service.resolution;

import io.alithes.oracle.domain.QuestionStatus;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rule 6: Terminal post-deadline rule resolving {@link QuestionStatus#RESOLVED_NO} when neither
 * confirming quorum nor partial-dispute threshold was met.
 */
@Component
@Order(6)
public class PostDeadlineNoResolutionRule implements ResolutionRule {

    @Override
    public Optional<RuleOutcome> evaluate(ResolutionContext context) {
        ClusterSummary summary = context.summary();
        double noConfidence = Math.max(
                context.scoring().minNoConfidence(),
                Math.min(
                        context.scoring().maxResolutionConfidence(),
                        1.0 - (summary.confirmSum() / context.policy().minConfirmSum())));
        String details = String.format(Locale.ROOT,
                "Deadline passed without confirming quorum (confirmSum=%.3f, denySum=%.3f)",
                summary.confirmSum(), summary.denySum());
        return Optional.of(new RuleOutcome.Resolved(
                context.buildResolution(QuestionStatus.RESOLVED_NO, noConfidence, details)));
    }
}

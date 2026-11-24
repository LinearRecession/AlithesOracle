package io.alithes.oracle.service.resolution;

import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.domain.QuestionStatus;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rule 2: Resolves {@link QuestionStatus#RESOLVED_YES} immediately when cluster quorum, trust,
 * confirmation sum, and deny-ratio constraints are all satisfied.
 */
@Component
@Order(2)
public class ConfirmingQuorumRule implements ResolutionRule {

    @Override
    public Optional<RuleOutcome> evaluate(ResolutionContext context) {
        ClusterSummary summary = context.summary();
        PolicyProperties policy = context.policy();
        if (summary.independentClusters() >= policy.minConfirmClusters()
                && summary.trustedConfirmClusters() >= policy.minTrustedClusters()
                && summary.confirmSum() >= policy.minConfirmSum()
                && summary.denySum() < policy.maxDenyRatio() * summary.confirmSum()) {
            double confidence = Math.min(
                    context.scoring().maxResolutionConfidence(),
                    summary.confirmSum() / context.scoring().confidenceScaleSum());
            String details = String.format(Locale.ROOT,
                    "Confirming quorum met (%d independent clusters, %d trusted, confirmSum=%.3f, denySum=%.3f)",
                    summary.independentClusters(),
                    summary.trustedConfirmClusters(),
                    summary.confirmSum(),
                    summary.denySum());
            return Optional.of(new RuleOutcome.Resolved(
                    context.buildResolution(QuestionStatus.RESOLVED_YES, confidence, details)));
        }
        return Optional.empty();
    }
}

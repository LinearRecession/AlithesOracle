package io.alithes.oracle.service.resolution;

import io.alithes.oracle.domain.QuestionStatus;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rule 4: Post-deadline gate returning {@link QuestionStatus#UNRESOLVABLE} when upstream scan
 * failures exceed {@code maxFailedScanRatio}.
 */
@Component
@Order(4)
public class CoverageGapUnresolvableRule implements ResolutionRule {

    @Override
    public Optional<RuleOutcome> evaluate(ResolutionContext context) {
        long totalScans = context.scanLogs().size();
        long failedScans = context.scanLogs().stream()
                .filter(s -> s != null && !s.gdeltOk())
                .count();
        double failedRatio = (totalScans == 0) ? 1.0 : (double) failedScans / totalScans;
        if (failedRatio > context.policy().maxFailedScanRatio()) {
            return Optional.of(new RuleOutcome.Resolved(
                    context.buildResolution(
                            QuestionStatus.UNRESOLVABLE,
                            0.0,
                            "GDELT coverage gap: failed scan ratio=" + failedRatio)));
        }
        return Optional.empty();
    }
}

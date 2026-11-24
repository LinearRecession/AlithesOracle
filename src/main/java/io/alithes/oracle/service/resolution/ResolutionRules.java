package io.alithes.oracle.service.resolution;

import java.util.List;

/**
 * Factory providing the default ordered chain of {@link ResolutionRule} evaluators for standalone usage and tests.
 */
public final class ResolutionRules {

    private ResolutionRules() {
    }

    public static List<ResolutionRule> defaultChain() {
        return List.of(
                new ContradictionDisputeRule(),
                new ConfirmingQuorumRule(),
                new ActiveBeforeDeadlineRule(),
                new CoverageGapUnresolvableRule(),
                new PostDeadlinePartialDisputeRule(),
                new PostDeadlineNoResolutionRule()
        );
    }
}

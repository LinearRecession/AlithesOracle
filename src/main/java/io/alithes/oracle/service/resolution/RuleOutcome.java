package io.alithes.oracle.service.resolution;

import io.alithes.oracle.domain.Resolution;

/**
 * Outcome produced by a {@link ResolutionRule} node in the resolution policy chain.
 */
public sealed interface RuleOutcome permits RuleOutcome.Resolved, RuleOutcome.Defer {

    record Resolved(Resolution resolution) implements RuleOutcome {
    }

    record Defer() implements RuleOutcome {
    }
}

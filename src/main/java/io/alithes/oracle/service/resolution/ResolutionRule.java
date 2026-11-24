package io.alithes.oracle.service.resolution;

import java.util.Optional;

/**
 * Ordered Chain of Responsibility rule deciding whether to resolve, defer, or pass evaluation
 * to the next policy rule.
 */
public interface ResolutionRule {

    Optional<RuleOutcome> evaluate(ResolutionContext context);
}

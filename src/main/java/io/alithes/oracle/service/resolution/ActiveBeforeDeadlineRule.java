package io.alithes.oracle.service.resolution;

import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rule 3: Keeps the question active while {@code now} is before {@code deadline + graceHours}.
 */
@Component
@Order(3)
public class ActiveBeforeDeadlineRule implements ResolutionRule {

    @Override
    public Optional<RuleOutcome> evaluate(ResolutionContext context) {
        if (context.now().isBefore(context.question().finalAt())) {
            return Optional.of(new RuleOutcome.Defer());
        }
        return Optional.empty();
    }
}

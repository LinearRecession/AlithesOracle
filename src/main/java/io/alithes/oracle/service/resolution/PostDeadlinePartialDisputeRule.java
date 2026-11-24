package io.alithes.oracle.service.resolution;

import io.alithes.oracle.domain.QuestionStatus;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Rule 5: Post-deadline partial-evidence gate marking the question {@link QuestionStatus#DISPUTED}
 * when {@code confirmSum >= postDeadlineDisputeConfirmSum}.
 */
@Component
@Order(5)
public class PostDeadlinePartialDisputeRule implements ResolutionRule {

    @Override
    public Optional<RuleOutcome> evaluate(ResolutionContext context) {
        if (context.summary().confirmSum() >= context.policy().postDeadlineDisputeConfirmSum()) {
            return Optional.of(new RuleOutcome.Resolved(
                    context.buildResolution(
                            QuestionStatus.DISPUTED,
                            context.scoring().disputedConfidence(),
                            "Deadline passed with partial evidence below YES quorum")));
        }
        return Optional.empty();
    }
}

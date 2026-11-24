package io.alithes.oracle.service.assessment;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.config.SynthesisProperties;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.service.ResolutionEngine;
import io.alithes.oracle.util.UrlNormalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Post-evaluation adjudicator that invokes {@link ConsensusSynthesizer} on non-contradictory
 * {@link QuestionStatus#DISPUTED} outcomes to settle clear YES/NO cases automatically.
 */
@Component
public class ConsensusAdjudicator {

    private static final Logger log = LoggerFactory.getLogger(ConsensusAdjudicator.class);

    private final PolicyProperties policyProps;
    private final SynthesisProperties synthesisProps;
    private final ConsensusSynthesizer synthesizer;

    @Autowired
    public ConsensusAdjudicator(OracleProperties props, ConsensusSynthesizer synthesizer) {
        this(
                Objects.requireNonNull(props, "props must not be null").policy(),
                props.synthesis(),
                synthesizer
        );
    }

    public ConsensusAdjudicator(
            PolicyProperties policyProps,
            SynthesisProperties synthesisProps,
            ConsensusSynthesizer synthesizer
    ) {
        this.policyProps = Objects.requireNonNull(policyProps, "policyProps must not be null");
        this.synthesisProps = Objects.requireNonNull(synthesisProps, "synthesisProps must not be null");
        this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer must not be null");
    }

    public Resolution adjudicate(
            Question q,
            List<ResolutionEngine.EvidenceWithAssessment> items,
            Resolution resolution,
            Instant now
    ) {
        if (!policyProps.jevSynthesisEnabled() || resolution.outcome() != QuestionStatus.DISPUTED) {
            return resolution;
        }
        boolean trueContradiction = resolution.confirmSum() >= policyProps.disputeThreshold()
                && resolution.denySum() >= policyProps.disputeThreshold();
        if (trueContradiction
                || resolution.denySum() >= policyProps.maxDenyRatio() * Math.max(resolution.confirmSum(), 0.001)) {
            return resolution;
        }

        Set<String> relevantDomains = new HashSet<>();
        Set<String> confirmingDomains = new HashSet<>();
        List<UUID> confirmingIds = new ArrayList<>();
        double minRel = synthesisProps.minRelevantProbability();
        double minStrong = synthesisProps.minStrongStanceProbability();
        int maxIds = synthesisProps.maxSupportingIds();

        items.stream()
                .filter(i -> !i.assessment().injectionFlag()
                        && i.assessment().pRelevant() >= minRel
                        && (i.assessment().pConfirms() >= minStrong || i.assessment().pDenies() >= minStrong))
                .sorted(Comparator.comparingDouble(
                        (ResolutionEngine.EvidenceWithAssessment i) -> i.assessment().pRelevant()
                                * Math.max(i.assessment().pConfirms(), i.assessment().pDenies()))
                        .reversed())
                .forEach(i -> {
                    String dom = UrlNormalizer.stripHostPrefixes(i.evidence().domain());
                    if (!dom.isBlank()) {
                        relevantDomains.add(dom);
                        if (i.assessment().pConfirms() >= minStrong
                                && confirmingDomains.add(dom)
                                && confirmingIds.size() < maxIds) {
                            confirmingIds.add(i.evidence().id());
                        }
                    }
                });

        int minDomains = synthesisProps.minDistinctDomains();
        if (relevantDomains.size() < minDomains) {
            return resolution;
        }

        try {
            ConsensusSynthesis s = synthesizer.synthesize(q, items);
            log.info(
                    "Jev consensus synthesis for question {}: pYes={}, pNo={}, pClear={} (domains={}, confirmSum={}, denySum={})",
                    q.id(), s.pConsensusYes(), s.pConsensusNo(), s.pSufficient(),
                    relevantDomains.size(), resolution.confirmSum(), resolution.denySum());

            double minClear = synthesisProps.minClearProbability();
            double minConsensus = synthesisProps.minConsensusProbability();
            double maxOpposing = synthesisProps.maxOpposingProbability();
            double maxConfidence = synthesisProps.maxConfidence();

            if (confirmingDomains.size() >= minDomains
                    && s.pSufficient() >= minClear
                    && s.pConsensusYes() >= minConsensus
                    && s.pConsensusNo() <= maxOpposing) {
                double confidence = Math.min(maxConfidence, s.pConsensusYes() * s.pSufficient());
                List<UUID> supportingIds = resolution.supportingEvidenceIds().isEmpty()
                        ? List.copyOf(confirmingIds)
                        : resolution.supportingEvidenceIds();
                String details = String.format(Locale.ROOT,
                        "Jev consensus synthesis confirmed YES (%d distinct domains, pYes=%.2f, pNo=%.2f, pClear=%.2f, confirmSum=%.3f)",
                        confirmingDomains.size(), s.pConsensusYes(), s.pConsensusNo(), s.pSufficient(),
                        resolution.confirmSum());
                return new Resolution(
                        resolution.questionId(),
                        QuestionStatus.RESOLVED_YES,
                        confidence,
                        resolution.confirmSum(),
                        resolution.denySum(),
                        confirmingDomains.size(),
                        supportingIds,
                        resolution.policyVersion(),
                        s.model().isBlank() ? resolution.model() : s.model(),
                        details,
                        now);
            }
            if (s.pSufficient() >= minClear
                    && s.pConsensusNo() >= minConsensus
                    && s.pConsensusYes() <= maxOpposing) {
                double confidence = Math.min(maxConfidence, s.pConsensusNo() * s.pSufficient());
                String details = String.format(Locale.ROOT,
                        "Jev consensus synthesis confirmed NO (pYes=%.2f, pNo=%.2f, pClear=%.2f, confirmSum=%.3f)",
                        s.pConsensusYes(), s.pConsensusNo(), s.pSufficient(), resolution.confirmSum());
                return new Resolution(
                        resolution.questionId(),
                        QuestionStatus.RESOLVED_NO,
                        confidence,
                        resolution.confirmSum(),
                        resolution.denySum(),
                        resolution.independentClusters(),
                        List.of(),
                        resolution.policyVersion(),
                        s.model().isBlank() ? resolution.model() : s.model(),
                        details,
                        now);
            }
        } catch (Exception e) {
            log.warn("Jev consensus synthesis failed for question {}; keeping DISPUTED resolution: {}",
                    q.id(), e.getMessage());
        }
        return resolution;
    }
}

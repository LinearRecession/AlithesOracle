package io.alithes.oracle.service;

import io.alithes.oracle.client.GdeltArticle;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.db.AssessmentRepository;
import io.alithes.oracle.db.EvidenceRepository;
import io.alithes.oracle.db.QuestionRepository;
import io.alithes.oracle.db.ResolutionRepository;
import io.alithes.oracle.db.ScanLogRepository;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.service.ArticleDiscoveryService.DiscoveryResult;
import io.alithes.oracle.service.assessment.ConsensusAdjudicator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates one scan cycle for an active question across discovery, evidence processing,
 * deterministic resolution evaluation, consensus synthesis adjudication, and webhook delivery.
 */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    private final Clock clock;
    private final QuestionRepository questionRepository;
    private final EvidenceRepository evidenceRepository;
    private final AssessmentRepository assessmentRepository;
    private final ResolutionRepository resolutionRepository;
    private final ScanLogRepository scanLogRepository;
    private final ArticleDiscoveryService discoveryService;
    private final EvidenceProcessingPipeline processingPipeline;
    private final ResolutionEngine resolutionEngine;
    private final ConsensusAdjudicator consensusAdjudicator;
    private final ResolutionNotifier resolutionNotifier;
    private final OracleProperties props;
    private final TransactionTemplate transactionTemplate;
    private final Set<UUID> inFlightScans = ConcurrentHashMap.newKeySet();

    public ScanService(
            Clock clock,
            QuestionRepository questionRepository,
            EvidenceRepository evidenceRepository,
            AssessmentRepository assessmentRepository,
            ResolutionRepository resolutionRepository,
            ScanLogRepository scanLogRepository,
            ArticleDiscoveryService discoveryService,
            EvidenceProcessingPipeline processingPipeline,
            ResolutionEngine resolutionEngine,
            ConsensusAdjudicator consensusAdjudicator,
            ResolutionNotifier resolutionNotifier,
            OracleProperties props,
            TransactionTemplate transactionTemplate
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.questionRepository = Objects.requireNonNull(questionRepository, "questionRepository");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.assessmentRepository = Objects.requireNonNull(assessmentRepository, "assessmentRepository");
        this.resolutionRepository = Objects.requireNonNull(resolutionRepository, "resolutionRepository");
        this.scanLogRepository = Objects.requireNonNull(scanLogRepository, "scanLogRepository");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.processingPipeline = Objects.requireNonNull(processingPipeline, "processingPipeline");
        this.resolutionEngine = Objects.requireNonNull(resolutionEngine, "resolutionEngine");
        this.consensusAdjudicator = Objects.requireNonNull(consensusAdjudicator, "consensusAdjudicator");
        this.resolutionNotifier = Objects.requireNonNull(resolutionNotifier, "resolutionNotifier");
        this.props = Objects.requireNonNull(props, "props");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Executes one scan pass for {@code questionId} if the question exists and is not in a final status.
     */
    public void scanQuestion(UUID questionId) {
        Objects.requireNonNull(questionId, "questionId must not be null");
        if (!inFlightScans.add(questionId)) {
            log.info("Skipping scan for question {} because a scan is already in flight", questionId);
            return;
        }
        try {
            scanQuestionInternal(questionId);
        } finally {
            inFlightScans.remove(questionId);
        }
    }

    private void scanQuestionInternal(UUID questionId) {
        Question q = questionRepository.findById(questionId).orElse(null);
        if (q == null || q.status().isFinal()) {
            return;
        }

        Instant startedAt = clock.instant();
        Instant scanFrom = computeScanFrom(q);
        Instant scanTo = earlierOf(startedAt, q.finalAt());

        DiscoveryResult discovered = discoveryService.discover(q, scanFrom, scanTo);
        int newEvidence = persistNewEvidence(q, discovered.uniqueArticles(), clock.instant());
        processingPipeline.processPending(q);

        Instant finishedAt = clock.instant();
        String errorSummary = discovered.errors().isEmpty() ? null : String.join("; ", discovered.errors());
        ScanLog scanLog = new ScanLog(
                UUID.randomUUID(),
                q.id(),
                startedAt,
                finishedAt,
                discovered.primarySourceOk(),
                discovered.totalFound(),
                newEvidence,
                errorSummary);

        Question currentQuestion = persistScanProgress(q, scanLog, scanTo, finishedAt);
        evaluateAndMaybeResolve(currentQuestion, finishedAt);
    }

    private Instant computeScanFrom(Question q) {
        if (q.lastScanEnd() == null) {
            return q.windowStart();
        }
        Instant overlapped = q.lastScanEnd().minus(Duration.ofMinutes(props.scanner().overlapMinutes()));
        return overlapped.isAfter(q.windowStart()) ? overlapped : q.windowStart();
    }

    private int persistNewEvidence(Question q, Map<String, GdeltArticle> uniqueArticles, Instant now) {
        int insertedCount = 0;
        for (Map.Entry<String, GdeltArticle> entry : uniqueArticles.entrySet()) {
            String normalizedUrl = entry.getKey();
            GdeltArticle article = entry.getValue();
            Evidence evidence = new Evidence(
                    UUID.randomUUID(),
                    q.id(),
                    article.url(),
                    normalizedUrl,
                    resolveDomain(article.domain(), article.url()),
                    article.title() == null ? "" : article.title(),
                    article.seenDate() != null ? article.seenDate() : now,
                    null,
                    article.language(),
                    article.sourceCountry(),
                    EvidenceStage.NEW,
                    FetchStatus.NOT_FETCHED,
                    null,
                    null,
                    null,
                    0L,
                    now);
            if (evidenceRepository.insertIfAbsent(evidence)) {
                insertedCount++;
            }
        }
        return insertedCount;
    }

    private Question persistScanProgress(Question q, ScanLog scanLog, Instant scanTo, Instant finishedAt) {
        if (!scanLog.gdeltOk()) {
            scanLogRepository.save(scanLog);
            return q;
        }
        Instant newLastScanEnd = (q.lastScanEnd() != null && q.lastScanEnd().isAfter(scanTo))
                ? q.lastScanEnd()
                : scanTo;
        transactionTemplate.executeWithoutResult(status -> {
            scanLogRepository.save(scanLog);
            questionRepository.updateLastScanEnd(q.id(), newLastScanEnd, finishedAt);
        });
        return q.withLastScanEnd(newLastScanEnd, finishedAt);
    }

    private void evaluateAndMaybeResolve(Question q, Instant now) {
        List<Evidence> assessedEvidence = evidenceRepository.findByQuestionIdAndStage(q.id(), EvidenceStage.ASSESSED);
        List<Assessment> assessments = assessmentRepository.findByQuestionId(q.id());

        Map<UUID, Assessment> byEvidenceId = new LinkedHashMap<>();
        for (Assessment a : assessments) {
            byEvidenceId.put(a.evidenceId(), a);
        }

        List<ResolutionEngine.EvidenceWithAssessment> items = new ArrayList<>(assessedEvidence.size());
        for (Evidence ev : assessedEvidence) {
            Assessment assessment = byEvidenceId.get(ev.id());
            if (assessment != null) {
                items.add(new ResolutionEngine.EvidenceWithAssessment(ev, assessment));
            }
        }

        List<ScanLog> scanLogs = scanLogRepository.findByQuestionId(q.id());
        Optional<Resolution> resolutionOpt = resolutionEngine.evaluate(q, items, scanLogs, now);
        if (resolutionOpt.isPresent()) {
            Resolution resolution = consensusAdjudicator.adjudicate(q, items, resolutionOpt.get(), now);
            transactionTemplate.executeWithoutResult(status -> {
                resolutionRepository.save(resolution);
                questionRepository.updateStatus(
                        q.id(),
                        resolution.outcome(),
                        resolution.details(),
                        resolution.resolvedAt());
            });
            if (q.webhookUrl() != null && !q.webhookUrl().isBlank()) {
                resolutionNotifier.notify(q.webhookUrl(), resolution);
            }
        }
    }

    private static Instant earlierOf(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static String resolveDomain(String domain, String rawUrl) {
        if (domain != null && !domain.isBlank()) {
            return domain.trim().toLowerCase(Locale.ROOT);
        }
        if (rawUrl != null) {
            try {
                String host = URI.create(rawUrl.trim()).getHost();
                if (host != null && !host.isBlank()) {
                    return host.toLowerCase(Locale.ROOT);
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to empty domain.
            }
        }
        return "";
    }
}

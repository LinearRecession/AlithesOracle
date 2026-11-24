package io.alithes.oracle.service;

import io.alithes.oracle.client.ContentFetcher;
import io.alithes.oracle.client.DecisionEngine;
import io.alithes.oracle.client.FetchedArticle;
import io.alithes.oracle.client.GdeltArticle;
import io.alithes.oracle.client.JevModels;
import io.alithes.oracle.client.NewsSource;
import io.alithes.oracle.client.WikipediaClient;
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
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.util.UrlNormalizer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Orchestrates a full evidence collection, headline prefiltering, fetching, Jev
 * assessment,
 * deterministic policy evaluation, and webhook notification cycle for an active
 * question.
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
    private final NewsSource newsSource;
    private final WikipediaClient wikipediaClient;
    private final DecisionEngine decisionEngine;
    private final ContentFetcher contentFetcher;
    private final EvidenceAssessor evidenceAssessor;
    private final ResolutionEngine resolutionEngine;
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
            NewsSource newsSource,
            WikipediaClient wikipediaClient,
            DecisionEngine decisionEngine,
            ContentFetcher contentFetcher,
            EvidenceAssessor evidenceAssessor,
            ResolutionEngine resolutionEngine,
            ResolutionNotifier resolutionNotifier,
            OracleProperties props,
            TransactionTemplate transactionTemplate) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.questionRepository = Objects.requireNonNull(questionRepository, "questionRepository");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.assessmentRepository = Objects.requireNonNull(assessmentRepository, "assessmentRepository");
        this.resolutionRepository = Objects.requireNonNull(resolutionRepository, "resolutionRepository");
        this.scanLogRepository = Objects.requireNonNull(scanLogRepository, "scanLogRepository");
        this.newsSource = Objects.requireNonNull(newsSource, "newsSource");
        this.wikipediaClient = Objects.requireNonNull(wikipediaClient, "wikipediaClient");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine");
        this.contentFetcher = Objects.requireNonNull(contentFetcher, "contentFetcher");
        this.evidenceAssessor = Objects.requireNonNull(evidenceAssessor, "evidenceAssessor");
        this.resolutionEngine = Objects.requireNonNull(resolutionEngine, "resolutionEngine");
        this.resolutionNotifier = Objects.requireNonNull(resolutionNotifier, "resolutionNotifier");
        this.props = Objects.requireNonNull(props, "props");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    }

    /**
     * Executes one scan pass for {@code questionId} if the question exists and is
     * not in a final status.
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

        boolean gdeltOk = true;
        int articlesFound = 0;
        List<String> errors = new ArrayList<>();
        Map<String, GdeltArticle> uniqueArticles = new LinkedHashMap<>();

        if (scanFrom.isBefore(scanTo)) {
            for (String query : q.searchQueries()) {
                try {
                    List<GdeltArticle> articles = newsSource.search(query, scanFrom, scanTo);
                    articlesFound += articles.size();
                    for (GdeltArticle article : articles) {
                        String normalizedUrl = UrlNormalizer.normalize(article.url());
                        if (!normalizedUrl.isBlank()) {
                            uniqueArticles.putIfAbsent(normalizedUrl, article);
                        }
                    }
                } catch (Exception e) {
                    gdeltOk = false;
                    errors.add(query + ": " + e.getMessage());
                    log.warn("GDELT search failed for question {} query '{}': {}",
                            q.id(), query, e.getMessage());
                }
            }

            if (props.news().wikipediaEnabled()) {
                try {
                    List<GdeltArticle> wikiArticles = wikipediaClient.searchQuestion(q.text());
                    articlesFound += wikiArticles.size();
                    for (GdeltArticle article : wikiArticles) {
                        String normalizedUrl = UrlNormalizer.normalize(article.url());
                        if (!normalizedUrl.isBlank()) {
                            uniqueArticles.putIfAbsent(normalizedUrl, article);
                        }
                    }
                    if (!wikiArticles.isEmpty() && props.news().wikipediaMaxCitations() > 0) {
                        try {
                            List<GdeltArticle> citations = wikipediaClient.harvestCitations(q.text(), wikiArticles);
                            articlesFound += citations.size();
                            for (GdeltArticle citation : citations) {
                                String normalizedUrl = UrlNormalizer.normalize(citation.url());
                                if (!normalizedUrl.isBlank()) {
                                    uniqueArticles.putIfAbsent(normalizedUrl, citation);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Wikipedia citation harvest failed for question {}: {}", q.id(), e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Wikipedia search failed for question {}: {}", q.id(), e.getMessage());
                }
            }
        }

        int newEvidence = persistNewEvidence(q, uniqueArticles, clock.instant());
        processPendingEvidence(q);

        Instant finishedAt = clock.instant();
        String errorSummary = errors.isEmpty() ? null : String.join("; ", errors);
        ScanLog scanLog = new ScanLog(
                UUID.randomUUID(),
                q.id(),
                startedAt,
                finishedAt,
                gdeltOk,
                articlesFound,
                newEvidence,
                errorSummary);
        Question currentQuestion = q;
        if (gdeltOk) {
            Instant newLastScanEnd = (q.lastScanEnd() != null && q.lastScanEnd().isAfter(scanTo))
                    ? q.lastScanEnd()
                    : scanTo;
            transactionTemplate.executeWithoutResult(status -> {
                scanLogRepository.save(scanLog);
                questionRepository.updateLastScanEnd(q.id(), newLastScanEnd, finishedAt);
            });
            currentQuestion = q.withLastScanEnd(newLastScanEnd, finishedAt);
        } else {
            scanLogRepository.save(scanLog);
        }

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

    private void processPendingEvidence(Question q) {
        int maxArticles = Math.max(0, props.scanner().maxArticlesPerScan());
        double minHeadlineScore = props.scanner().headlineMinScore();
        int maxConcurrency = Math.max(1, props.scanner().maxConcurrency());
        List<Evidence> pending = evidenceRepository.findByQuestionIdAndStageLimit(
                q.id(), EvidenceStage.NEW, maxArticles);
        if (pending.isEmpty()) {
            return;
        }

        Semaphore permits = new Semaphore(maxConcurrency);
        List<Future<?>> futures = new ArrayList<>(pending.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Evidence ev : pending) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        processSingleEvidence(q, ev, minHeadlineScore);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for evidence processing on question {}", q.id(), e);
                    break;
                } catch (ExecutionException e) {
                    log.warn("Unexpected task failure during evidence processing for question {}: {}",
                            q.id(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                }
            }
        }
    }

    private void processSingleEvidence(Question q, Evidence ev, double minHeadlineScore) {
        try {
            double headlineScore = prefilterHeadline(q, ev);
            if (headlineScore < minHeadlineScore) {
                evidenceRepository.updateHeadlineResult(ev.id(), headlineScore, EvidenceStage.SKIPPED);
                return;
            }
            evidenceRepository.updateHeadlineResult(ev.id(), headlineScore, EvidenceStage.NEW);
            Evidence scoredEvidence = ev.withHeadlineResult(headlineScore, EvidenceStage.NEW);

            FetchedArticle fetched = contentFetcher.fetch(scoredEvidence.url(), scoredEvidence.title());
            evidenceRepository.updateFetchedContent(
                    scoredEvidence.id(),
                    fetched.status(),
                    fetched.publishedAt(),
                    fetched.contentSha256(),
                    fetched.excerpt(),
                    fetched.simhash());
            Evidence fetchedEvidence = scoredEvidence.withFetchedContent(
                    fetched.status(),
                    fetched.publishedAt(),
                    fetched.contentSha256(),
                    fetched.excerpt(),
                    fetched.simhash());

            Assessment assessment = evidenceAssessor.assess(q, fetchedEvidence, fetched);
            transactionTemplate.executeWithoutResult(status -> {
                assessmentRepository.save(assessment);
                evidenceRepository.updateStage(fetchedEvidence.id(), EvidenceStage.ASSESSED);
            });
        } catch (

        Exception e) {
            log.warn("Processing failed for evidence {} on question {}: {}",
                    ev.id(), q.id(), e.getMessage(), e);
            evidenceRepository.updateStage(ev.id(), EvidenceStage.FAILED);
        }
    }

    private double prefilterHeadline(Question q, Evidence ev) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("question", q.text());
        state.put("headline", ev.title() == null ? "" : ev.title());
        state.put("domain", ev.domain() == null ? "" : ev.domain());

        Map<String, JevModels.QuestionSpec> questions = Map.of(
                "relevant",
                new JevModels.NoulQuestion("Could an article with this headline be about the event in question?"));
        JevModels.JevResponse response = decisionEngine.evaluate(state, questions);
        return response.noulProbability("relevant");
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
            Resolution resolution = maybeAdjudicateWithSynthesis(q, items, resolutionOpt.get(), now);
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

    private Resolution maybeAdjudicateWithSynthesis(
            Question q,
            List<ResolutionEngine.EvidenceWithAssessment> items,
            Resolution resolution,
            Instant now) {
        if (!props.policy().jevSynthesisEnabled()
                || resolution.outcome() != QuestionStatus.DISPUTED) {
            return resolution;
        }
        boolean trueContradiction = resolution.confirmSum() >= props.policy().disputeThreshold()
                && resolution.denySum() >= props.policy().disputeThreshold();
        if (trueContradiction
                || resolution.denySum() >= props.policy().maxDenyRatio() * Math.max(resolution.confirmSum(), 0.001)) {
            return resolution;
        }

        Set<String> relevantDomains = new HashSet<>();
        Set<String> confirmingDomains = new HashSet<>();
        List<UUID> confirmingIds = new ArrayList<>();
        items.stream()
                .filter(i -> !i.assessment().injectionFlag()
                        && i.assessment().pRelevant() >= 0.50
                        && (i.assessment().pConfirms() >= 0.70 || i.assessment().pDenies() >= 0.70))
                .sorted(Comparator.comparingDouble(
                        (ResolutionEngine.EvidenceWithAssessment i) -> i.assessment().pRelevant()
                                * Math.max(i.assessment().pConfirms(), i.assessment().pDenies()))
                        .reversed())
                .forEach(i -> {
                    String dom = UrlNormalizer.stripHostPrefixes(i.evidence().domain());
                    if (!dom.isBlank()) {
                        relevantDomains.add(dom);
                        if (i.assessment().pConfirms() >= 0.70
                                && confirmingDomains.add(dom)
                                && confirmingIds.size() < 5) {
                            confirmingIds.add(i.evidence().id());
                        }
                    }
                });

        if (relevantDomains.size() < 3) {
            return resolution;
        }

        try {
            EvidenceAssessor.ConsensusSynthesis s = evidenceAssessor.synthesize(q, items);
            log.info(
                    "Jev consensus synthesis for question {}: pYes={}, pNo={}, pClear={} (domains={}, confirmSum={}, denySum={})",
                    q.id(), s.pConsensusYes(), s.pConsensusNo(), s.pSufficient(),
                    relevantDomains.size(), resolution.confirmSum(), resolution.denySum());

            if (confirmingDomains.size() >= 3
                    && s.pSufficient() >= 0.65
                    && s.pConsensusYes() >= 0.75
                    && s.pConsensusNo() <= 0.25) {
                double confidence = Math.min(0.95, s.pConsensusYes() * s.pSufficient());
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
            if (s.pSufficient() >= 0.65 && s.pConsensusNo() >= 0.75 && s.pConsensusYes() <= 0.25) {
                double confidence = Math.min(0.95, s.pConsensusNo() * s.pSufficient());
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

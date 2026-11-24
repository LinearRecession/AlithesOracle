package io.alithes.oracle.service;

import io.alithes.oracle.client.ContentFetcher;
import io.alithes.oracle.client.FetchedArticle;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.ScannerProperties;
import io.alithes.oracle.db.AssessmentRepository;
import io.alithes.oracle.db.EvidenceRepository;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.service.assessment.HeadlinePrefilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * Executes headline prefiltering, SSRF-guarded content fetching, and Jev stance assessment
 * over {@link EvidenceStage#NEW} evidence items using bounded virtual-thread concurrency.
 */
@Service
public class EvidenceProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(EvidenceProcessingPipeline.class);

    private final OracleProperties props;
    private final EvidenceRepository evidenceRepository;
    private final AssessmentRepository assessmentRepository;
    private final HeadlinePrefilter headlinePrefilter;
    private final ContentFetcher contentFetcher;
    private final EvidenceAssessor evidenceAssessor;
    private final TransactionTemplate transactionTemplate;

    public EvidenceProcessingPipeline(
            OracleProperties props,
            EvidenceRepository evidenceRepository,
            AssessmentRepository assessmentRepository,
            HeadlinePrefilter headlinePrefilter,
            ContentFetcher contentFetcher,
            EvidenceAssessor evidenceAssessor,
            TransactionTemplate transactionTemplate
    ) {
        this.props = Objects.requireNonNull(props, "props must not be null");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository must not be null");
        this.assessmentRepository = Objects.requireNonNull(assessmentRepository, "assessmentRepository must not be null");
        this.headlinePrefilter = Objects.requireNonNull(headlinePrefilter, "headlinePrefilter must not be null");
        this.contentFetcher = Objects.requireNonNull(contentFetcher, "contentFetcher must not be null");
        this.evidenceAssessor = Objects.requireNonNull(evidenceAssessor, "evidenceAssessor must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
    }

    public void processPending(Question q) {
        ScannerProperties scanner = props.scanner();
        int maxArticles = Math.max(0, scanner.maxArticlesPerScan());
        double minHeadlineScore = scanner.headlineMinScore();
        int maxConcurrency = Math.max(1, scanner.maxConcurrency());

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
                        processSingle(q, ev, minHeadlineScore);
                    } finally {
                        permits.release();
                    }
                    return null;
                }));
            }
            awaitAll(q, futures);
        }
    }

    private void processSingle(Question q, Evidence ev, double minHeadlineScore) {
        try {
            double headlineScore = headlinePrefilter.score(q, ev);
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
        } catch (Exception e) {
            log.warn("Processing failed for evidence {} on question {}: {}",
                    ev.id(), q.id(), e.getMessage(), e);
            evidenceRepository.updateStage(ev.id(), EvidenceStage.FAILED);
        }
    }

    private static void awaitAll(Question q, List<Future<?>> futures) {
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

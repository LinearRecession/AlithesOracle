package io.alithes.oracle.service;

import io.alithes.oracle.config.AdmissionProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.db.AssessmentRepository;
import io.alithes.oracle.db.EvidenceRepository;
import io.alithes.oracle.db.QuestionRepository;
import io.alithes.oracle.db.ResolutionRepository;
import io.alithes.oracle.db.ScanLogRepository;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.util.SafeUrlValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coordinates question creation, inspection, manual rescan, and disputed manual review.
 */
@Service
public class QuestionService {

    private static final Set<QuestionStatus> ALLOWED_MANUAL_OUTCOMES = Set.of(
            QuestionStatus.RESOLVED_YES,
            QuestionStatus.RESOLVED_NO,
            QuestionStatus.UNRESOLVABLE
    );

    private final Clock clock;
    private final OracleProperties props;
    private final AdmissionService admissionService;
    private final QueryCompiler queryCompiler;
    private final ScanService scanService;
    private final ResolutionNotifier resolutionNotifier;
    private final SafeUrlValidator safeUrlValidator;
    private final TransactionTemplate transactionTemplate;
    private final QuestionRepository questionRepository;
    private final EvidenceRepository evidenceRepository;
    private final AssessmentRepository assessmentRepository;
    private final ResolutionRepository resolutionRepository;
    private final ScanLogRepository scanLogRepository;

    public QuestionService(
            Clock clock,
            OracleProperties props,
            AdmissionService admissionService,
            QueryCompiler queryCompiler,
            ScanService scanService,
            ResolutionNotifier resolutionNotifier,
            SafeUrlValidator safeUrlValidator,
            TransactionTemplate transactionTemplate,
            QuestionRepository questionRepository,
            EvidenceRepository evidenceRepository,
            AssessmentRepository assessmentRepository,
            ResolutionRepository resolutionRepository,
            ScanLogRepository scanLogRepository
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.props = Objects.requireNonNull(props, "props");
        this.admissionService = Objects.requireNonNull(admissionService, "admissionService");
        this.queryCompiler = Objects.requireNonNull(queryCompiler, "queryCompiler");
        this.scanService = Objects.requireNonNull(scanService, "scanService");
        this.resolutionNotifier = Objects.requireNonNull(resolutionNotifier, "resolutionNotifier");
        this.safeUrlValidator = Objects.requireNonNull(safeUrlValidator, "safeUrlValidator");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
        this.questionRepository = Objects.requireNonNull(questionRepository, "questionRepository");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.assessmentRepository = Objects.requireNonNull(assessmentRepository, "assessmentRepository");
        this.resolutionRepository = Objects.requireNonNull(resolutionRepository, "resolutionRepository");
        this.scanLogRepository = Objects.requireNonNull(scanLogRepository, "scanLogRepository");
    }

    public record CreateQuestionResult(Question question, boolean accepted, List<String> reasons) {
        public CreateQuestionResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record EvidenceDetail(Evidence evidence, Assessment assessment) {
    }

    public record QueryExecutionResult(
            Question question,
            boolean accepted,
            List<String> rejectionReasons,
            Resolution resolution,
            List<EvidenceDetail> evidence
    ) {
        public QueryExecutionResult {
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * Evaluates a natural-language query synchronously on demand without waiting for a scheduler tick.
     */
    public QueryExecutionResult runQuery(
            String queryText,
            String resolutionCriteria,
            Integer lookbackDays,
            Instant windowStartOverride,
            Instant deadlineOverride,
            Integer graceHoursOverride,
            List<String> searchQueriesOverride
    ) {
        AdmissionProperties admission = props.admission();
        Instant now = clock.instant();
        int days = (lookbackDays == null) ? admission.defaultQueryLookbackDays() : lookbackDays;
        if (days < 1 || days > admission.maxQueryLookbackDays()) {
            throw new IllegalArgumentException(
                    "lookbackDays must be between 1 and " + admission.maxQueryLookbackDays());
        }

        Instant deadline = (deadlineOverride != null) ? deadlineOverride : now;
        Instant windowStart = (windowStartOverride != null)
                ? windowStartOverride
                : deadline.minus(Duration.ofDays(days));
        int graceHours = (graceHoursOverride != null) ? graceHoursOverride : 0;

        CreateQuestionResult created = createQuestion(
                queryText,
                resolutionCriteria,
                windowStart,
                deadline,
                graceHours,
                searchQueriesOverride,
                null
        );
        if (!created.accepted()) {
            return new QueryExecutionResult(created.question(), false, created.reasons(), null, List.of());
        }

        UUID questionId = created.question().id();
        scanService.scanQuestion(questionId);

        Question updated = getQuestion(questionId);
        Resolution resolution = resolutionRepository.findByQuestionId(questionId).orElse(null);
        List<EvidenceDetail> evidence = getEvidence(questionId, null);
        return new QueryExecutionResult(updated, true, List.of(), resolution, evidence);
    }

    /**
     * Validates, admits (or persists as {@link QuestionStatus#REJECTED}), and compiles GDELT queries
     * for a newly submitted question. Deliberately non-transactional so rejected questions stay persisted.
     */
    public CreateQuestionResult createQuestion(
            String text,
            String resolutionCriteria,
            Instant windowStart,
            Instant deadline,
            Integer graceHours,
            List<String> searchQueries,
            String webhookUrl
    ) {
        AdmissionProperties admissionProps = props.admission();
        String trimmedText = text == null ? "" : text.trim();
        String trimmedCriteria = (resolutionCriteria == null || resolutionCriteria.isBlank())
                ? null
                : resolutionCriteria.trim();
        int resolvedGraceHours = graceHours == null ? admissionProps.defaultGraceHours() : graceHours;
        if (resolvedGraceHours < 0 || resolvedGraceHours > admissionProps.maxGraceHours()) {
            throw new IllegalArgumentException(
                    "graceHours must be between 0 and " + admissionProps.maxGraceHours());
        }
        String normalizedWebhookUrl = normalizeAndValidateWebhookUrl(webhookUrl);

        AdmissionService.AdmissionResult admission = admissionService.evaluate(
                trimmedText, trimmedCriteria, windowStart, deadline);
        if (!admission.accepted()) {
            Instant now = clock.instant();
            Question rejected = new Question(
                    UUID.randomUUID(),
                    trimmedText,
                    trimmedCriteria,
                    windowStart,
                    deadline,
                    resolvedGraceHours,
                    QuestionStatus.REJECTED,
                    String.join("; ", admission.reasons()),
                    List.of(),
                    normalizedWebhookUrl,
                    null,
                    now,
                    now
            );
            questionRepository.save(rejected);
            return new CreateQuestionResult(rejected, false, admission.reasons());
        }

        List<String> compiledQueries = queryCompiler.compile(trimmedText, trimmedCriteria, searchQueries);
        Instant now = clock.instant();
        Question active = new Question(
                UUID.randomUUID(),
                trimmedText,
                trimmedCriteria,
                windowStart,
                deadline,
                resolvedGraceHours,
                QuestionStatus.ACTIVE,
                "Admitted",
                compiledQueries,
                normalizedWebhookUrl,
                null,
                now,
                now
        );
        questionRepository.save(active);
        return new CreateQuestionResult(active, true, List.of());
    }

    public List<Question> listQuestions(QuestionStatus status) {
        return (status == null)
                ? questionRepository.findAll()
                : questionRepository.findByStatusOrderByCreatedAtDesc(status);
    }

    public Question getQuestion(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return questionRepository.findById(id)
                .orElseThrow(() -> new QuestionNotFoundException(id));
    }

    public List<ScanLog> getScanLogs(UUID id) {
        getQuestion(id);
        return scanLogRepository.findByQuestionId(id);
    }

    public Map<QuestionStatus, Long> countQuestionsByStatus() {
        return questionRepository.countByStatus();
    }

    public List<EvidenceDetail> getEvidence(UUID id, EvidenceStage stage) {
        getQuestion(id);
        List<Evidence> evidenceList = (stage == null)
                ? evidenceRepository.findByQuestionId(id)
                : evidenceRepository.findByQuestionIdAndStage(id, stage);
        List<Assessment> assessments = assessmentRepository.findByQuestionId(id);

        Map<UUID, Assessment> assessmentByEvidenceId = new LinkedHashMap<>();
        for (Assessment a : assessments) {
            assessmentByEvidenceId.put(a.evidenceId(), a);
        }

        List<EvidenceDetail> result = new ArrayList<>(evidenceList.size());
        for (Evidence ev : evidenceList) {
            result.add(new EvidenceDetail(ev, assessmentByEvidenceId.get(ev.id())));
        }
        return List.copyOf(result);
    }

    public Resolution getResolution(UUID id) {
        getQuestion(id);
        return resolutionRepository.findByQuestionId(id)
                .orElseThrow(() -> new ResolutionNotFoundException(id));
    }

    public Question rescan(UUID id) {
        getQuestion(id);
        scanService.scanQuestion(id);
        return getQuestion(id);
    }

    public Resolution manualReview(UUID id, QuestionStatus outcome, String reason) {
        if (outcome == null || !ALLOWED_MANUAL_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException(
                    "Manual review outcome must be one of RESOLVED_YES, RESOLVED_NO, or UNRESOLVABLE");
        }

        AtomicReference<String> webhookUrlRef = new AtomicReference<>();
        Resolution manualResolution = transactionTemplate.execute(status -> {
            Question q = getQuestion(id);
            if (q.status() != QuestionStatus.DISPUTED) {
                throw new ConflictException(
                        "Question " + id + " is in status " + q.status()
                                + "; manual review is only allowed when status is DISPUTED");
            }

            Optional<Resolution> existing = resolutionRepository.findByQuestionId(id);
            String details = (reason == null || reason.isBlank())
                    ? "Manual review: " + outcome
                    : reason.trim();
            Instant now = clock.instant();

            Resolution resolution = new Resolution(
                    id,
                    outcome,
                    1.0,
                    existing.map(Resolution::confirmSum).orElse(0.0),
                    existing.map(Resolution::denySum).orElse(0.0),
                    existing.map(Resolution::independentClusters).orElse(0),
                    existing.map(Resolution::supportingEvidenceIds).orElse(List.of()),
                    props.policy().version(),
                    "manual",
                    details,
                    now
            );

            resolutionRepository.save(resolution);
            questionRepository.updateStatus(id, outcome, details, now);
            webhookUrlRef.set(q.webhookUrl());
            return resolution;
        });

        String webhookUrl = webhookUrlRef.get();
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            resolutionNotifier.notify(webhookUrl, manualResolution);
        }
        return manualResolution;
    }

    private String normalizeAndValidateWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return null;
        }
        return safeUrlValidator.validateAndParse(webhookUrl).toString();
    }
}

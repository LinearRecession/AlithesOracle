package io.alithes.oracle.service;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.db.QuestionRepository;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically scans all {@link QuestionStatus#ACTIVE} questions sequentially when
 * {@code oracle.scanner.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "oracle.scanner", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final OracleProperties props;
    private final QuestionRepository questionRepository;
    private final ScanService scanService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ScanScheduler(
            OracleProperties props,
            QuestionRepository questionRepository,
            ScanService scanService
    ) {
        this.props = Objects.requireNonNull(props, "props must not be null");
        this.questionRepository = Objects.requireNonNull(questionRepository, "questionRepository must not be null");
        this.scanService = Objects.requireNonNull(scanService, "scanService must not be null");
    }

    @Scheduled(fixedDelayString = "${oracle.scanner.fixed-delay-ms}")
    public void scanActiveQuestions() {
        if (!props.scanner().enabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("Scan tick skipped because previous scan is still running");
            return;
        }

        try {
            List<Question> activeQuestions = questionRepository.findByStatus(QuestionStatus.ACTIVE);
            for (Question question : activeQuestions) {
                try {
                    scanService.scanQuestion(question.id());
                } catch (Exception e) {
                    log.error("Unexpected failure scanning active question {}", question.id(), e);
                }
            }
        } finally {
            running.set(false);
        }
    }
}

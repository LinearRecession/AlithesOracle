package io.alithes.oracle.db;

import static org.assertj.core.api.Assertions.assertThat;

import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RepositoryTest {

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private EvidenceRepository evidenceRepository;

    @Autowired
    private AssessmentRepository assessmentRepository;

    @Autowired
    private ResolutionRepository resolutionRepository;

    @Autowired
    private ScanLogRepository scanLogRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("DELETE FROM questions").update();
    }

    @Test
    void questionRepositoryCrudStatusAndLastScanEndUpdates() {
        Instant windowStart = Instant.parse("2026-11-01T00:00:00Z");
        Instant deadline = Instant.parse("2026-12-31T23:59:59Z");
        Instant createdAt = Instant.parse("2026-11-20T09:00:00Z");
        Instant lastScan = Instant.parse("2026-11-20T10:00:00Z");

        Question activeQuestion = new Question(
                UUID.randomUUID(),
                "Has Country X officially announced a snap election?",
                "Official government gazette or PM address",
                windowStart,
                deadline,
                48,
                QuestionStatus.ACTIVE,
                "Admitted by AdmissionService",
                List.of("\"Country X\" election", "Country X parliament dissolved"),
                "https://hooks.example.com/oracle",
                lastScan,
                createdAt,
                createdAt
        );

        Question rejectedWithNulls = new Question(
                UUID.randomUUID(),
                "Is Country X's economic policy good?",
                null,
                windowStart,
                deadline,
                48,
                QuestionStatus.REJECTED,
                null,
                List.of(),
                null,
                null,
                createdAt.plusSeconds(60),
                createdAt.plusSeconds(60)
        );

        questionRepository.save(activeQuestion);
        questionRepository.save(rejectedWithNulls);

        assertThat(questionRepository.findById(activeQuestion.id())).contains(activeQuestion);
        assertThat(questionRepository.findById(rejectedWithNulls.id())).contains(rejectedWithNulls);
        assertThat(questionRepository.findById(UUID.randomUUID())).isEmpty();

        Question loadedNullable = questionRepository.findById(rejectedWithNulls.id()).orElseThrow();
        assertThat(loadedNullable.resolutionCriteria()).isNull();
        assertThat(loadedNullable.statusReason()).isNull();
        assertThat(loadedNullable.webhookUrl()).isNull();
        assertThat(loadedNullable.lastScanEnd()).isNull();
        assertThat(loadedNullable.searchQueries()).isEmpty();

        assertThat(questionRepository.findByStatus(QuestionStatus.ACTIVE))
                .containsExactly(activeQuestion);
        assertThat(questionRepository.findByStatus(QuestionStatus.REJECTED))
                .containsExactly(rejectedWithNulls);

        Instant scanUpdateTime = Instant.parse("2026-11-20T11:00:00Z");
        Instant newScanEnd = Instant.parse("2026-11-20T10:55:00Z");
        questionRepository.updateLastScanEnd(activeQuestion.id(), newScanEnd, scanUpdateTime);

        Question afterScanUpdate = questionRepository.findById(activeQuestion.id()).orElseThrow();
        assertThat(afterScanUpdate.lastScanEnd()).isEqualTo(newScanEnd);
        assertThat(afterScanUpdate.updatedAt()).isEqualTo(scanUpdateTime);

        Instant statusUpdateTime = Instant.parse("2026-11-20T12:00:00Z");
        questionRepository.updateStatus(
                activeQuestion.id(),
                QuestionStatus.DISPUTED,
                "Conflicting tier-1 reports",
                statusUpdateTime
        );

        Question afterStatusUpdate = questionRepository.findById(activeQuestion.id()).orElseThrow();
        assertThat(afterStatusUpdate.status()).isEqualTo(QuestionStatus.DISPUTED);
        assertThat(afterStatusUpdate.statusReason()).isEqualTo("Conflicting tier-1 reports");
        assertThat(afterStatusUpdate.updatedAt()).isEqualTo(statusUpdateTime);
        assertThat(questionRepository.findByStatus(QuestionStatus.ACTIVE)).isEmpty();
        assertThat(questionRepository.findByStatus(QuestionStatus.DISPUTED))
                .extracting(Question::id)
                .containsExactly(activeQuestion.id());

        questionRepository.updateStatus(rejectedWithNulls.id(), QuestionStatus.REJECTED, null, statusUpdateTime);
        assertThat(questionRepository.findById(rejectedWithNulls.id()).orElseThrow().statusReason()).isNull();
    }

    @Test
    void evidenceRepositoryInsertIfAbsentDeduplicationAndUpdates() {
        Question q1 = createQuestion(UUID.randomUUID(), "Question one about treaty signing");
        Question q2 = createQuestion(UUID.randomUUID(), "Question two about treaty signing");
        questionRepository.save(q1);
        questionRepository.save(q2);

        Instant seen1 = Instant.parse("2026-11-21T08:00:00Z");
        Instant seen2 = Instant.parse("2026-11-21T09:00:00Z");
        Instant seen3 = Instant.parse("2026-11-21T10:00:00Z");
        Instant createdAt = Instant.parse("2026-11-21T10:05:00Z");

        Evidence e1 = new Evidence(
                UUID.randomUUID(),
                q1.id(),
                "https://www.reuters.com/world/treaty-signed/?utm_source=x",
                "https://reuters.com/world/treaty-signed",
                "reuters.com",
                "Leaders sign historic treaty",
                seen1,
                null,
                null,
                null,
                EvidenceStage.NEW,
                FetchStatus.NOT_FETCHED,
                null,
                null,
                null,
                0L,
                createdAt
        );

        Evidence duplicateNormalizedUrl = new Evidence(
                UUID.randomUUID(),
                q1.id(),
                "https://reuters.com/world/treaty-signed?ref=feed",
                "https://reuters.com/world/treaty-signed",
                "reuters.com",
                "Leaders sign historic treaty (updated)",
                seen2,
                null,
                "English",
                "United Kingdom",
                EvidenceStage.NEW,
                FetchStatus.NOT_FETCHED,
                null,
                null,
                null,
                0L,
                createdAt
        );

        Evidence sameUrlDifferentQuestion = new Evidence(
                UUID.randomUUID(),
                q2.id(),
                "https://reuters.com/world/treaty-signed",
                "https://reuters.com/world/treaty-signed",
                "reuters.com",
                "Leaders sign historic treaty",
                seen1,
                null,
                "English",
                "United Kingdom",
                EvidenceStage.NEW,
                FetchStatus.NOT_FETCHED,
                null,
                null,
                null,
                0L,
                createdAt
        );

        assertThat(evidenceRepository.insertIfAbsent(e1)).isTrue();
        assertThat(evidenceRepository.insertIfAbsent(duplicateNormalizedUrl)).isFalse();
        assertThat(evidenceRepository.insertIfAbsent(sameUrlDifferentQuestion)).isTrue();

        Evidence loadedNulls = evidenceRepository.findById(e1.id()).orElseThrow();
        assertThat(loadedNulls).isEqualTo(e1);
        assertThat(loadedNulls.publishedAt()).isNull();
        assertThat(loadedNulls.headlineScore()).isNull();
        assertThat(loadedNulls.language()).isNull();
        assertThat(loadedNulls.sourceCountry()).isNull();
        assertThat(loadedNulls.contentSha256()).isNull();
        assertThat(loadedNulls.excerpt()).isNull();
        assertThat(evidenceRepository.findById(UUID.randomUUID())).isEmpty();

        Evidence e2 = new Evidence(
                UUID.randomUUID(),
                q1.id(),
                "https://apnews.com/article/treaty-2",
                "https://apnews.com/article/treaty-2",
                "apnews.com",
                "Treaty ratified in parliament",
                seen2,
                null,
                "English",
                "United States",
                EvidenceStage.NEW,
                FetchStatus.NOT_FETCHED,
                null,
                null,
                null,
                0L,
                createdAt
        );

        Evidence e3 = new Evidence(
                UUID.randomUUID(),
                q1.id(),
                "https://bbc.com/news/treaty-3",
                "https://bbc.com/news/treaty-3",
                "bbc.com",
                "Unrelated festival news",
                seen3,
                null,
                "English",
                "United Kingdom",
                EvidenceStage.NEW,
                FetchStatus.NOT_FETCHED,
                null,
                null,
                null,
                0L,
                createdAt
        );

        assertThat(evidenceRepository.insertIfAbsent(e2)).isTrue();
        assertThat(evidenceRepository.insertIfAbsent(e3)).isTrue();

        assertThat(evidenceRepository.findByQuestionId(q1.id()))
                .extracting(Evidence::id)
                .containsExactly(e3.id(), e2.id(), e1.id());
        assertThat(evidenceRepository.findByQuestionIdAndStageLimit(q1.id(), EvidenceStage.NEW, 2))
                .extracting(Evidence::id)
                .containsExactly(e3.id(), e2.id());
        assertThat(evidenceRepository.findByQuestionIdAndStageLimit(q1.id(), EvidenceStage.NEW, 0))
                .isEmpty();

        evidenceRepository.updateHeadlineResult(e3.id(), 0.14, EvidenceStage.SKIPPED);
        Evidence skipped = evidenceRepository.findById(e3.id()).orElseThrow();
        assertThat(skipped.headlineScore()).isEqualTo(0.14);
        assertThat(skipped.stage()).isEqualTo(EvidenceStage.SKIPPED);

        evidenceRepository.updateHeadlineResult(e1.id(), 0.92, EvidenceStage.NEW);
        Instant publishedAt = Instant.parse("2026-11-21T07:45:00Z");
        long negativeSimhash = -7654321098765432109L;
        evidenceRepository.updateFetchedContent(
                e1.id(),
                FetchStatus.OK,
                publishedAt,
                "a".repeat(64),
                "Full extracted article excerpt...",
                negativeSimhash
        );
        evidenceRepository.updateStage(e1.id(), EvidenceStage.ASSESSED);

        Evidence assessed = evidenceRepository.findById(e1.id()).orElseThrow();
        assertThat(assessed.headlineScore()).isEqualTo(0.92);
        assertThat(assessed.fetchStatus()).isEqualTo(FetchStatus.OK);
        assertThat(assessed.publishedAt()).isEqualTo(publishedAt);
        assertThat(assessed.contentSha256()).isEqualTo("a".repeat(64));
        assertThat(assessed.excerpt()).isEqualTo("Full extracted article excerpt...");
        assertThat(assessed.simhash()).isEqualTo(negativeSimhash);
        assertThat(assessed.stage()).isEqualTo(EvidenceStage.ASSESSED);

        evidenceRepository.updateFetchedContent(
                e2.id(),
                FetchStatus.TITLE_ONLY,
                null,
                "b".repeat(64),
                e2.title(),
                42L
        );
        evidenceRepository.updateStage(e2.id(), EvidenceStage.FAILED);

        Evidence failed = evidenceRepository.findById(e2.id()).orElseThrow();
        assertThat(failed.fetchStatus()).isEqualTo(FetchStatus.TITLE_ONLY);
        assertThat(failed.publishedAt()).isNull();
        assertThat(failed.stage()).isEqualTo(EvidenceStage.FAILED);

        assertThat(evidenceRepository.findByQuestionIdAndStage(q1.id(), EvidenceStage.ASSESSED))
                .containsExactly(assessed);
        assertThat(evidenceRepository.findByQuestionIdAndStage(q1.id(), EvidenceStage.SKIPPED))
                .containsExactly(skipped);
        assertThat(evidenceRepository.findByQuestionIdAndStage(q1.id(), EvidenceStage.FAILED))
                .containsExactly(failed);
        assertThat(evidenceRepository.findByQuestionIdAndStage(q1.id(), EvidenceStage.NEW))
                .isEmpty();
    }

    @Test
    void assessmentRepositorySaveAndFinders() {
        Question q = createQuestion(UUID.randomUUID(), "Did the central bank cut interest rates?");
        questionRepository.save(q);

        Evidence ev1 = createEvidence(UUID.randomUUID(), q.id(), "https://reuters.com/rates-1");
        Evidence ev2 = createEvidence(UUID.randomUUID(), q.id(), "https://apnews.com/rates-2");
        evidenceRepository.insertIfAbsent(ev1);
        evidenceRepository.insertIfAbsent(ev2);

        Instant t1 = Instant.parse("2026-11-21T12:00:00Z");
        Instant t2 = Instant.parse("2026-11-21T12:05:00Z");

        Assessment a1 = new Assessment(
                UUID.randomUUID(),
                ev1.id(),
                q.id(),
                "jev-1.14.0",
                0.96,
                0.91,
                0.88,
                0.05,
                0.04,
                0.03,
                false,
                0.89,
                0.95,
                "official_source_cited",
                false,
                410,
                "1".repeat(64),
                "{\"model\":\"jev-1.14.0\"}",
                t1
        );

        Assessment a2 = new Assessment(
                UUID.randomUUID(),
                ev2.id(),
                q.id(),
                "jev-1.14.0",
                0.40,
                0.25,
                0.10,
                0.20,
                0.50,
                0.20,
                true,
                0.42,
                0.60,
                "anonymous_sources",
                true,
                250,
                "2".repeat(64),
                "{\"model\":\"jev-1.14.0\",\"flagged\":true}",
                t2
        );

        assessmentRepository.save(a1);
        assessmentRepository.save(a2);

        assertThat(assessmentRepository.findByEvidenceId(ev1.id())).contains(a1);
        assertThat(assessmentRepository.findByEvidenceId(ev2.id())).contains(a2);
        assertThat(assessmentRepository.findByEvidenceId(UUID.randomUUID())).isEmpty();
        assertThat(assessmentRepository.findByQuestionId(q.id())).containsExactly(a1, a2);

        Assessment updatedA1 = new Assessment(
                a1.id(),
                a1.evidenceId(),
                a1.questionId(),
                a1.model(),
                0.99,
                a1.pStanceFits(),
                a1.pConfirms(),
                a1.pPlanned(),
                a1.pSpeculation(),
                a1.pDenies(),
                a1.stanceUnstable(),
                a1.stanceConfidence(),
                a1.pFresh(),
                a1.attribution(),
                a1.injectionFlag(),
                a1.inputTokens(),
                a1.requestSha256(),
                a1.responseJson(),
                a1.createdAt()
        );
        assessmentRepository.save(updatedA1);
        assertThat(assessmentRepository.findByEvidenceId(ev1.id())).contains(updatedA1);
    }

    @Test
    void resolutionRepositorySaveUpsertAndNullableDetails() {
        Question q = createQuestion(UUID.randomUUID(), "Did the parliament pass the trade bill?");
        questionRepository.save(q);

        UUID evId1 = UUID.randomUUID();
        UUID evId2 = UUID.randomUUID();
        Instant resolvedAt = Instant.parse("2026-11-22T14:00:00Z");

        Resolution initialDisputed = new Resolution(
                q.id(),
                QuestionStatus.DISPUTED,
                0.50,
                1.65,
                1.55,
                4,
                List.of(evId1, evId2),
                1,
                "jev-1.14.0",
                null,
                resolvedAt
        );

        resolutionRepository.save(initialDisputed);

        Resolution loadedInitial = resolutionRepository.findByQuestionId(q.id()).orElseThrow();
        assertThat(loadedInitial).isEqualTo(initialDisputed);
        assertThat(loadedInitial.details()).isNull();
        assertThat(loadedInitial.supportingEvidenceIds()).containsExactly(evId1, evId2);
        assertThat(resolutionRepository.findByQuestionId(UUID.randomUUID())).isEmpty();

        Resolution manualOverride = new Resolution(
                q.id(),
                QuestionStatus.RESOLVED_YES,
                0.99,
                1.65,
                1.55,
                4,
                List.of(),
                1,
                "manual",
                "Verified against parliamentary record",
                resolvedAt.plusSeconds(3600)
        );

        resolutionRepository.save(manualOverride);

        assertThat(resolutionRepository.findByQuestionId(q.id())).contains(manualOverride);
    }

    @Test
    void scanLogRepositorySaveAndFindByQuestionId() {
        Question q = createQuestion(UUID.randomUUID(), "Has Company A completed the acquisition?");
        questionRepository.save(q);

        Instant s1 = Instant.parse("2026-11-21T01:00:00Z");
        Instant s2 = Instant.parse("2026-11-21T01:15:00Z");

        ScanLog okLog = new ScanLog(
                UUID.randomUUID(),
                q.id(),
                s1,
                s1.plusSeconds(4),
                true,
                12,
                5,
                null
        );

        ScanLog failedLog = new ScanLog(
                UUID.randomUUID(),
                q.id(),
                s2,
                s2.plusSeconds(2),
                false,
                0,
                0,
                "GDELT HTTP 429 rate limited"
        );

        scanLogRepository.save(failedLog);
        scanLogRepository.save(okLog);

        List<ScanLog> logs = scanLogRepository.findByQuestionId(q.id());
        assertThat(logs).containsExactly(okLog, failedLog);
        assertThat(logs.get(0).error()).isNull();
        assertThat(logs.get(1).error()).isEqualTo("GDELT HTTP 429 rate limited");
        assertThat(scanLogRepository.findByQuestionId(UUID.randomUUID())).isEmpty();
    }

    private static Question createQuestion(UUID id, String text) {
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        return new Question(
                id,
                text,
                "Official confirmation required",
                now,
                now.plusSeconds(86400L * 30),
                48,
                QuestionStatus.ACTIVE,
                null,
                List.of("treaty signed"),
                null,
                null,
                now,
                now
        );
    }

    private static Evidence createEvidence(UUID id, UUID questionId, String url) {
        Instant now = Instant.parse("2026-11-21T00:00:00Z");
        return new Evidence(
                id,
                questionId,
                url,
                url,
                "reuters.com",
                "News headline",
                now,
                now,
                "English",
                "United Kingdom",
                EvidenceStage.NEW,
                FetchStatus.OK,
                0.85,
                "c".repeat(64),
                "Article excerpt",
                12345L,
                now
        );
    }
}

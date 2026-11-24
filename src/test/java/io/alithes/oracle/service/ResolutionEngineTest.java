package io.alithes.oracle.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.domain.Assessment;
import io.alithes.oracle.domain.Evidence;
import io.alithes.oracle.domain.EvidenceStage;
import io.alithes.oracle.domain.FetchStatus;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.domain.Resolution;
import io.alithes.oracle.domain.ScanLog;
import io.alithes.oracle.service.ResolutionEngine.EvidenceWithAssessment;
import io.alithes.oracle.util.SimHash;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResolutionEngineTest {

    private static final Instant WINDOW_START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-10T00:00:00Z");
    private static final int GRACE_HOURS = 48;
    private static final Instant BEFORE_FINAL = Instant.parse("2026-11-11T12:00:00Z");
    private static final Instant AFTER_FINAL = Instant.parse("2026-11-12T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");

    // Orthogonal 64-bit Rademacher basis vectors: every pair has Hamming distance >= 32.
    private static final long[] ORTHOGONAL_SIMHASHES = {
            0x00000000FFFFFFFFL,
            0xFFFFFFFF00000000L,
            0x0000FFFF0000FFFFL,
            0xFFFF0000FFFF0000L,
            0x00FF00FF00FF00FFL,
            0xFF00FF00FF00FF00L,
            0x0F0F0F0F0F0F0F0FL,
            0xF0F0F0F0F0F0F0F0L,
            0x3333333333333333L,
            0xCCCCCCCCCCCCCCCCL,
            0x5555555555555555L,
            0xAAAAAAAAAAAAAAAAL
    };

    private final SourceTierRegistry registry = new SourceTierRegistry();
    private final ResolutionEngine engine = new ResolutionEngine(PolicyProperties.defaults(), registry);

    @Test
    void sourceTierRegistryLoadsTiersNormalizesHostsAndFallsBackOneSubdomainLabel() {
        assertThat(registry.weightFor("reuters.com")).isEqualTo(1.0);
        assertThat(registry.weightFor("WWW.Reuters.COM.")).isEqualTo(1.0);
        assertThat(registry.weightFor("m.bbc.co.uk")).isEqualTo(1.0);
        assertThat(registry.weightFor("uk.reuters.com")).isEqualTo(1.0);
        assertThat(registry.weightFor("www.uk.reuters.com")).isEqualTo(1.0);
        assertThat(registry.weightFor("news.bbc.co.uk")).isEqualTo(1.0);
        assertThat(registry.weightFor("deep.uk.reuters.com")).isEqualTo(0.3);

        assertThat(registry.weightFor("cnn.com")).isEqualTo(0.7);
        assertThat(registry.weightFor("edition.cnn.com")).isEqualTo(0.7);

        assertThat(registry.weightFor("theonion.com")).isEqualTo(0.0);
        assertThat(registry.weightFor("www.babylonbee.com")).isEqualTo(0.0);

        assertThat(registry.weightFor("unknown-local-blog.org")).isEqualTo(0.3);
        assertThat(registry.weightFor("")).isEqualTo(0.3);
        assertThat(registry.weightFor(null)).isEqualTo(0.3);

        SourceTierRegistry custom = new SourceTierRegistry(Map.of(
                "WWW.CustomOutlet.org", 0.85,
                "uk.reuters.com", 0.95
        ));
        assertThat(custom.weightFor("customoutlet.org")).isEqualTo(0.85);
        assertThat(custom.weightFor("news.customoutlet.org")).isEqualTo(0.85);
        assertThat(custom.weightFor("uk.reuters.com")).isEqualTo(0.95);
        assertThat(custom.weightFor("reuters.com")).isEqualTo(0.3);
    }

    @Test
    void scenario1_threeIndependentTier1And2ConfirmingArticlesResolveYes() {
        Question q = sampleQuestion();
        EvidenceWithAssessment item1 = confirmingItem(
                uuid(1), q.id(), "reuters.com", "Reuters Exclusive Election Confirmed",
                ORTHOGONAL_SIMHASHES[0], NOW.minus(Duration.ofHours(2)), "official_source_cited");
        EvidenceWithAssessment item2 = confirmingItem(
                uuid(2), q.id(), "apnews.com", "AP Reports Snap Election Decree",
                ORTHOGONAL_SIMHASHES[1], NOW.minus(Duration.ofHours(1)), "official_source_cited");
        EvidenceWithAssessment item3 = confirmingItem(
                uuid(3), q.id(), "cnn.com", "CNN Parliament Dissolved for Vote",
                ORTHOGONAL_SIMHASHES[2], NOW.minus(Duration.ofMinutes(30)), "direct_reporting");

        Optional<Resolution> result = engine.evaluate(q, List.of(item1, item2, item3), List.of(), NOW);

        assertThat(result).isPresent();
        Resolution res = result.get();
        // item1: 0.95 * 1.0 * 1.0 * 1.0 * 1.0 * 1.0 * 0.95 = 0.9025
        // item2: 0.95 * 1.0 * 1.0 * 1.0 * 1.0 * 1.0 * 0.95 = 0.9025
        // item3: 0.95 * 1.0 * 0.7 * 0.9 * 1.0 * 1.0 * 0.95 = 0.568575
        double expectedSum = 0.9025 + 0.9025 + 0.568575;
        assertThat(res.outcome()).isEqualTo(QuestionStatus.RESOLVED_YES);
        assertThat(res.confirmSum()).isCloseTo(expectedSum, within(1e-9));
        assertThat(res.denySum()).isZero();
        assertThat(res.independentClusters()).isEqualTo(3);
        assertThat(res.confidence()).isCloseTo(Math.min(0.99, expectedSum / 3.0), within(1e-9));
        assertThat(res.supportingEvidenceIds()).containsExactly(uuid(1), uuid(2), uuid(3));
        assertThat(res.policyVersion()).isEqualTo(1);
        assertThat(res.model()).isEqualTo("jev-1.14.0");
        assertThat(res.resolvedAt()).isEqualTo(NOW);
    }

    @Test
    void scenario2_tenSyndicatedCopiesAcrossDifferentDomainsCollapseToOneClusterAndStayActive() {
        Question q = sampleQuestion();
        String wireBody = "Prime Minister Jane Doe announced a snap general election on Thursday, calling on "
                + "parliament to dissolve immediately after months of legislative deadlock over the national "
                + "budget. Opposition leaders welcomed the announcement and pledged to campaign on healthcare "
                + "funding, infrastructure investment, and lower energy costs for households across the "
                + "country ahead of next month's vote. Treasury officials said emergency spending measures "
                + "will remain in place during the campaign period while independent election monitors "
                + "prepare polling stations across all twelve regions before final certification next "
                + "Tuesday. Speaking outside parliament after meeting party leaders, the prime minister said "
                + "voters deserved a clear mandate before the upcoming economic summit in Geneva. Regional "
                + "governors confirmed that local registration offices will extend operating hours through "
                + "the weekend to process ballot applications from citizens living abroad and first-time "
                + "voters in rural districts.";
        long originalHash = SimHash.compute("LONDON (Reuters) - " + wireBody + " Additional reporting by John Smith.");
        long syndicatedHash = SimHash.compute("LONDON - " + wireBody + " Editing by Sarah Jones.");
        assertThat(SimHash.hammingDistance(originalHash, syndicatedHash)).isLessThanOrEqualTo(3);

        List<String> domains = List.of(
                "reuters.com", "apnews.com", "bbc.com", "ft.com", "bloomberg.com",
                "wsj.com", "nytimes.com", "theguardian.com", "france24.com", "dw.com"
        );
        List<EvidenceWithAssessment> items = new ArrayList<>();
        for (int i = 0; i < domains.size(); i++) {
            long hash = (i % 2 == 0) ? originalHash : syndicatedHash;
            items.add(confirmingItem(
                    uuid(10 + i),
                    q.id(),
                    domains.get(i),
                    "Unique Syndicate Headline Number " + i,
                    hash,
                    NOW.minus(Duration.ofHours(1)),
                    "official_source_cited"
            ));
        }

        Optional<Resolution> result = engine.evaluate(q, items, List.of(), NOW);

        assertThat(result).isEmpty();
    }

    @Test
    void scenario3_fiveDistinctArticlesFromSamePublisherCollapseToOneClusterAndStayActive() {
        Question q = sampleQuestion();
        List<String> reutersHosts = List.of(
                "reuters.com", "www.reuters.com", "m.reuters.com", "uk.reuters.com", "reuters.com"
        );
        List<EvidenceWithAssessment> items = new ArrayList<>();
        for (int i = 0; i < reutersHosts.size(); i++) {
            items.add(confirmingItem(
                    uuid(30 + i),
                    q.id(),
                    reutersHosts.get(i),
                    "Distinct Reuters Angle " + i,
                    ORTHOGONAL_SIMHASHES[i],
                    NOW.minus(Duration.ofHours(1)),
                    "official_source_cited"
            ));
        }

        Optional<Resolution> result = engine.evaluate(q, items, List.of(), NOW);

        assertThat(result).isEmpty();
    }

    @Test
    void scenario4_threeUnknownTier3ConfirmingArticlesStayActive() {
        Question q = sampleQuestion();
        List<EvidenceWithAssessment> items = List.of(
                confirmingItem(uuid(41), q.id(), "unknown-alpha.org", "Alpha Blog Report",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(42), q.id(), "unknown-beta.net", "Beta Blog Report",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited"),
                confirmingItem(uuid(43), q.id(), "unknown-gamma.info", "Gamma Blog Report",
                        ORTHOGONAL_SIMHASHES[2], NOW, "official_source_cited")
        );

        assertThat(engine.evaluate(q, items, List.of(), NOW)).isEmpty();

        // Even with a permissive cluster weight/sum threshold, minTrustedClusters=2 keeps 3 tier-3 sources ACTIVE.
        PolicyProperties relaxedClusterThreshold = new PolicyProperties(
                1, 0.20, 3, 2, 0.60, 0.50, 1.50, 1.00, 0.20, false);
        ResolutionEngine relaxedEngine = new ResolutionEngine(relaxedClusterThreshold, registry);
        assertThat(relaxedEngine.evaluate(q, items, List.of(), NOW)).isEmpty();
    }

    @Test
    void scenario5_threeConfirmingAndThreeDenyingTier1ArticlesReturnDisputed() {
        Question q = sampleQuestion();
        List<EvidenceWithAssessment> items = List.of(
                confirmingItem(uuid(51), q.id(), "reuters.com", "Reuters Confirms Snap Vote",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(52), q.id(), "apnews.com", "AP Confirms Snap Vote",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited"),
                confirmingItem(uuid(53), q.id(), "bbc.com", "BBC Confirms Snap Vote",
                        ORTHOGONAL_SIMHASHES[2], NOW, "official_source_cited"),
                denyingItem(uuid(54), q.id(), "ft.com", "FT Reports Cabinet Denied Vote",
                        ORTHOGONAL_SIMHASHES[3], NOW, "official_source_cited"),
                denyingItem(uuid(55), q.id(), "bloomberg.com", "Bloomberg Says Election Cancelled",
                        ORTHOGONAL_SIMHASHES[4], NOW, "official_source_cited"),
                denyingItem(uuid(56), q.id(), "wsj.com", "WSJ Reports No Snap Election",
                        ORTHOGONAL_SIMHASHES[5], NOW, "official_source_cited")
        );

        Optional<Resolution> result = engine.evaluate(q, items, List.of(), NOW);

        assertThat(result).isPresent();
        Resolution res = result.get();
        assertThat(res.outcome()).isEqualTo(QuestionStatus.DISPUTED);
        assertThat(res.confidence()).isEqualTo(0.50);
        assertThat(res.confirmSum()).isGreaterThanOrEqualTo(1.50);
        assertThat(res.denySum()).isGreaterThanOrEqualTo(1.50);
    }

    @Test
    void scenario6_articlePublishedFiveDaysBeforeWindowStartIsIgnored() {
        Question q = sampleQuestion();
        Instant fiveDaysBeforeWindow = WINDOW_START.minus(Duration.ofDays(5));

        List<EvidenceWithAssessment> items = List.of(
                confirmingItem(uuid(61), q.id(), "reuters.com", "Fresh Reuters Report",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(62), q.id(), "apnews.com", "Fresh AP Report",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited"),
                confirmingItem(uuid(63), q.id(), "bbc.com", "Old Recap From Five Days Before Window",
                        ORTHOGONAL_SIMHASHES[2], fiveDaysBeforeWindow, "official_source_cited")
        );

        // Only 2 fresh clusters remain -> below minConfirmClusters=3 -> stays ACTIVE.
        assertThat(engine.evaluate(q, items, List.of(), NOW)).isEmpty();
    }

    @Test
    void scenario7_articleWithInjectionFlagTrueGetsZeroWeightAndIsIgnored() {
        Question q = sampleQuestion();
        Evidence injectedEv = evidence(
                uuid(73), q.id(), "bbc.com", "BBC Article With Prompt Injection",
                ORTHOGONAL_SIMHASHES[2], NOW, FetchStatus.OK);
        Assessment injectedAssessment = new Assessment(
                uuid(173), uuid(73), q.id(), "jev-1.14.0",
                0.99, 0.99, 0.99, 0.0, 0.0, 0.0,
                false, 0.99, 0.99, "official_source_cited", true,
                250, "req-sha", "{}", NOW
        );

        List<EvidenceWithAssessment> items = List.of(
                confirmingItem(uuid(71), q.id(), "reuters.com", "Clean Reuters Report",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(72), q.id(), "apnews.com", "Clean AP Report",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited"),
                new EvidenceWithAssessment(injectedEv, injectedAssessment)
        );

        // Only 2 clean clusters remain -> stays ACTIVE.
        assertThat(engine.evaluate(q, items, List.of(), NOW)).isEmpty();
    }

    @Test
    void scenario8_pastDeadlineAndGraceWithZeroConfirmingEvidenceAndHealthyScansResolvesNo() {
        Question q = sampleQuestion();
        List<ScanLog> healthyScans = List.of(
                scanLog(uuid(201), q.id(), true),
                scanLog(uuid(202), q.id(), true),
                scanLog(uuid(203), q.id(), true)
        );

        Optional<Resolution> result = engine.evaluate(q, List.of(), healthyScans, AFTER_FINAL);

        assertThat(result).isPresent();
        Resolution res = result.get();
        assertThat(res.outcome()).isEqualTo(QuestionStatus.RESOLVED_NO);
        assertThat(res.confidence()).isEqualTo(0.99);
        assertThat(res.confirmSum()).isZero();
        assertThat(res.denySum()).isZero();
        assertThat(res.independentClusters()).isZero();
        assertThat(res.supportingEvidenceIds()).isEmpty();
        assertThat(res.model()).isEqualTo("none");
        assertThat(res.resolvedAt()).isEqualTo(AFTER_FINAL);
    }

    @Test
    void scenario9_pastDeadlineAndGraceWithHighFailedScanRatioReturnsUnresolvable() {
        Question q = sampleQuestion();
        List<ScanLog> scanLogs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            // 3 failed out of 10 = 30% > 20% threshold
            scanLogs.add(scanLog(uuid(300 + i), q.id(), i >= 3));
        }

        Optional<Resolution> result = engine.evaluate(q, List.of(), scanLogs, AFTER_FINAL);

        assertThat(result).isPresent();
        Resolution res = result.get();
        assertThat(res.outcome()).isEqualTo(QuestionStatus.UNRESOLVABLE);
        assertThat(res.confidence()).isZero();
        assertThat(res.details()).contains("failed scan ratio=0.3");

        // Zero scans recorded also implies coverage gap (failedRatio = 1.0) -> UNRESOLVABLE, never false NO.
        Optional<Resolution> zeroScansResult = engine.evaluate(q, List.of(), List.of(), AFTER_FINAL);
        assertThat(zeroScansResult).isPresent();
        assertThat(zeroScansResult.get().outcome()).isEqualTo(QuestionStatus.UNRESOLVABLE);
    }

    @Test
    void scenario10_replayDeterminismProducesIdenticalResolutionAcrossRepeatedAndShuffledRuns() {
        Question q = sampleQuestion();
        List<EvidenceWithAssessment> items = List.of(
                confirmingItem(uuid(81), q.id(), "reuters.com", "Reuters Report",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(82), q.id(), "apnews.com", "AP Report",
                        ORTHOGONAL_SIMHASHES[1], NOW, "direct_reporting"),
                confirmingItem(uuid(83), q.id(), "bbc.com", "BBC Report",
                        ORTHOGONAL_SIMHASHES[2], NOW, "official_source_cited"),
                confirmingItem(uuid(84), q.id(), "cnn.com", "CNN Report",
                        ORTHOGONAL_SIMHASHES[3], NOW, "direct_reporting")
        );
        List<ScanLog> logs = List.of(scanLog(uuid(401), q.id(), true));

        Optional<Resolution> first = engine.evaluate(q, items, logs, NOW);
        Optional<Resolution> second = engine.evaluate(q, items, logs, NOW);

        List<EvidenceWithAssessment> reversed = new ArrayList<>(items);
        Collections.reverse(reversed);
        Optional<Resolution> third = engine.evaluate(q, reversed, logs, NOW);

        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
    }

    @Test
    void identicalNormalizedHeadlinesCollapseAcrossDomains() {
        Question q = sampleQuestion();
        List<EvidenceWithAssessment> items = List.of(
                confirmingItem(uuid(91), q.id(), "reuters.com", "Country X Calls Snap Election!",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(92), q.id(), "apnews.com", "  country x   calls snap election ",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited"),
                confirmingItem(uuid(93), q.id(), "bbc.com", "Country X Calls Snap Election?",
                        ORTHOGONAL_SIMHASHES[2], NOW, "official_source_cited")
        );

        assertThat(engine.evaluate(q, items, List.of(), NOW)).isEmpty();
    }

    @Test
    void staysActiveBetweenDeadlineAndFinalGraceWindow() {
        Question q = sampleQuestion();
        List<ScanLog> healthyScans = List.of(scanLog(uuid(410), q.id(), true));

        assertThat(engine.evaluate(q, List.of(), healthyScans, BEFORE_FINAL)).isEmpty();
    }

    @Test
    void postDeadlinePartialEvidenceReturnsDisputedAndWeakEvidenceReturnsResolvedNo() {
        Question q = sampleQuestion();
        List<ScanLog> healthyScans = List.of(scanLog(uuid(420), q.id(), true));

        // 2 confirming tier-1 items -> confirmSum = 1.805 >= 1.00, below YES quorum -> DISPUTED
        List<EvidenceWithAssessment> partialItems = List.of(
                confirmingItem(uuid(94), q.id(), "reuters.com", "Reuters Report",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(95), q.id(), "apnews.com", "AP Report",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited")
        );
        Optional<Resolution> partialRes = engine.evaluate(q, partialItems, healthyScans, AFTER_FINAL);
        assertThat(partialRes).isPresent();
        assertThat(partialRes.get().outcome()).isEqualTo(QuestionStatus.DISPUTED);
        assertThat(partialRes.get().confidence()).isEqualTo(0.50);
        assertThat(partialRes.get().details()).isEqualTo("Deadline passed with partial evidence below YES quorum");

        // 1 confirming tier-1 item -> confirmSum = 0.9025 < 1.00 -> RESOLVED_NO
        List<EvidenceWithAssessment> weakItems = List.of(partialItems.get(0));
        Optional<Resolution> weakRes = engine.evaluate(q, weakItems, healthyScans, AFTER_FINAL);
        assertThat(weakRes).isPresent();
        assertThat(weakRes.get().outcome()).isEqualTo(QuestionStatus.RESOLVED_NO);
        assertThat(weakRes.get().confidence())
                .isCloseTo(Math.max(0.50, Math.min(0.99, 1.0 - (0.9025 / 2.20))), within(1e-9));
    }

    @Test
    void blockedSourceAndLowStanceFitsAreIgnoredAndTopFiveClusterCapApplies() {
        Question q = sampleQuestion();

        // Blocked source (theonion.com = 0.0) and low pStanceFits (< 0.30) must be ignored
        Evidence onionEv = evidence(uuid(101), q.id(), "theonion.com", "Satire Headline",
                ORTHOGONAL_SIMHASHES[6], NOW, FetchStatus.OK);
        Assessment onionAssessment = assessment(uuid(201), uuid(101), q.id(), 0.99, 0.99, 0.99, 0.0,
                false, 0.99, 0.99, "official_source_cited", false);

        Evidence lowFitEv = evidence(uuid(102), q.id(), "nytimes.com", "Unrelated Commentary",
                ORTHOGONAL_SIMHASHES[7], NOW, FetchStatus.OK);
        Assessment lowFitAssessment = assessment(uuid(202), uuid(102), q.id(), 0.99, 0.25, 0.99, 0.0,
                false, 0.99, 0.99, "official_source_cited", false);

        List<EvidenceWithAssessment> sixValidPlusTwoIgnored = List.of(
                new EvidenceWithAssessment(onionEv, onionAssessment),
                new EvidenceWithAssessment(lowFitEv, lowFitAssessment),
                confirmingItem(uuid(111), q.id(), "reuters.com", "Reuters Story",
                        ORTHOGONAL_SIMHASHES[0], NOW, "official_source_cited"),
                confirmingItem(uuid(112), q.id(), "apnews.com", "AP Story",
                        ORTHOGONAL_SIMHASHES[1], NOW, "official_source_cited"),
                confirmingItem(uuid(113), q.id(), "bbc.com", "BBC Story",
                        ORTHOGONAL_SIMHASHES[2], NOW, "official_source_cited"),
                confirmingItem(uuid(114), q.id(), "ft.com", "FT Story",
                        ORTHOGONAL_SIMHASHES[3], NOW, "official_source_cited"),
                confirmingItem(uuid(115), q.id(), "bloomberg.com", "Bloomberg Story",
                        ORTHOGONAL_SIMHASHES[4], NOW, "official_source_cited"),
                confirmingItem(uuid(116), q.id(), "cnn.com", "CNN Story",
                        ORTHOGONAL_SIMHASHES[5], NOW, "direct_reporting")
        );

        Optional<Resolution> result = engine.evaluate(q, sixValidPlusTwoIgnored, List.of(), NOW);

        assertThat(result).isPresent();
        Resolution res = result.get();
        assertThat(res.outcome()).isEqualTo(QuestionStatus.RESOLVED_YES);
        assertThat(res.independentClusters()).isEqualTo(6);
        assertThat(res.supportingEvidenceIds()).hasSize(5);
        assertThat(res.supportingEvidenceIds()).containsExactly(uuid(111), uuid(112), uuid(113), uuid(114), uuid(115));
        // Top 5 are the five tier-1 items (5 * 0.9025 = 4.5125); tier-2 cnn.com is 6th and excluded from top-5 sum.
        assertThat(res.confirmSum()).isCloseTo(5 * 0.9025, within(1e-9));
        assertThat(res.confidence()).isEqualTo(0.99);
    }

    @Test
    void stepAWeightingFactorsApplyTitleOnlyUnstableStanceAttributionAndSubHalfFreshness() {
        Question q = sampleQuestion();
        // fetch=0.4 (TITLE_ONLY), stability=0.5 (stanceUnstable), attr=0.2 (opinion_or_analysis),
        // fresh=0.40 (<0.50), tier=1.0 (reuters.com), pRelevant=0.80, pConfirms=0.90
        // expected confirmWeight = 0.80 * 0.40 * 1.0 * 0.2 * 0.4 * 0.5 * 0.90 = 0.01152
        Evidence titleOnlyEv = evidence(uuid(121), q.id(), "reuters.com", "Opinion Title Only",
                ORTHOGONAL_SIMHASHES[8], NOW, FetchStatus.TITLE_ONLY);
        Assessment unstableOpinion = assessment(uuid(221), uuid(121), q.id(),
                0.80, 0.80, 0.90, 0.0,
                true, 0.40, 0.40, "opinion_or_analysis", false);

        Optional<Resolution> res = engine.evaluate(
                q,
                List.of(new EvidenceWithAssessment(titleOnlyEv, unstableOpinion)),
                List.of(scanLog(uuid(430), q.id(), true)),
                AFTER_FINAL
        );

        assertThat(res).isPresent();
        assertThat(res.get().outcome()).isEqualTo(QuestionStatus.RESOLVED_NO);
        assertThat(res.get().confirmSum()).isCloseTo(0.01152, within(1e-9));
    }

    private static Question sampleQuestion() {
        return new Question(
                uuid(1),
                "Has Country X officially announced a snap election?",
                "Official decree or statement by the Prime Minister or Election Commission.",
                WINDOW_START,
                DEADLINE,
                GRACE_HOURS,
                QuestionStatus.ACTIVE,
                null,
                List.of("\"Country X\" election"),
                null,
                null,
                WINDOW_START,
                WINDOW_START
        );
    }

    private static EvidenceWithAssessment confirmingItem(
            UUID evidenceId,
            UUID questionId,
            String domain,
            String title,
            long simhash,
            Instant publishedAt,
            String attribution
    ) {
        Evidence ev = evidence(evidenceId, questionId, domain, title, simhash, publishedAt, FetchStatus.OK);
        Assessment a = assessment(
                UUID.nameUUIDFromBytes(("assess-" + evidenceId).getBytes(StandardCharsets.UTF_8)),
                evidenceId,
                questionId,
                0.95, 0.95, 0.95, 0.0,
                false, 0.95, 0.95, attribution, false
        );
        return new EvidenceWithAssessment(ev, a);
    }

    private static EvidenceWithAssessment denyingItem(
            UUID evidenceId,
            UUID questionId,
            String domain,
            String title,
            long simhash,
            Instant publishedAt,
            String attribution
    ) {
        Evidence ev = evidence(evidenceId, questionId, domain, title, simhash, publishedAt, FetchStatus.OK);
        Assessment a = assessment(
                UUID.nameUUIDFromBytes(("assess-" + evidenceId).getBytes(StandardCharsets.UTF_8)),
                evidenceId,
                questionId,
                0.95, 0.95, 0.0, 0.95,
                false, 0.95, 0.95, attribution, false
        );
        return new EvidenceWithAssessment(ev, a);
    }

    private static Evidence evidence(
            UUID id,
            UUID questionId,
            String domain,
            String title,
            long simhash,
            Instant publishedAt,
            FetchStatus fetchStatus
    ) {
        String url = "https://" + domain + "/news/" + id;
        return new Evidence(
                id,
                questionId,
                url,
                url,
                domain,
                title,
                NOW,
                publishedAt,
                "English",
                "United Kingdom",
                EvidenceStage.ASSESSED,
                fetchStatus,
                0.95,
                "sha256-" + id,
                title,
                simhash,
                NOW
        );
    }

    private static Assessment assessment(
            UUID id,
            UUID evidenceId,
            UUID questionId,
            double pRelevant,
            double pStanceFits,
            double pConfirms,
            double pDenies,
            boolean stanceUnstable,
            double stanceConfidence,
            double pFresh,
            String attribution,
            boolean injectionFlag
    ) {
        return new Assessment(
                id,
                evidenceId,
                questionId,
                "jev-1.14.0",
                pRelevant,
                pStanceFits,
                pConfirms,
                0.0,
                0.0,
                pDenies,
                stanceUnstable,
                stanceConfidence,
                pFresh,
                attribution,
                injectionFlag,
                300,
                "req-sha-" + id,
                "{}",
                NOW
        );
    }

    private static ScanLog scanLog(UUID id, UUID questionId, boolean gdeltOk) {
        return new ScanLog(
                id,
                questionId,
                NOW.minus(Duration.ofMinutes(15)),
                NOW,
                gdeltOk,
                gdeltOk ? 10 : 0,
                0,
                gdeltOk ? null : "HTTP 503"
        );
    }

    private static UUID uuid(int n) {
        return new UUID(0L, n);
    }
}

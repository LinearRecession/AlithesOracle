package io.alithes.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.alithes.oracle.client.CooldownGuard;
import io.alithes.oracle.config.AdmissionProperties;
import io.alithes.oracle.config.HttpClientProperties;
import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.config.PolicyProperties;
import io.alithes.oracle.config.ScoringProperties;
import io.alithes.oracle.health.OracleHealthIndicator;
import io.alithes.oracle.service.resolution.AttributionTier;
import io.alithes.oracle.service.resolution.ClusterSummary;
import io.alithes.oracle.service.resolution.ResolutionContext;
import io.alithes.oracle.service.resolution.ResolutionRule;
import io.alithes.oracle.service.resolution.ResolutionRules;
import io.alithes.oracle.service.resolution.RuleOutcome;
import io.alithes.oracle.domain.Question;
import io.alithes.oracle.domain.QuestionStatus;
import io.alithes.oracle.util.BoundedStreamReader;
import io.alithes.oracle.web.filter.RequestCorrelationFilter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ProductionArchitectureTest {

    @Test
    void oraclePropertiesDefaultsAndValidationEnforceConstraints() {
        OracleProperties defaults = OracleProperties.defaults();
        assertThat(defaults.http().connectTimeoutMs()).isEqualTo(10_000L);
        assertThat(defaults.admission().maxAmbiguousScore()).isEqualTo(0.65);
        assertThat(defaults.query().maxCompiledQueries()).isEqualTo(2);
        assertThat(defaults.assessor().screeningMaxInputParagraphs()).isEqualTo(25);
        assertThat(defaults.synthesis().maxReports()).isEqualTo(8);
        assertThat(defaults.scoring().trustedTierThreshold()).isEqualTo(0.70);

        assertThatThrownBy(() -> new HttpClientProperties(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AdmissionProperties(20, 10, 80, 0.6, 0.65, 0.5, 48, 720, 30, 79))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attributionTierMapsKnownAndFallbackKeys() {
        assertThat(AttributionTier.fromKey("official_source_cited")).isEqualTo(AttributionTier.OFFICIAL_SOURCE_CITED);
        assertThat(AttributionTier.fromKey(" DIRECT_REPORTING ")).isEqualTo(AttributionTier.DIRECT_REPORTING);
        assertThat(AttributionTier.fromKey("opinion_or_analysis").weight()).isEqualTo(0.2);
        assertThat(AttributionTier.fromKey(null)).isEqualTo(AttributionTier.DEFAULT);
        assertThat(AttributionTier.fromKey("unknown_style").weight()).isEqualTo(0.5);
    }

    @Test
    void cooldownGuardTracksActiveWindowAndExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
        CooldownGuard guard = new CooldownGuard(clock);
        assertThat(guard.isActive()).isFalse();

        guard.activate(Duration.ofSeconds(60));
        assertThat(guard.isActive()).isTrue();

        clock.advance(Duration.ofSeconds(59));
        assertThat(guard.isActive()).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(guard.isActive()).isFalse();
    }

    @Test
    void boundedStreamReaderCapsLargeStreams() throws Exception {
        byte[] input = "abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        byte[] capped = BoundedStreamReader.readCappedBytes(new ByteArrayInputStream(input), 10, 4);
        assertThat(new String(capped, StandardCharsets.UTF_8)).isEqualTo("abcdefghij");
        assertThat(BoundedStreamReader.readCappedBytes(new ByteArrayInputStream(input), 0, 4)).isEmpty();
    }

    @Test
    void resolutionRuleChainOrdersContradictionBeforeQuorumAndDeadline() {
        Instant now = Instant.parse("2026-11-24T12:00:00Z");
        Question active = new Question(
                UUID.randomUUID(),
                "Has Event X occurred?",
                null,
                now.minus(Duration.ofDays(5)),
                now.plus(Duration.ofDays(1)),
                24,
                QuestionStatus.ACTIVE,
                "Admitted",
                List.of("\"Event X\""),
                null,
                null,
                now.minus(Duration.ofDays(5)),
                now
        );

        ClusterSummary disputed = new ClusterSummary(4, 3, 2.5, 2.0, List.of());
        ResolutionContext ctx = new ResolutionContext(
                active, disputed, List.of(), PolicyProperties.defaults(), ScoringProperties.defaults(), "jev-1.14.0", now);

        RuleOutcome outcome = null;
        for (ResolutionRule rule : ResolutionRules.defaultChain()) {
            var opt = rule.evaluate(ctx);
            if (opt.isPresent()) {
                outcome = opt.get();
                break;
            }
        }
        assertThat(outcome).isInstanceOf(RuleOutcome.Resolved.class);
        assertThat(((RuleOutcome.Resolved) outcome).resolution().outcome()).isEqualTo(QuestionStatus.DISPUTED);
    }

    @Test
    void requestCorrelationFilterEchoesValidIdGeneratesMissingIdAndClearsMdc() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter();

        MockHttpServletRequest incomingReq = new MockHttpServletRequest();
        incomingReq.addHeader(RequestCorrelationFilter.HEADER_NAME, "req-abc-123");
        MockHttpServletResponse incomingResp = new MockHttpServletResponse();
        filter.doFilter(incomingReq, incomingResp, new MockFilterChain());

        assertThat(incomingResp.getHeader(RequestCorrelationFilter.HEADER_NAME)).isEqualTo("req-abc-123");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();

        MockHttpServletRequest emptyReq = new MockHttpServletRequest();
        emptyReq.addHeader(RequestCorrelationFilter.HEADER_NAME, "bad\nheader");
        MockHttpServletResponse generatedResp = new MockHttpServletResponse();
        filter.doFilter(emptyReq, generatedResp, new MockFilterChain());

        String generated = generatedResp.getHeader(RequestCorrelationFilter.HEADER_NAME);
        assertThat(generated).isNotBlank().matches("[0-9a-f-]{36}");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void healthIndicatorReportsSafeOperationalDetailsWithoutExposingSecrets() {
        OracleProperties props = OracleProperties.defaults();
        OracleHealthIndicator indicator = new OracleHealthIndicator(props);

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("jevModel", "jev-1.14.0")
                .containsEntry("jevApiKeyConfigured", false)
                .containsEntry("schedulerEnabled", false)
                .containsEntry("policyVersion", 1)
                .doesNotContainKey("apiKey");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

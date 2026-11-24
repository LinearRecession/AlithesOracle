package io.alithes.oracle.health;

import io.alithes.oracle.config.OracleProperties;
import java.util.Objects;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Boot Actuator {@link HealthIndicator} reporting non-sensitive Oracle
 * configuration readiness alongside standard database and probe checks.
 */
@Component("oracle")
public class OracleHealthIndicator implements HealthIndicator {

    private final OracleProperties props;

    public OracleHealthIndicator(OracleProperties props) {
        this.props = Objects.requireNonNull(props, "props must not be null");
    }

    @Override
    public Health health() {
        String apiKey = props.jev().apiKey();
        boolean apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        return Health.up()
                .withDetail("jevModel", props.jev().model())
                .withDetail("jevApiKeyConfigured", apiKeyConfigured)
                .withDetail("schedulerEnabled", props.scanner().enabled())
                .withDetail("policyVersion", props.policy().version())
                .build();
    }
}

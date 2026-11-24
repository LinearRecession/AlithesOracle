package io.alithes.oracle.config;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the shared {@link java.net.http.HttpClient} bean ({@code oracle.http.*}).
 */
public record HttpClientProperties(
        @DefaultValue("10000") long connectTimeoutMs
) {
    public HttpClientProperties {
        PropertyAssertions.requirePositive(connectTimeoutMs, "oracle.http.connect-timeout-ms");
    }

    public static HttpClientProperties defaults() {
        return new HttpClientProperties(10_000L);
    }
}

package io.alithes.oracle.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core infrastructure beans for UTC time and HTTP transport.
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public HttpClient httpClient(OracleProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.http().connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}

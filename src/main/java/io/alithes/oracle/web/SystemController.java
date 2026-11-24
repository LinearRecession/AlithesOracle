package io.alithes.oracle.web;

import io.alithes.oracle.config.OracleProperties;
import io.alithes.oracle.service.QuestionService;
import io.alithes.oracle.web.dto.SystemStatusResponse;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read-only operational status and policy configuration for the management console.
 */
@RestController
@RequestMapping("/v1/system")
public class SystemController {

    private final OracleProperties props;
    private final QuestionService questionService;

    public SystemController(OracleProperties props, QuestionService questionService) {
        this.props = Objects.requireNonNull(props, "props must not be null");
        this.questionService = Objects.requireNonNull(questionService, "questionService must not be null");
    }

    @GetMapping
    public SystemStatusResponse getSystemStatus() {
        String apiKey = props.jev().apiKey();
        boolean apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        return new SystemStatusResponse(
                props.jev().model(),
                apiKeyConfigured,
                props.jev().baseUrl(),
                props.gdelt().baseUrl(),
                props.scanner().enabled(),
                props.scanner().fixedDelayMs(),
                props.policy().version(),
                props.policy(),
                questionService.countQuestionsByStatus()
        );
    }
}

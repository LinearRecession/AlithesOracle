package io.alithes.oracle.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a per-request correlation ID ({@code X-Request-Id}) and binds it into SLF4J MDC
 * for structured trace logging across the HTTP lifecycle.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_NAME);
        if (incoming != null) {
            String trimmed = incoming.trim();
            if (VALID_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString();
    }
}

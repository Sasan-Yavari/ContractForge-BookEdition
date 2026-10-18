package io.contractforge.backend.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds an untrusted-client-independent correlation value to each HTTP request and its logs. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String RESPONSE_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private static final Logger LOG = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        String correlationId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(RESPONSE_HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            throw failure;
        } finally {
            LOG.atInfo()
                    .addKeyValue("event_type", "http_request_completed")
                    .addKeyValue("http_method", request.getMethod())
                    .addKeyValue("http_status", response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - startedAt) / 1_000_000L)
                    .log("http_request_completed");
            if (previousContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previousContext);
            }
        }
    }
}

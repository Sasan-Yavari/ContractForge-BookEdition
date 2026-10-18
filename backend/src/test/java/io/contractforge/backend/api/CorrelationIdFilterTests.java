package io.contractforge.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTests {
    @Test
    void generatesResponseCorrelationIdAndExposesItInMdcWithoutTrustingCallerHeader() throws Exception {
        var request = new MockHttpServletRequest("GET", "/v1/health");
        request.addHeader(CorrelationIdFilter.RESPONSE_HEADER, "caller-controlled-id");
        var response = new MockHttpServletResponse();
        var filter = new CorrelationIdFilter();

        FilterChain chain = (servletRequest, servletResponse) -> {
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(response.getHeader(CorrelationIdFilter.RESPONSE_HEADER));
        };
        filter.doFilter(request, response, chain);

        UUID.fromString(response.getHeader(CorrelationIdFilter.RESPONSE_HEADER));
        assertThat(response.getHeader(CorrelationIdFilter.RESPONSE_HEADER)).isNotEqualTo("caller-controlled-id");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void keepsCorrelationHeaderAndClearsMdcWhenRequestFails() {
        var request = new MockHttpServletRequest("GET", "/v1/health");
        var response = new MockHttpServletResponse();
        var filter = new CorrelationIdFilter();

        FilterChain chain = (servletRequest, servletResponse) -> {
            throw new ServletException("private failure detail");
        };
        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(ServletException.class);

        UUID.fromString(response.getHeader(CorrelationIdFilter.RESPONSE_HEADER));
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}

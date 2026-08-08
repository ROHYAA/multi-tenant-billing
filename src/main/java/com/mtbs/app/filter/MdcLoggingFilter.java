package com.mtbs.app.filter;

import com.mtbs.shared.util.SecurityUtils;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MdcLoggingFilter implements Filter {

    private static final String HEADER_REQUEST_ID = "X-Request-Id";
    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        long startTime = System.currentTimeMillis();

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        try {
            // ===============================
            // 🔹 Request ID (Correlation ID)
            // ===============================
            String requestId = getOrGenerate(httpRequest.getHeader(HEADER_REQUEST_ID), 12);
            MDC.put("requestId", requestId);
            httpResponse.setHeader(HEADER_REQUEST_ID, requestId);

            // ===============================
            // 🔹 TRACE ID (Distributed tracing)
            // ===============================
            String traceId = getOrGenerate(httpRequest.getHeader(HEADER_TRACE_ID), 16);
            MDC.put("traceId", traceId);
            httpResponse.setHeader(HEADER_TRACE_ID, traceId);

            // ===============================
            // 🔹 SPAN ID (this service execution)
            // ===============================
            String spanId = UUID.randomUUID().toString().substring(0, 16);
            MDC.put("spanId", spanId);

            // ===============================
            // 🔹 BASIC REQUEST INFO
            // ===============================
            MDC.put("method", httpRequest.getMethod());
            MDC.put("uri", httpRequest.getRequestURI());
            MDC.put("query", httpRequest.getQueryString());
            MDC.put("clientIp", getClientIp(httpRequest));
            MDC.put("userAgent", httpRequest.getHeader("User-Agent"));
            MDC.put("timestamp", Instant.now().toString());


            // ===============================
            // 🔹 EXECUTE REQUEST
            // ===============================
            chain.doFilter(request, response);

        } finally {
            long duration = System.currentTimeMillis() - startTime;

            MDC.put("status", String.valueOf(httpResponse.getStatus()));
            MDC.put("durationMs", String.valueOf(duration));

            log.info("Request completed");

            MDC.clear();
        }
    }

    private String getOrGenerate(String value, int length) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, length);
        }
        return value;
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) {
            return request.getRemoteAddr();
        }
        return xfHeader.split(",")[0];
    }
}
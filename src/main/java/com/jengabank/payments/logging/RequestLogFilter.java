package com.jengabank.payments.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Structured access log: one {@code http.request} JSON line per request with the
 * JengaSec key set, plus request-scoped src_ip/method/path for every security
 * event emitted while handling that request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private final SecurityEventLogger events;

    public RequestLogFilter(SecurityEventLogger events) {
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String forwarded = request.getHeader("X-Forwarded-For");
        String srcIp = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        events.bind(srcIp, request.getMethod(), request.getRequestURI());
        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            String user = request.getHeader(SecurityEventLogger.ID_HEADER);
            events.emit(Actions.HTTP_REQUEST, response.getStatus(),
                    user == null || user.isBlank() ? "anonymous" : user,
                    Map.of("duration_ms", durationMs));
            events.unbind();
        }
    }
}

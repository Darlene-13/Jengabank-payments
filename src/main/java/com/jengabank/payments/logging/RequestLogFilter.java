package com.jengabank.payments.logging;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before Spring Security, so even rejected (401/403) requests get logged.
 * Sets the request ID used in every log line and every error body.
 * One JSON line per request: ts, src_ip, method, path, status, user, action.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    /** Dev 2: the token filter must set this attribute to the username once authenticated. */
    public static final String USER_ATTRIBUTE = "jb.username";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    // Only accept safe client IDs, so nobody can inject junk into our logs.
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final SecurityEventLogger events;

    public RequestLogFilter(SecurityEventLogger events) {
        this.events = events;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        // src_ip = the address our app actually sees. X-Forwarded-For is client-controlled.
        events.bind(request.getRemoteAddr(), request.getMethod(), request.getRequestURI());
        long start = System.nanoTime();

        try {
            chain.doFilter(request, response);
        } finally {
            events.emit(Actions.HTTP_REQUEST, response.getStatus(), user(request),
                    Map.of("request_id", requestId,
                           "duration_ms", (System.nanoTime() - start) / 1_000_000));
            events.unbind();
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    private static String user(HttpServletRequest request) {
        Object user = request.getAttribute(USER_ATTRIBUTE);
        if (user != null) {
            return user.toString();
        }
        // Until Dev 2's token filter lands, the maker-checker layer still reads this header.
        return request.getHeader(SecurityEventLogger.ID_HEADER);
    }
}

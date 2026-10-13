package com.jengabank.payments.logging;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before Spring Security, so even rejected (401/403) requests get logged.
 * Sets the request ID used in every log line and every error body.
 * One JSON line per request: ts, src_ip, method, path, status, user, action.
 * user is empty when nobody is signed in. action is the request's most severe security
 * event (for example transfer.blocked), or http.request when there was none.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "jb.username";
    /** Set by SecurityEventLogger. */
    public static final String ACTION_ATTRIBUTE = "jb.action";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";

    private static final Logger log = LoggerFactory.getLogger("http");
    // Only accept safe client IDs, so nobody can inject junk into our logs.
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private final ClientIpResolver clientIp;

    public RequestLogFilter(ClientIpResolver clientIp) {
        this.clientIp = clientIp;
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
        long start = System.nanoTime();

        try {
            chain.doFilter(request, response);
        } finally {
            Object user = request.getAttribute(USER_ATTRIBUTE);
            Object action = request.getAttribute(ACTION_ATTRIBUTE);
            log.info("request",
                    kv("src_ip", clientIp.resolve(request)),
                    kv("method", request.getMethod()),
                    kv("path", request.getRequestURI()),
                    kv("status", response.getStatus()),
                    kv("user", user == null ? "" : user.toString()),
                    kv("action", action == null ? Actions.HTTP_REQUEST : action.toString()),
                    kv("duration_ms", (System.nanoTime() - start) / 1_000_000));
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}

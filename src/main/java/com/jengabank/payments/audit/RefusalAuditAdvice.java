package com.jengabank.payments.audit;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.jengabank.payments.error.ApiError;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RequestLogFilter;

/**
 * Writes the audit record for a {@link RefusalException}. By the time this runs the
 * service's transaction has rolled back and returned its connection, so the audit
 * write never holds two pool connections at once. Same body as GlobalExceptionHandler.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RefusalAuditAdvice {

    private static final Logger log = LoggerFactory.getLogger("security");

    private final AuditService audit;

    public RefusalAuditAdvice(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(RefusalException.class)
    public ResponseEntity<ApiError> refused(RefusalException ex) {
        try {
            audit.recordRefusal(ex.actor(), ex.action(), ex.target(), ex.details());
        } catch (DataAccessException e) {
            // The refusal itself stands; the security log line already captured it.
            log.error("could not write refusal audit record", kv("action", Actions.SERVER_ERROR),
                    kv("refused_action", ex.action()), e);
        }
        return ResponseEntity.status(ex.status())
                .body(new ApiError(ex.code(), ex.getMessage(), MDC.get(RequestLogFilter.MDC_REQUEST_ID), ex.reasons()));
    }
}

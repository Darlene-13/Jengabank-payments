package com.jengabank.payments.error;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.sql.SQLException;
import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RequestLogFilter;
import com.jengabank.payments.logging.SecurityEventLogger;

/**
 * Turns every exception into { code, message, requestId }.
 * Never returns stack traces, SQL, class names or database messages.
 * Rejections go through SecurityEventLogger, so the request's http line carries the event too.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("security");

    private final SecurityEventLogger events;

    public GlobalExceptionHandler(SecurityEventLogger events) {
        this.events = events;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex) {
        if (ex.status() == HttpStatus.BAD_REQUEST) {
            events.warn(Actions.REQUEST_INVALID, "code", ex.code());
        }
        return body(ex.status(), ex.code(), ex.getMessage(), ex.reasons());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
        List<String> reasons = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        boolean badAmount = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(f -> "amountMinor".equals(f.getField()));
        events.warn(badAmount ? Actions.TRANSFER_INVALID_AMOUNT : Actions.REQUEST_INVALID, "reasons", reasons);
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid.", reasons);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidParams(Exception ex) {
        events.warn(Actions.REQUEST_INVALID, "type", ex.getClass().getSimpleName());
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Some details in the request aren't valid.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        events.warn(Actions.REQUEST_INVALID, "reason", "unreadable_body");
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body isn't valid JSON, or has fields we don't accept.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex) {
        events.warn(Actions.REQUEST_INVALID, "reason", "missing_header", "header", ex.getHeaderName());
        return body(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        events.warn(Actions.REQUEST_INVALID, "reason", "unknown_route");
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "We couldn't find that.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex) {
        events.warn(Actions.REQUEST_INVALID, "reason", "method_not_allowed");
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "That method isn't allowed here.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex) {
        events.warn(Actions.REQUEST_INVALID, "reason", "unsupported_media_type");
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the request as application/json.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException ex) {
        events.warn(Actions.AUTHZ_ACCESS_DENIED, "reason", "role");
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", "Your role can't do this.");
    }

    /** Database guards from V1-V3 end up here. Map them by SQLSTATE. */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> database(Exception ex) {
        String state = sqlState(ex);
        if (state == null) {
            return serverError(ex);
        }
        switch (state) {
            case "23514": // check_violation: unbalanced journal, overdraft, illegal status change, self-approval
                events.warn(Actions.DB_GUARD_TRIGGERED, "sqlstate", state);
                return body(HttpStatus.UNPROCESSABLE_ENTITY, "RULE_VIOLATION", "This was stopped by a bank rule.");
            case "23505": // unique_violation: duplicate reversal, duplicate idempotency key
                return body(HttpStatus.CONFLICT, "CONFLICT", "This conflicts with an earlier request.");
            case "23503": // foreign_key_violation
                return body(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Something in the request refers to a record that doesn't exist.");
            case "40001", "40P01": // serialization failure, deadlock
                return body(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Another request changed this at the same time. Try again.");
            case "42501": // our append-only / immutability triggers: someone tried to rewrite history
                events.error(Actions.DB_GUARD_TRIGGERED, "sqlstate", state, "reason", "protected_data");
                return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The server hit a problem. Try again in a moment.");
            default:
                return serverError(ex);
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        return serverError(ex);
    }

    private ResponseEntity<ApiError> serverError(Exception ex) {
        // Full detail goes to our logs only, never to the client.
        log.error("unhandled error", kv("action", Actions.SERVER_ERROR), ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The server hit a problem. Try again in a moment.");
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message) {
        return body(status, code, message, List.of());
    }

    private static ResponseEntity<ApiError> body(HttpStatus status, String code, String message, List<String> reasons) {
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, MDC.get(RequestLogFilter.MDC_REQUEST_ID), reasons));
    }

    private static String sqlState(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}

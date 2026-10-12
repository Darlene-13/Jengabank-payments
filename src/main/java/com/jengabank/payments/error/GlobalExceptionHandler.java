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

/**
 * Turns every exception into { code, message, requestId }.
 * Never returns stack traces, SQL, class names or database messages.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("security");

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex) {
        return body(ex.status(), ex.code(), ex.getMessage(), ex.reasons());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
        List<String> reasons = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        log.warn("invalid request", kv("action", Actions.REQUEST_INVALID), kv("reasons", reasons));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Some fields are invalid.", reasons);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> invalidParams(Exception ex) {
        log.warn("invalid request", kv("action", Actions.REQUEST_INVALID), kv("type", ex.getClass().getSimpleName()));
        return body(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Some details in the request aren't valid.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        log.warn("unreadable body", kv("action", Actions.REQUEST_INVALID));
        return body(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "The request body isn't valid JSON, or has fields we don't accept.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex) {
        log.warn("missing header", kv("action", Actions.REQUEST_INVALID), kv("header", ex.getHeaderName()));
        return body(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "We couldn't find that.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "That method isn't allowed here.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException ex) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the request as application/json.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException ex) {
        log.warn("access denied", kv("action", Actions.AUTHZ_ACCESS_DENIED));
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
                log.warn("database rule blocked request", kv("action", Actions.DB_GUARD_TRIGGERED), kv("sqlstate", state));
                return body(HttpStatus.UNPROCESSABLE_ENTITY, "RULE_VIOLATION", "This was stopped by a bank rule.");
            case "23505": // unique_violation: duplicate reversal, duplicate idempotency key
                return body(HttpStatus.CONFLICT, "CONFLICT", "This conflicts with an earlier request.");
            case "23503": // foreign_key_violation
                return body(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE", "Something in the request refers to a record that doesn't exist.");
            case "40001", "40P01": // serialization failure, deadlock
                return body(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Another request changed this at the same time. Try again.");
            case "42501": // our append-only / immutability triggers: someone tried to rewrite history
                log.error("attempt to modify protected data", kv("action", Actions.DB_GUARD_TRIGGERED), kv("sqlstate", state));
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

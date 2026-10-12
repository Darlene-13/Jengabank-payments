package com.jengabank.payments.error;

import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SecurityEventLogger events;
    private final Clock clock;

    public GlobalExceptionHandler(SecurityEventLogger events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /**
     * Expected rejections (limits, fraud block, maker-checker, idempotency, ...).
     * The throwing code has already emitted the precise security event, so we only
     * render the body here - no duplicate log lines.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(clock),
                ex.status().value(),
                ex.status().getReasonPhrase(),
                ex.code(),
                ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(ex.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        return ResponseEntity.badRequest().body(
                new ApiError(Instant.now(clock), 400, "Bad Request", "validation.failed", message, request.getRequestURI()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(
                new ApiError(Instant.now(clock), 400, "Bad Request", "malformed_request",
                        "Request body could not be parsed", request.getRequestURI()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(Instant.now(clock), 404, "Not Found", "not_found",
                        "No such endpoint", request.getRequestURI()));
    }

    /** Anything unclassified: log it, emit an event, return a generic 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("path", request.getRequestURI());
        extra.put("method", request.getMethod());
        extra.put("error", ex.getClass().getSimpleName());
        events.emit(Actions.INTERNAL_ERROR, 500, resolveUser(request), extra);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError(Instant.now(clock), 500, "Internal Server Error", "internal.error",
                        "Unexpected server error", request.getRequestURI()));
    }

    private String resolveUser(HttpServletRequest request) {
        String user = request.getHeader("X-User-Id");
        return user == null || user.isBlank() ? "anonymous" : user;
    }
}

package com.jengabank.payments.error;

import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

    /**
     * Defence-in-depth guard: a database trigger or constraint rejected an
     * operation the application thought was valid. Per proposal section 8, this
     * is the "db.guard_triggered" signal that AI defenders and judges watch for -
     * injection or a logic bypass reached the database and was stopped there.
     *
     * SQLStates we treat as a guard trigger:
     *   23514 = check_violation          (V1 CHECK constraints, V3 journal & overdraft guards)
     *   23505 = unique_violation         (one live reversal per transfer, idempotency key)
     *   42501 = insufficient_privilege   (V2 forbid_change() on append-only tables)
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDbGuard(DataAccessException ex, HttpServletRequest request) {
        String sqlState = extractSqlState(ex);

        if (isGuardViolation(sqlState)) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("path", request.getRequestURI());
            details.put("method", request.getMethod());
            details.put("sql_state", sqlState);
            details.put("reason", ex.getMostSpecificCause().getMessage());
            events.emit(Actions.DB_GUARD_TRIGGERED, 422, resolveUser(request), details);

            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
                    new ApiError(Instant.now(clock), 422, "Unprocessable Entity",
                            Actions.DB_GUARD_TRIGGERED,
                            "A database guard rejected this operation",
                            request.getRequestURI()));
        }

        // Unknown DB failure - fall through to the generic 500 handler.
        return handleUnexpected(ex, request);
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

    private static String extractSqlState(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof SQLException sqlEx) {
            return sqlEx.getSQLState();
        }
        return null;
    }

    private static boolean isGuardViolation(String sqlState) {
        return "23514".equals(sqlState)   // check_violation
                || "23505".equals(sqlState)   // unique_violation
                || "42501".equals(sqlState);  // insufficient_privilege
    }

    private String resolveUser(HttpServletRequest request) {
        String user = request.getHeader("X-User-Id");
        return user == null || user.isBlank() ? "anonymous" : user;
    }
}
package com.jengabank.payments.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/** Throw this for any expected rule failure. The message is shown to the user, so keep it safe and plain. */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> reasons;

    public BusinessException(HttpStatus status, String code, String message, List<String> reasons) {
        super(message);
        this.status = status;
        this.code = code;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public BusinessException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Use for "doesn't exist" AND "not yours", so attackers can't tell the difference. */
    public static BusinessException notFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "We couldn't find that.");
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    public static BusinessException rule(String code, String message, List<String> reasons) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, reasons);
    }

    public static BusinessException rule(String code, String message) {
        return rule(code, message, List.of());
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<String> reasons() { return reasons; }
}

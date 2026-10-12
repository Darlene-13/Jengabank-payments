package com.jengabank.payments.error;

import org.springframework.http.HttpStatus;

/**
 * A business-rule rejection. Callers are expected to have already emitted the
 * matching security event to stdout and written the audit row, so the global
 * handler only renders the response.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}

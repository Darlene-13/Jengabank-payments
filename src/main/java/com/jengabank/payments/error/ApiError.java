package com.jengabank.payments.error;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The only error shape the API ever returns. */
public record ApiError(
        String code,
        String message,
        String requestId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> reasons) {

    public ApiError(String code, String message, String requestId) {
        this(code, message, requestId, List.of());
    }
}

package com.jengabank.payments.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.jengabank.payments.error.BusinessException;

/** Opaque cursors (base64url). Anything malformed is a 400, never a 500. */
public final class Cursors {

    private Cursors() {}

    public static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (cursor.length() > 200) {
            throw invalid();
        }
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    public static int limit(Integer requested) {
        if (requested == null) {
            return 50;
        }
        return Math.max(1, Math.min(100, requested));
    }

    public static BusinessException invalid() {
        return BusinessException.badRequest("INVALID_CURSOR", "That page link isn't valid. Start from the first page.");
    }
}

package com.jengabank.payments.auth;

import java.util.UUID;

/** The signed-in caller. Comes only from the token, never from the request body. */
public record AuthenticatedUser(UUID id, String username, String displayName, Role role, UUID sessionId) {

    public boolean is(Role r) {
        return role == r;
    }

    public boolean isStaff() {
        return role == Role.MAKER || role == Role.CHECKER;
    }
}

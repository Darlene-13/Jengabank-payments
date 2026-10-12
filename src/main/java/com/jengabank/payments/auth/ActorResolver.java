package com.jengabank.payments.auth;

import org.springframework.stereotype.Component;

/**
 * Resolves the caller of a request into an {@link Actor}.
 *
 * <p>Missing or unknown credentials resolve to {@code anonymous / VIEWER}, so an
 * unauthenticated caller can never satisfy a maker-checker check.
 */
@Component
public class ActorResolver {

    public Actor resolve(String userId, String userRole) {
        String id = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
        return new Actor(id, Role.parse(userRole));
    }
}

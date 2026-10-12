package com.jengabank.payments.auth;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jengabank.payments.error.BusinessException;

/** Usage anywhere: AuthenticatedUser me = CurrentUser.require(); */
public final class CurrentUser {

    private CurrentUser() {}

    public static AuthenticatedUser require() {
        return current().orElseThrow(() ->
                new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."));
    }

    public static Optional<AuthenticatedUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }
}

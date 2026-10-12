package com.jengabank.payments.auth;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.jengabank.payments.auth.AuthDtos.LoginResponse;
import com.jengabank.payments.auth.AuthDtos.MeResponse;
import com.jengabank.payments.auth.SessionService.IssuedToken;
import com.jengabank.payments.auth.UserRepository.UserRow;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;

@Service
public class AuthService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,64}$");

    private final UserRepository users;
    private final SessionService sessions;
    private final PasswordEncoder encoder;
    private final LoginRateLimiter limiter;
    private final SecurityEventLogger events;
    private final int maxFailures;
    private final int lockMinutes;
    private final String dummyHash;

    public AuthService(UserRepository users, SessionService sessions, PasswordEncoder encoder,
                       LoginRateLimiter limiter, SecurityEventLogger events,
                       @Value("${jengabank.auth.max-failed-logins:5}") int maxFailures,
                       @Value("${jengabank.auth.lockout-minutes:15}") int lockMinutes) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.limiter = limiter;
        this.events = events;
        this.maxFailures = maxFailures;
        this.lockMinutes = lockMinutes;
        // Unknown usernames still pay the BCrypt cost, so response time can't reveal who exists.
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(String rawUsername, String password, String ip) {
        if (!limiter.allow(ip)) {
            events.warn(Actions.RATE_LIMITED, "endpoint", "login", "src_ip", ip);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many sign-in attempts. Wait a minute, then try again.");
        }

        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        Optional<UserRow> found = USERNAME.matcher(username).matches()
                ? users.findByUsername(username)
                : Optional.empty();

        if (found.isEmpty()) {
            encoder.matches(password, dummyHash);
            events.warn(Actions.AUTH_LOGIN_FAILED, "username", username, "reason", "unknown_user", "src_ip", ip);
            throw invalidCredentials();
        }

        UserRow user = found.get();
        if (user.locked()) {
            events.warn(Actions.AUTH_ACCOUNT_LOCKED, "username", username, "reason", "attempt_while_locked", "src_ip", ip);
            throw locked();
        }

        if (!encoder.matches(password, user.passwordHash())) {
            if (users.recordFailure(user.id(), maxFailures, lockMinutes)) {
                events.warn(Actions.AUTH_ACCOUNT_LOCKED, "username", username, "reason", "too_many_failures", "src_ip", ip);
                throw locked();
            }
            events.warn(Actions.AUTH_LOGIN_FAILED, "username", username, "reason", "bad_password", "src_ip", ip);
            throw invalidCredentials();
        }

        users.recordSuccess(user.id());
        IssuedToken token = sessions.issue(user.id());
        events.info(Actions.AUTH_LOGIN_SUCCESS, "username", username, "role", user.role().name(), "src_ip", ip);
        return new LoginResponse(token.token(), token.expiresAt(),
                new MeResponse(user.id(), user.username(), user.displayName(), List.of(user.role().name())));
    }

    public void logout(AuthenticatedUser user) {
        sessions.revoke(user.sessionId());
        events.info(Actions.AUTH_LOGOUT, "username", user.username());
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                "That username and password don't match.");
    }

    private static BusinessException locked() {
        return new BusinessException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "This account is locked after too many failed attempts. Try again later.");
    }
}

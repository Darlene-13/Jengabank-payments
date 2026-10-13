package com.jengabank.payments.auth;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.ClientIpResolver;
import com.jengabank.payments.logging.RequestLogFilter;
import com.jengabank.payments.logging.SecurityEventLogger;

/**
 * Reads "Authorization: Bearer <token>". Valid: the caller is authenticated with ROLE_<role>.
 * Missing or invalid: the request continues anonymous, and SecurityConfig returns 401 for /api/**.
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final SessionService sessions;
    private final SecurityEventLogger events;
    private final ClientIpResolver clientIp;

    public TokenAuthFilter(SessionService sessions, SecurityEventLogger events, ClientIpResolver clientIp) {
        this.sessions = sessions;
        this.events = events;
        this.clientIp = clientIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            authenticate(header, request);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String header, HttpServletRequest request) {
        String ip = clientIp.resolve(request);
        String path = request.getRequestURI();

        if (!header.startsWith("Bearer ")) {
            events.warn(Actions.AUTH_TOKEN_INVALID, "reason", "bad_scheme", "path", path, "src_ip", ip);
            return;
        }
        String token = header.substring(7).trim();
        if (!TOKEN.matcher(token).matches()) {
            events.warn(Actions.AUTH_TOKEN_INVALID, "reason", "malformed", "path", path, "src_ip", ip);
            return;
        }

        sessions.authenticate(token).ifPresentOrElse(user -> {
            var auth = UsernamePasswordAuthenticationToken.authenticated(
                    user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            request.setAttribute(RequestLogFilter.USER_ATTRIBUTE, user.username());
        }, () -> events.warn(Actions.AUTH_TOKEN_INVALID, "reason", "unknown_expired_or_revoked",
                "path", path, "src_ip", ip));
    }
}

package com.jengabank.payments.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Opaque bearer tokens: 32 random bytes, base64url (43 chars).
 * Only the SHA-256 is stored. Logout and expiry are enforced by the database row.
 */
@Service
public class SessionService {

    public record IssuedToken(String token, OffsetDateTime expiresAt) {
        @Override
        public String toString() {
            return "IssuedToken[token=***, expiresAt=" + expiresAt + "]";
        }
    }

    private final JdbcTemplate jdbc;
    private final int ttlMinutes;
    private final SecureRandom random = new SecureRandom();

    public SessionService(JdbcTemplate jdbc, @Value("${jengabank.auth.token-ttl-minutes:30}") int ttlMinutes) {
        this.jdbc = jdbc;
        this.ttlMinutes = ttlMinutes;
    }

    public IssuedToken issue(UUID userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        OffsetDateTime expiresAt = jdbc.queryForObject("""
                INSERT INTO sessions (id, user_id, token_hash, expires_at)
                VALUES (?, ?, ?, now() + make_interval(mins => ?))
                RETURNING expires_at
                """, OffsetDateTime.class, UUID.randomUUID(), userId, sha256(token), ttlMinutes);
        return new IssuedToken(token, expiresAt);
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        return jdbc.query("""
                SELECT s.id AS session_id, u.id, u.username, u.display_name, u.role
                FROM sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now()
                """,
                (rs, n) -> new AuthenticatedUser(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        Role.valueOf(rs.getString("role")),
                        rs.getObject("session_id", UUID.class)),
                sha256(token)).stream().findFirst();
    }

    public void revoke(UUID sessionId) {
        jdbc.update("UPDATE sessions SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

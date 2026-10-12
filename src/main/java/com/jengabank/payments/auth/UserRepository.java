package com.jengabank.payments.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    public record UserRow(UUID id, String username, String displayName, String passwordHash, Role role, boolean locked) {}

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByUsername(String username) {
        return jdbc.query("""
                SELECT id, username, display_name, password_hash, role,
                       COALESCE(locked_until > now(), FALSE) AS locked
                FROM users WHERE username = ?
                """,
                (rs, n) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("username"),
                        rs.getString("display_name"),
                        rs.getString("password_hash"),
                        Role.valueOf(rs.getString("role")),
                        rs.getBoolean("locked")),
                username).stream().findFirst();
    }

    /** Atomically counts a failure. Returns true if this failure just locked the account. */
    public boolean recordFailure(UUID userId, int maxFailures, int lockMinutes) {
        Boolean locked = jdbc.queryForObject("""
                UPDATE users SET
                  failed_login_count = CASE WHEN failed_login_count + 1 >= ? THEN 0 ELSE failed_login_count + 1 END,
                  locked_until       = CASE WHEN failed_login_count + 1 >= ? THEN now() + make_interval(mins => ?)
                                            ELSE locked_until END
                WHERE id = ?
                RETURNING COALESCE(locked_until > now(), FALSE)
                """, Boolean.class, maxFailures, maxFailures, lockMinutes, userId);
        return Boolean.TRUE.equals(locked);
    }

    public void recordSuccess(UUID userId) {
        jdbc.update("UPDATE users SET failed_login_count = 0, locked_until = NULL WHERE id = ?", userId);
    }
}

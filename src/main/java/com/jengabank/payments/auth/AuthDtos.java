package com.jengabank.payments.auth;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + ", password=***]"; // never log the password
        }
    }

    public record MeResponse(UUID id, String username, String displayName, List<String> roles) {
        public static MeResponse of(AuthenticatedUser u) {
            return new MeResponse(u.id(), u.username(), u.displayName(), List.of(u.role().name()));
        }
    }

    public record LoginResponse(String token, OffsetDateTime expiresAt, MeResponse user) {
        @Override
        public String toString() {
            return "LoginResponse[token=***, expiresAt=" + expiresAt + ", user=" + user + "]";
        }
    }
}

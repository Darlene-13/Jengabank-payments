package com.jengabank.payments.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.auth.AuthDtos.LoginRequest;
import com.jengabank.payments.auth.AuthDtos.LoginResponse;
import com.jengabank.payments.auth.AuthDtos.MeResponse;
import com.jengabank.payments.logging.ClientIpResolver;

import com.jengabank.payments.logging.RequestLogFilter;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService auth;
    private final ClientIpResolver clientIp;

    public AuthController(AuthService auth, ClientIpResolver clientIp) {
        this.auth = auth;
        this.clientIp = clientIp;
    }

    @SecurityRequirements
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        LoginResponse response = auth.login(body.username(), body.password(), clientIp.resolve(request));
        request.setAttribute(RequestLogFilter.USER_ATTRIBUTE, response.user().username());
        return response;
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout() {
        auth.logout(CurrentUser.require());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.of(CurrentUser.require());
    }
}

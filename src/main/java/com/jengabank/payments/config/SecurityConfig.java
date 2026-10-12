package com.jengabank.payments.config;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.auth.SessionService;
import com.jengabank.payments.auth.TokenAuthFilter;
import com.jengabank.payments.error.ApiError;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RequestLogFilter;
import com.jengabank.payments.logging.SecurityEventLogger;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger("security");

    private static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private static final String[] PUBLIC_GET = {
            "/healthz", "/readyz", "/openapi.json",
            "/", "/index.html", "/assets/**", "/icons/**", "/favicon.ico",
            "/manifest.webmanifest", "/sw.js", "/workbox-*.js", "/registerSW.js",
            "/login", "/send", "/transfers", "/transfers/*", "/accounts/*", "/staff/**"
    };

    private final ObjectMapper mapper;
    private final SessionService sessions;
    private final SecurityEventLogger events;

    public SecurityConfig(ObjectMapper mapper, SessionService sessions, SecurityEventLogger events) {
        this.mapper = mapper;
        this.sessions = sessions;
        this.events = events;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)          // bearer header, no cookies
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .anonymous(a -> a.authorities("ROLE_ANONYMOUS"))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "camera=(), microphone=(), geolocation=(), payment=()")))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/v1/approvals/**").hasRole("CHECKER")
                .requestMatchers(HttpMethod.POST, "/api/v1/reconciliation/runs").hasRole("CHECKER")
                .requestMatchers("/api/v1/reconciliation/**", "/api/v1/audit/**").hasAnyRole("MAKER", "CHECKER")
                .requestMatchers(HttpMethod.POST, "/api/v1/transfers/*/reversals").hasRole("MAKER")
                .requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasRole("CUSTOMER")
                .requestMatchers("/api/v1/accounts/**").hasRole("CUSTOMER")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                        write(res, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "Sign in to continue."))
                .accessDeniedHandler((req, res, ex) -> {
                    log.warn("access denied", kv("action", Actions.AUTHZ_ACCESS_DENIED),
                            kv("path", req.getRequestURI()), kv("src_ip", req.getRemoteAddr()));
                    write(res, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Your role can't do this.");
                }))
            .addFilterBefore(new TokenAuthFilter(sessions, events), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void write(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(),
                new ApiError(code, message, MDC.get(RequestLogFilter.MDC_REQUEST_ID), List.of()));
    }
}

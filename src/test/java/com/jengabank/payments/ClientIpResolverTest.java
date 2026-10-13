package com.jengabank.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;

import com.jengabank.payments.logging.ClientIpResolver;

/**
 * X-Forwarded-For / X-Real-IP must never override the socket address unless it came
 * through a configured trusted proxy - otherwise any caller could spoof their way
 * around per-IP rate limiting and src_ip attribution in the logs.
 */
class ClientIpResolverTest {

    private HttpServletRequest request(String socketAddress, String forwardedFor, String realIp) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(socketAddress);
        when(req.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        when(req.getHeader("X-Real-IP")).thenReturn(realIp);
        return req;
    }

    @Test
    void noTrustedProxiesConfigured_alwaysUsesSocketAddress() {
        ClientIpResolver resolver = new ClientIpResolver("");
        assertThat(resolver.resolve(request("203.0.113.9", "9.9.9.9", null))).isEqualTo("203.0.113.9");
    }

    @Test
    void untrustedPeer_spoofedHeaderIsIgnored() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.5");
        // Attacker connects directly; peer isn't the trusted proxy, so the header is ignored.
        assertThat(resolver.resolve(request("198.51.100.7", "1.2.3.4", null))).isEqualTo("198.51.100.7");
    }

    @Test
    void trustedExactPeer_usesFirstForwardedForEntry() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.42, 10.0.0.5", null)))
                .isEqualTo("203.0.113.42");
    }

    @Test
    void trustedCidr_usesXRealIpWhenForwardedForAbsent() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.1.2.3", null, "203.0.113.42"))).isEqualTo("203.0.113.42");
    }

    @Test
    void trustedPeer_outsideCidrFallsBackToSocketAddress() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("192.168.1.1", "203.0.113.42", null))).isEqualTo("192.168.1.1");
    }

    @Test
    void trustedPeer_malformedForwardedHeaderFallsBackToSocketAddress() {
        ClientIpResolver resolver = new ClientIpResolver("10.0.0.5");
        String injectionAttempt = "not-an-ip\nauth.login.success fake entry";
        assertThat(resolver.resolve(request("10.0.0.5", injectionAttempt, null))).isEqualTo("10.0.0.5");
    }

    @Test
    void malformedTrustedProxyConfigEntry_isIgnoredNotFatal() {
        ClientIpResolver resolver = new ClientIpResolver("not-an-ip, 10.0.0.5");
        assertThat(resolver.resolve(request("10.0.0.5", "203.0.113.42", null))).isEqualTo("203.0.113.42");
    }
}

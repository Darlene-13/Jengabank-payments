package com.jengabank.payments.logging;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The address every security decision (rate limiting, lockout, event logs) is keyed on.
 *
 * By default we trust nothing but the TCP peer address: request.getRemoteAddr().
 * X-Forwarded-For / X-Real-IP are only read when that peer address is a configured
 * trusted proxy (jengabank.security.trusted-proxies) - otherwise anyone could put
 * "X-Forwarded-For: 1.2.3.4" on a direct request and reset their own rate limit.
 * Once a hop is trusted, we take the left-most entry of X-Forwarded-For (the
 * original client, per RFC 7239 convention) or X-Real-IP, and only if it looks
 * like an IP literal - never raw header text - so it can't be used for log injection.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger("security");

    // IPv4 dotted-quad or IPv6 hex/colon form. Deliberately loose (no octet-range check):
    // this is a log-injection guard, not a full IP validator.
    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final List<String> trustedProxies;

    public ClientIpResolver(@Value("${jengabank.security.trusted-proxies:}") String trustedProxiesConfig) {
        this.trustedProxies = new ArrayList<>();
        for (String entry : trustedProxiesConfig.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!isWellFormed(trimmed)) {
                log.warn("ignoring malformed trusted-proxy entry: {}", trimmed);
                continue;
            }
            trustedProxies.add(trimmed);
        }
    }

    public String resolve(HttpServletRequest request) {
        String socketAddress = request.getRemoteAddr();
        if (!isTrustedProxy(socketAddress)) {
            return socketAddress;
        }

        String forwarded = firstToken(request.getHeader("X-Forwarded-For"));
        if (forwarded != null && IP_LITERAL.matcher(forwarded).matches()) {
            return forwarded;
        }
        String realIp = header(request, "X-Real-IP");
        if (realIp != null && IP_LITERAL.matcher(realIp).matches()) {
            return realIp;
        }
        return socketAddress;
    }

    private boolean isTrustedProxy(String socketAddress) {
        for (String entry : trustedProxies) {
            if (entry.indexOf('/') >= 0 ? cidrMatches(entry, socketAddress) : entry.equals(socketAddress)) {
                return true;
            }
        }
        return false;
    }

    private static String firstToken(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        return headerValue.split(",", 2)[0].trim();
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null ? null : value.trim();
    }

    private static boolean isWellFormed(String entry) {
        String ip = entry.indexOf('/') >= 0 ? entry.substring(0, entry.indexOf('/')) : entry;
        return IP_LITERAL.matcher(ip).matches();
    }

    /** IPv4 CIDR only. An IPv6 or bare-IP trusted-proxy entry is matched by exact string equality. */
    private static boolean cidrMatches(String cidr, String ip) {
        int slash = cidr.indexOf('/');
        try {
            int prefix = Integer.parseInt(cidr.substring(slash + 1));
            if (prefix < 0 || prefix > 32) {
                return false;
            }
            int network = ipv4ToInt(cidr.substring(0, slash));
            int addr = ipv4ToInt(ip);
            int mask = prefix == 0 ? 0 : (int) (0xFFFFFFFFL << (32 - prefix));
            return (network & mask) == (addr & mask);
        } catch (RuntimeException e) {
            return false; // not IPv4, or not well-formed - no match, fail closed
        }
    }

    private static int ipv4ToInt(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("not IPv4: " + ip);
        }
        int result = 0;
        for (String octet : octets) {
            int value = Integer.parseInt(octet);
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException("bad octet: " + octet);
            }
            result = (result << 8) | value;
        }
        return result;
    }
}

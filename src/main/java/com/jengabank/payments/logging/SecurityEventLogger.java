package com.jengabank.payments.logging;

import static net.logstash.logback.argument.StructuredArguments.entries;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * One JSON line per security decision. Usage:
 *   events.warn(Actions.AUTH_LOGIN_FAILED, "username", name, "src_ip", ip);
 * Never pass passwords, tokens or full account details.
 * The most severe action of a request (latest wins on a tie) also goes on that request's
 * http line, so a reader of RequestLogFilter's line sees what happened without a join.
 * Events raised inside an authenticated request get that request's username when the caller
 * didn't pass one, so every event can be grouped by user without a requestId join.
 */
@Component
public class SecurityEventLogger {

    private static final Logger log = LoggerFactory.getLogger("security");
    private static final int MAX_VALUE_LENGTH = 200;
    private static final String SEVERITY_ATTRIBUTE = "jb.action.severity";

    public void info(String action, Object... keyValues) {
        log.info(action, entries(fields(action, keyValues)));
        remember(action, 0);
    }

    public void warn(String action, Object... keyValues) {
        log.warn(action, entries(fields(action, keyValues)));
        remember(action, 1);
    }

    public void error(String action, Object... keyValues) {
        log.error(action, entries(fields(action, keyValues)));
        remember(action, 2);
    }

    /** Outside a request (scheduled jobs) there is no http line to annotate. */
    private static void remember(String action, int severity) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return;
        }
        int scope = RequestAttributes.SCOPE_REQUEST;
        Object current = request.getAttribute(SEVERITY_ATTRIBUTE, scope);
        if (current == null || severity >= (Integer) current) {
            request.setAttribute(SEVERITY_ATTRIBUTE, severity, scope);
            request.setAttribute(RequestLogFilter.ACTION_ATTRIBUTE, action, scope);
        }
    }

    private static Map<String, Object> fields(String action, Object[] keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action", action);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value instanceof String s && s.length() > MAX_VALUE_LENGTH) {
                value = s.substring(0, MAX_VALUE_LENGTH); // attacker-supplied text stays bounded
            }
            map.put(String.valueOf(keyValues[i]), value);
        }
        if (!map.containsKey("username")) {
            Object user = requestUser();
            if (user != null) {
                map.put("username", user);
            }
        }
        return map;
    }

    private static Object requestUser() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null ? null
                : request.getAttribute(RequestLogFilter.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }
}

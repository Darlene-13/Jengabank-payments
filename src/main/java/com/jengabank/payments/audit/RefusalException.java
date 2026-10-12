package com.jengabank.payments.audit;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.jengabank.payments.error.BusinessException;

/**
 * A refused request that must still leave an audit record. Throwing rolls the
 * transaction back; {@link RefusalAuditAdvice} then writes the record once the
 * request's database connection has been released.
 */
public class RefusalException extends BusinessException {

    private final String actor;
    private final String action;
    private final String target;
    private final Map<String, Object> details;

    public RefusalException(HttpStatus status, String code, String message, List<String> reasons,
                            String actor, String action, String target, Map<String, Object> details) {
        super(status, code, message, reasons);
        this.actor = actor;
        this.action = action;
        this.target = target;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public String actor() { return actor; }
    public String action() { return action; }
    public String target() { return target; }
    public Map<String, Object> details() { return details; }
}

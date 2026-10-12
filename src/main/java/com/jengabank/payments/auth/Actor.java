package com.jengabank.payments.auth;

/**
 * The acting identity for one request, as seen by the transaction-control layer.
 * Dev 2 will populate it from a verified token; until then it comes from the
 * {@code X-User-Id} / {@code X-User-Role} headers (deny-by-default roles).
 */
public record Actor(String id, Role role) {

    public static Actor anonymous() {
        return new Actor("anonymous", Role.VIEWER);
    }

    public boolean is(String otherId) {
        return otherId != null && otherId.equals(id);
    }
}

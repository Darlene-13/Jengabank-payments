package com.jengabank.payments.logging;

/** Every log action name. Use these constants, never raw strings. */
public final class Actions {

    private Actions() {}

    public static final String HTTP_REQUEST = "http.request";
    public static final String SERVER_ERROR = "server.error";
    public static final String DB_GUARD_TRIGGERED = "db.guard_triggered";

    public static final String AUTH_LOGIN_SUCCESS = "auth.login.success";
    public static final String AUTH_LOGIN_FAILED = "auth.login.failed";
    public static final String AUTH_ACCOUNT_LOCKED = "auth.account.locked";
    public static final String AUTH_TOKEN_INVALID = "auth.token.invalid";
    public static final String AUTH_LOGOUT = "auth.logout";
    public static final String AUTHZ_ACCESS_DENIED = "authz.access.denied";
    public static final String ACCOUNT_VIEWED = "account.viewed";

    public static final String TRANSFER_CREATED = "transfer.created";
    public static final String TRANSFER_COMPLETED = "transfer.completed";
    public static final String TRANSFER_PENDING_APPROVAL = "transfer.pending_approval";
    public static final String TRANSFER_BLOCKED = "transfer.blocked";
    public static final String TRANSFER_INSUFFICIENT_FUNDS = "transfer.insufficient_funds";
    public static final String TRANSFER_INVALID_AMOUNT = "transfer.invalid_amount";

    public static final String FRAUD_ALLOW = "fraud.allow";
    public static final String FRAUD_REVIEW = "fraud.review";
    public static final String FRAUD_BLOCK = "fraud.block";

    public static final String LIMIT_EXCEEDED = "limit.exceeded";
    public static final String IDEMPOTENCY_REPLAYED = "idempotency.replayed";
    public static final String IDEMPOTENCY_CONFLICT = "idempotency.conflict";

    public static final String APPROVAL_APPROVED = "approval.approved";
    public static final String APPROVAL_REJECTED = "approval.rejected";
    public static final String APPROVAL_SELF_APPROVAL_DENIED = "approval.self_approval_denied";

    public static final String REVERSAL_CREATED = "reversal.created";
    public static final String REVERSAL_DUPLICATE_DENIED = "reversal.duplicate_denied";
    public static final String REVERSAL_DENIED = "reversal.denied";
    public static final String REVERSAL_PENDING_APPROVAL = "reversal.pending_approval";

    public static final String RECONCILIATION_PASSED = "reconciliation.passed";
    public static final String RECONCILIATION_FAILED = "reconciliation.failed";

    public static final String AUDIT_CHAIN_OK = "audit.chain.ok";
    public static final String AUDIT_CHAIN_BROKEN = "audit.chain.broken";
    public static final String REQUEST_INVALID = "request.invalid";
    public static final String RATE_LIMITED = "rate.limited";
}

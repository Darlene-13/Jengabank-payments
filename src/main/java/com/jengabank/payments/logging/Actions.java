package com.jengabank.payments.logging;

/**
 * Canonical security-event action names.
 *
 * <p>These strings are the contract with the AI defence agents and the judges:
 * if an action is not emitted with one of these values it did not happen as
 * far as the competition is concerned.
 *
 * <p>Values match the JengaSec 2026 Blue App brief (page 5) exactly.
 */
public final class Actions {

    private Actions() {
    }

    // http
    public static final String HTTP_REQUEST = "http.request";
    public static final String INTERNAL_ERROR = "internal.error";

    // identity / authorization
    public static final String AUTH_LOGIN_SUCCESS   = "auth.login.success";
    public static final String AUTH_LOGIN_FAILED    = "auth.login.failed";
    public static final String AUTH_ACCOUNT_LOCKED  = "auth.account.locked";
    public static final String AUTH_TOKEN_INVALID   = "auth.token.invalid";
    public static final String AUTHZ_ACCESS_DENIED  = "authz.access.denied";
    public static final String ACCOUNT_VIEWED       = "account.viewed";

    // Back-compat alias: code that used AUTH_FORBIDDEN now emits the brief name.
    public static final String AUTH_FORBIDDEN = AUTHZ_ACCESS_DENIED;

    // transfer + limits
    public static final String TRANSFER_CREATE              = "transfer.created";
    public static final String TRANSFER_COMPLETED           = "transfer.completed";
    public static final String TRANSFER_PENDING_APPROVAL    = "transfer.pending_approval";
    public static final String TRANSFER_BLOCKED             = "transfer.blocked";
    public static final String TRANSFER_INSUFFICIENT_FUNDS  = "transfer.insufficient_funds";
    public static final String TRANSFER_INVALID_AMOUNT      = "transfer.invalid_amount";
    public static final String LIMIT_EXCEEDED               = "limit.exceeded";

    // Aliases for internal cases that map to the same brief action.
    public static final String TRANSFER_SAME_ACCOUNT          = TRANSFER_INVALID_AMOUNT;
    public static final String TRANSFER_UNSUPPORTED_CURRENCY  = TRANSFER_INVALID_AMOUNT;
    public static final String TRANSFER_NOT_FOUND             = "request.invalid";

    // fraud
    public static final String FRAUD_ALLOW  = "fraud.allow";
    public static final String FRAUD_REVIEW = "fraud.review";
    public static final String FRAUD_BLOCK  = "fraud.block";

    // maker-checker
    public static final String APPROVAL_APPROVE             = "approval.approved";
    public static final String APPROVAL_REJECT              = "approval.rejected";
    public static final String APPROVAL_SELF_APPROVAL       = "approval.self_approval_denied";
    public static final String APPROVAL_INVALID_STATE       = "approval.invalid_state";
    public static final String APPROVAL_INSUFFICIENT_FUNDS  = "approval.insufficient_funds";

    // idempotency
    public static final String IDEMPOTENCY_MISMATCH = "idempotency.conflict";
    public static final String IDEMPOTENCY_REPLAY   = "idempotency.replayed";

    // reversal
    public static final String REVERSAL_CREATED   = "reversal.created";
    public static final String REVERSAL_DUPLICATE = "reversal.duplicate_denied";

    // reconciliation + audit
    public static final String RECONCILIATION_PASS = "reconciliation.passed";
    public static final String RECONCILIATION_FAIL = "reconciliation.failed";
    public static final String AUDIT_WRITE         = "audit.write";
    public static final String AUDIT_CHAIN_OK      = "audit.chain.ok";
    public static final String AUDIT_CHAIN_BROKEN  = "audit.chain.broken";

    // request / rate
    public static final String REQUEST_INVALID = "request.invalid";
    public static final String RATE_LIMITED    = "rate.limited";

    // NEW — emitted by GlobalExceptionHandler when a DB guard rejects an operation.
    public static final String DB_GUARD_TRIGGERED = "db.guard_triggered";
}
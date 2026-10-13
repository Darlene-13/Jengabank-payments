package com.jengabank.payments.transfer;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jengabank.payments.audit.AuditWriter;
import com.jengabank.payments.audit.RefusalException;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.common.Cursors;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.error.ApiError;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.ledger.LedgerService.Direction;
import com.jengabank.payments.ledger.LedgerService.Line;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RateLimiter;
import com.jengabank.payments.logging.RequestLogFilter;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.FraudEngine.Context;
import com.jengabank.payments.transfer.FraudEngine.Decision;
import com.jengabank.payments.transfer.FraudEngine.Result;
import com.jengabank.payments.transfer.TransferDtos.CreateTransferRequest;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;
import com.jengabank.payments.transfer.TransferRepository.AccountRef;

/**
 * One transfer = one transaction:
 *   idempotency key claimed -> customer row locked -> accounts checked and locked in id order
 *   -> limits -> balance -> fraud (synchronous, before insert) -> outcome:
 *      ALLOW and under threshold: journal posted, COMPLETED (201)
 *      REVIEW or at/above threshold: PENDING_APPROVAL, no money moves (202)
 *      BLOCK or over a limit: BLOCKED row recorded, no money moves (422)
 * The database re-checks balance and journal balance at commit.
 */
@Service
public class TransferService {

    private static final Set<String> STATUSES =
            Set.of("COMPLETED", "PENDING_APPROVAL", "REJECTED", "BLOCKED", "REVERSED");

    /** Anti-enumeration: unknown destination numbers allowed per user per window (600s is the limiter's max). */
    private static final int BAD_DESTINATIONS_MAX = 5;
    private static final int BAD_DESTINATIONS_WINDOW_SECONDS = 600;

    private final TransferRepository repo;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditWriter audit;
    private final SecurityEventLogger events;
    private final LimitChecker limits;
    private final FraudEngine fraud;
    private final RateLimiter rateLimiter;
    private final long thresholdMinor;

    public TransferService(TransferRepository repo, LedgerService ledger, IdempotencyService idempotency,
                           AuditWriter audit, SecurityEventLogger events, JdbcTemplate jdbc, RateLimiter rateLimiter,
                           ObjectProvider<LimitChecker> limitChecker, ObjectProvider<FraudEngine> fraudEngine,
                           @Value("${jengabank.approval.threshold-minor:25000000}") long thresholdMinor,
                           @Value("${jengabank.limits.max-per-transfer-minor:100000000}") long maxPerTransfer,
                           @Value("${jengabank.limits.daily-total-minor:200000000}") long dailyTotal,
                           @Value("${jengabank.limits.velocity-max-transfers:5}") int velocityMax,
                           @Value("${jengabank.limits.velocity-window-seconds:60}") int velocityWindow) {
        this.repo = repo;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.thresholdMinor = thresholdMinor;
        this.limits = limitChecker.getIfAvailable(
                () -> new BaselineControls.Limits(jdbc, maxPerTransfer, dailyTotal, velocityMax, velocityWindow));
        this.fraud = fraudEngine.getIfAvailable(() -> new BaselineControls.Fraud(jdbc));
    }

    // ---- Create ----------------------------------------------------------------

    @Transactional
    public ResponseEntity<?> create(AuthenticatedUser me, CreateTransferRequest request, String idempotencyKey) {
        return idempotency.execute(me, idempotencyKey, "POST /api/v1/transfers", request, () -> doCreate(me, request));
    }

    private ResponseEntity<?> doCreate(AuthenticatedUser me, CreateTransferRequest req) {
        long amount = req.amountMinor();
        String reference = clean(req.reference());

        repo.lockUser(me.id());

        AccountRef from = repo.findOwnedAccount(req.fromAccountId(), me.id()).orElseThrow(() -> {
            events.warn(Actions.AUTHZ_ACCESS_DENIED, "username", me.username(), "resource", "account",
                    "account_id", req.fromAccountId(), "reason", "transfer_from_not_owned");
            return BusinessException.notFound();
        });
        if (!"ACTIVE".equals(from.status())) {
            throw refuse(me, req, "ACCOUNT_NOT_ACTIVE", "This account can't send money right now.", "account_not_active");
        }
        AccountRef to = repo.findActiveCustomerAccount(req.toAccountNumber()).orElse(null);
        if (to == null) {
            // Counted in its own transaction, so the count survives this request's rollback.
            if (!rateLimiter.allow("transfer.bad_destination:" + me.id(), BAD_DESTINATIONS_MAX,
                    BAD_DESTINATIONS_WINDOW_SECONDS)) {
                events.warn(Actions.RATE_LIMITED, "username", me.username(), "endpoint", "transfers.destination_lookup");
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Too many attempts with unknown account numbers. Wait 10 minutes and try again.");
            }
            throw refuse(me, req, "INVALID_DESTINATION", "Check the account number. We can't send money to it.",
                    "invalid_destination");
        }
        if (to.id().equals(from.id())) {
            throw refuse(me, req, "SAME_ACCOUNT", "You can't send money to the same account.", "same_account");
        }

        ledger.lockAccounts(from.id(), to.id());
        events.info(Actions.TRANSFER_CREATED, "username", me.username(), "from_account_id", from.id(),
                "to_account_id", to.id(), "amount_minor", amount);

        // 1. Limits: a breach is recorded as BLOCKED, not silently dropped.
        List<String> limitViolations = limits.violations(me.id(), from.id(), amount);
        if (!limitViolations.isEmpty()) {
            events.warn(Actions.LIMIT_EXCEEDED, "username", me.username(), "amount_minor", amount,
                    "reasons", limitViolations);
            return blocked(me, from, to, amount, reference, null, limitViolations, "This transfer is over a limit.");
        }

        // 2. Balance: checked under the account lock.
        if (ledger.balance(from.id()) < amount) {
            events.warn(Actions.TRANSFER_INSUFFICIENT_FUNDS, "username", me.username(),
                    "from_account_id", from.id(), "amount_minor", amount);
            // RefusalException: the transaction rolls back, then RefusalAuditAdvice still writes the audit record.
            throw new RefusalException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "There isn't enough money in this account.", List.of(),
                    me.username(), Actions.TRANSFER_INSUFFICIENT_FUNDS, "account/" + from.id(),
                    Map.of("to_account", to.accountNumber(), "amount_minor", amount));
        }

        // 3. Fraud: synchronous, before the row is written; decision is immutable afterwards.
        Result decision = fraud.evaluate(new Context(me.id(), from.id(), to.id(), amount));
        logFraud(me, amount, decision);
        if (decision.decision() == Decision.BLOCK) {
            return blocked(me, from, to, amount, reference, Decision.BLOCK.name(), decision.reasons(),
                    "This transfer was stopped by our fraud checks.");
        }

        List<String> reasons = new ArrayList<>(decision.reasons());
        boolean overThreshold = amount >= thresholdMinor;
        if (overThreshold) {
            reasons.add("Amount is " + BaselineControls.kes(thresholdMinor) + " or more, so a second person must approve it");
        }

        UUID transferId = UUID.randomUUID();

        // 4a. Maker-checker: nothing moves until a CHECKER who isn't the creator approves.
        if (decision.decision() == Decision.REVIEW || overThreshold) {
            repo.insert(transferId, from.id(), to.id(), amount, reference, "PENDING_APPROVAL",
                    decision.decision().name(), reasons, null, me.id());
            audit.write(me.username(), Actions.TRANSFER_PENDING_APPROVAL, "transfer/" + transferId,
                    details(transferId, from, to, amount, decision.decision().name(), reasons));
            events.info(Actions.TRANSFER_PENDING_APPROVAL, "username", me.username(), "transfer_id", transferId,
                    "amount_minor", amount, "reasons", reasons);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(repo.find(transferId).orElseThrow());
        }

        // 4b. Allowed: post the double entry and record the transfer, atomically.
        UUID journalId = ledger.post("TRANSFER", reference.isEmpty() ? "Transfer" : reference, null, me.id(), List.of(
                new Line(from.id(), Direction.DEBIT, amount),
                new Line(to.id(), Direction.CREDIT, amount)));
        repo.insert(transferId, from.id(), to.id(), amount, reference, "COMPLETED",
                decision.decision().name(), reasons, journalId, me.id());
        audit.write(me.username(), Actions.TRANSFER_COMPLETED, "transfer/" + transferId,
                details(transferId, from, to, amount, decision.decision().name(), reasons));
        events.info(Actions.TRANSFER_COMPLETED, "username", me.username(), "transfer_id", transferId,
                "journal_id", journalId, "amount_minor", amount);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.find(transferId).orElseThrow());
    }

    /** Records the blocked attempt (it counts toward velocity and fraud history) and returns 422 with reasons. */
    private ResponseEntity<?> blocked(AuthenticatedUser me, AccountRef from, AccountRef to, long amount, String reference,
                                      String fraudDecision, List<String> reasons, String message) {
        UUID transferId = UUID.randomUUID();
        repo.insert(transferId, from.id(), to.id(), amount, reference, "BLOCKED", fraudDecision, reasons, null, me.id());
        audit.write(me.username(), Actions.TRANSFER_BLOCKED, "transfer/" + transferId,
                details(transferId, from, to, amount, fraudDecision, reasons));
        events.warn(Actions.TRANSFER_BLOCKED, "username", me.username(), "transfer_id", transferId,
                "amount_minor", amount, "reasons", reasons);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("TRANSFER_BLOCKED", message, MDC.get(RequestLogFilter.MDC_REQUEST_ID), reasons));
    }

    // ---- Read (#5: staff see all, customers see their own) ---------------------

    @Transactional(readOnly = true)
    public TransferDto get(AuthenticatedUser me, UUID transferId) {
        return repo.findVisible(transferId, me.id(), me.isStaff()).orElseThrow(() -> {
            events.warn(Actions.AUTHZ_ACCESS_DENIED, "username", me.username(), "resource", "transfer",
                    "transfer_id", transferId, "reason", "not_found_or_not_visible");
            return BusinessException.notFound();
        });
    }

    @Transactional(readOnly = true)
    public Page<TransferDto> list(AuthenticatedUser me, String status, String cursor, Integer limit) {
        if (status != null && !STATUSES.contains(status)) {
            throw BusinessException.badRequest("INVALID_PARAMETER", "Unknown status filter.");
        }
        int lim = Cursors.limit(limit);
        OffsetDateTime beforeTs = null;
        UUID beforeId = null;
        String raw = Cursors.decode(cursor);
        if (raw != null) {
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw Cursors.invalid();
            }
            try {
                beforeTs = OffsetDateTime.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (DateTimeParseException | IllegalArgumentException e) {
                throw Cursors.invalid();
            }
        }
        return Page.of(repo.list(me.id(), me.isStaff(), status, beforeTs, beforeId, lim + 1), lim,
                t -> t.createdAt() + "|" + t.id());
    }

    // ---- Helpers ---------------------------------------------------------------

    private void logFraud(AuthenticatedUser me, long amount, Result decision) {
        String action = switch (decision.decision()) {
            case ALLOW -> Actions.FRAUD_ALLOW;
            case REVIEW -> Actions.FRAUD_REVIEW;
            case BLOCK -> Actions.FRAUD_BLOCK;
        };
        if (decision.decision() == Decision.ALLOW) {
            events.info(action, "username", me.username(), "amount_minor", amount);
        } else {
            events.warn(action, "username", me.username(), "amount_minor", amount, "reasons", decision.reasons());
        }
    }

    private static Map<String, Object> details(UUID transferId, AccountRef from, AccountRef to, long amount,
                                               String fraudDecision, List<String> reasons) {
        Map<String, Object> d = new LinkedHashMap<>(); // Map.of would reject a null fraud decision
        d.put("transfer_id", transferId.toString());
        d.put("from_account", from.accountNumber());
        d.put("to_account", to.accountNumber());
        d.put("amount_minor", amount);
        d.put("fraud_decision", fraudDecision);
        d.put("reasons", reasons);
        return d;
    }

    /** A 422 that still leaves an audit record: RefusalAuditAdvice writes it after the rollback. */
    private RefusalException refuse(AuthenticatedUser me, CreateTransferRequest req, String code, String message,
                                    String reason) {
        events.warn(Actions.REQUEST_INVALID, "username", me.username(), "endpoint", "transfers.create", "reason", reason);
        return new RefusalException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, List.of(),
                me.username(), Actions.REQUEST_INVALID, "account/" + req.fromAccountId(),
                Map.of("to_account", req.toAccountNumber(), "amount_minor", req.amountMinor(), "reason", reason));
    }

    /** Removes control characters (log and display safety); the database caps length at 140. */
    private static String clean(String reference) {
        if (reference == null) {
            return "";
        }
        return reference.replaceAll("\\p{Cntrl}", "").trim();
    }
}

package com.jengabank.payments.transfer;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jengabank.payments.audit.AuditService;
import com.jengabank.payments.audit.RefusalException;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.common.Cursors;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.config.JengaProperties;
import com.jengabank.payments.error.ApiError;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudDecision;
import com.jengabank.payments.fraud.FraudEngine;
import com.jengabank.payments.fraud.FraudVerdict;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.ledger.LedgerAccount;
import com.jengabank.payments.ledger.LedgerRepository;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.limits.LimitService;
import com.jengabank.payments.limits.LimitViolation;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RequestLogFilter;
import com.jengabank.payments.logging.SecurityEventLogger;
import com.jengabank.payments.transfer.TransferDtos.CreateTransferRequest;
import com.jengabank.payments.transfer.TransferDtos.TransferDto;

import org.slf4j.MDC;

/**
 * Creating a transfer, in order, all inside one database transaction:
 *   idempotency -> ownership -> account locks -> funds -> limits -> fraud -> approval threshold -> persist.
 * Refusals throw RefusalException: everything rolls back, then the audit record is written.
 * A fraud BLOCK is the exception: the attempt is stored as evidence and answered with 422.
 */
@Service
public class TransferService {

    private static final String PATH = "/api/v1/transfers";

    private final TransferRepository transfers;
    private final LedgerRepository accounts;
    private final LedgerService ledger;
    private final LimitService limits;
    private final FraudEngine fraud;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final SecurityEventLogger events;
    private final JengaProperties properties;
    private final Clock clock;

    public TransferService(TransferRepository transfers, LedgerRepository accounts, LedgerService ledger,
                           LimitService limits, FraudEngine fraud, IdempotencyService idempotency,
                           AuditService audit, SecurityEventLogger events, JengaProperties properties, Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.ledger = ledger;
        this.limits = limits;
        this.fraud = fraud;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ResponseEntity<Object> create(AuthenticatedUser me, CreateTransferRequest req, String idempotencyKey) {
        IdempotencyService.Start idem = idempotency.begin(me, idempotencyKey, "POST", PATH, req);
        if (idem.isReplay()) {
            return idem.replay();
        }
        transfers.lockUser(me.id());

        // Ownership: the source must be one of the caller's accounts. Not yours looks like not found.
        LedgerAccount from = accounts.findById(req.fromAccountId())
                .filter(a -> me.id().equals(a.ownerId()))
                .orElseThrow(() -> {
                    events.warn(Actions.AUTHZ_ACCESS_DENIED, "username", me.username(), "resource", "account",
                            "account_id", req.fromAccountId(), "reason", "not_found_or_not_owner");
                    return BusinessException.notFound();
                });
        LedgerAccount to = accounts.findByNumber(req.toAccountNumber())
                .filter(LedgerAccount::isCustomer)
                .orElseThrow(() -> refuse(me, req, HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND",
                        "We couldn't find an account with that number.", Actions.REQUEST_INVALID, "unknown_recipient"));
        if (from.id().equals(to.id())) {
            throw refuse(me, req, HttpStatus.UNPROCESSABLE_ENTITY, "SAME_ACCOUNT",
                    "You can't send money to the same account.", Actions.TRANSFER_INVALID_AMOUNT, "same_account");
        }
        if (!accounts.lock(from.id(), to.id()).stream().allMatch(LedgerAccount::isActive)) {
            throw refuse(me, req, HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_ACTIVE",
                    "One of these accounts can't send or receive money right now.", Actions.REQUEST_INVALID,
                    "account_not_active");
        }

        long amount = req.amountMinor();
        long balance = accounts.balance(from.id());
        if (balance < amount) {
            throw refuse(me, req, HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
                    "There isn't enough money in this account.", Actions.TRANSFER_INSUFFICIENT_FUNDS,
                    "insufficient_funds");
        }

        LimitService.Usage usage = limits.usage(me.id());
        List<LimitViolation> violations = limits.check(usage, amount);
        if (!violations.isEmpty()) {
            List<String> reasons = violations.stream().map(LimitViolation::message).toList();
            Map<String, Object> details = details(req);
            details.put("violations", violations.stream().map(LimitViolation::rule).toList());
            events.warn(Actions.LIMIT_EXCEEDED, "username", me.username(), "amount_minor", amount,
                    "violations", details.get("violations"), "last24h_minor", usage.last24hMinor());
            throw new RefusalException(HttpStatus.UNPROCESSABLE_ENTITY, "LIMIT_EXCEEDED",
                    "This transfer is over your limit.", reasons,
                    me.username(), Actions.LIMIT_EXCEEDED, "account:" + from.accountNumber(), details);
        }

        FraudVerdict verdict = fraud.evaluate(new FraudContext(
                amount,
                ZonedDateTime.now(clock.withZone(ZoneId.of(properties.fraud().zone()))).getHour(),
                usage.last24hMinor(),
                !transfers.hasPaidBefore(me.id(), to.id()),
                transfers.blockedInLast24h(me.id()),
                properties.limits().maxPerTransferMinor(),
                properties.limits().dailyTotalMinor()));

        TransferStatus status = switch (verdict.decision()) {
            case BLOCK -> TransferStatus.BLOCKED;
            case REVIEW -> TransferStatus.PENDING_APPROVAL;
            case ALLOW -> amount >= properties.approval().thresholdMinor()
                    ? TransferStatus.PENDING_APPROVAL
                    : TransferStatus.COMPLETED;
        };

        UUID journalId = status == TransferStatus.COMPLETED
                ? ledger.postTransfer(from.id(), to.id(), amount, description(req), me.id())
                : null;
        UUID id = transfers.insert(from.id(), to.id(), amount, req.reference().trim(), status,
                verdict.decision(), verdict.reasons(), journalId, me.id());
        Transfer transfer = transfers.find(id).orElseThrow();

        Map<String, Object> details = details(req);
        details.put("transfer_id", id);
        details.put("status", status.name());
        details.put("fraud_decision", verdict.decision().name());
        details.put("fraud_reasons", verdict.reasons());
        audit.record(me.username(), actionFor(status), "transfer:" + id, details);
        events.info(fraudAction(verdict.decision()), "username", me.username(), "transfer_id", id,
                "reasons", verdict.reasons());
        events.info(Actions.TRANSFER_CREATED, "username", me.username(), "transfer_id", id,
                "amount_minor", amount, "status", status.name());
        if (status == TransferStatus.BLOCKED) {
            events.warn(Actions.TRANSFER_BLOCKED, "username", me.username(), "transfer_id", id,
                    "reasons", verdict.reasons());
            ApiError blocked = new ApiError("FRAUD_BLOCKED", "This transfer was stopped by our fraud checks.",
                    MDC.get(RequestLogFilter.MDC_REQUEST_ID), verdict.reasons());
            return idempotency.complete(idem, HttpStatus.UNPROCESSABLE_ENTITY, blocked);
        }
        events.info(actionFor(status), "username", me.username(), "transfer_id", id, "amount_minor", amount);

        HttpStatus http = status == TransferStatus.COMPLETED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return idempotency.complete(idem, http, TransferDto.of(transfer));
    }

    public Page<TransferDto> list(AuthenticatedUser me, String statusFilter, String cursor, Integer limit) {
        int lim = Cursors.limit(limit);
        TransferStatus status = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            try {
                status = TransferStatus.valueOf(statusFilter);
            } catch (IllegalArgumentException e) {
                throw BusinessException.badRequest("INVALID_PARAMETER", "Unknown transfer status.");
            }
        }
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
        UUID visibleTo = me.isStaff() ? null : me.id();
        Page<Transfer> page = Page.of(transfers.page(visibleTo, status, beforeTs, beforeId, lim + 1), lim,
                t -> t.createdAt() + "|" + t.id());
        return new Page<>(page.items().stream().map(TransferDto::of).toList(), page.nextCursor());
    }

    public TransferDto get(AuthenticatedUser me, UUID id) {
        return transfers.find(id)
                .filter(t -> me.isStaff() || t.visibleTo(me.id()))
                .map(TransferDto::of)
                .orElseThrow(() -> {
                    events.warn(Actions.AUTHZ_ACCESS_DENIED, "username", me.username(), "resource", "transfer",
                            "transfer_id", id, "reason", "not_found_or_not_visible");
                    return BusinessException.notFound();
                });
    }

    private RefusalException refuse(AuthenticatedUser me, CreateTransferRequest req, HttpStatus http, String code,
                                    String message, String action, String reason) {
        Map<String, Object> details = details(req);
        details.put("reason", reason);
        events.warn(action, "username", me.username(), "reason", reason, "amount_minor", req.amountMinor());
        return new RefusalException(http, code, message, List.of(),
                me.username(), action, "account:" + req.fromAccountId(), details);
    }

    private static Map<String, Object> details(CreateTransferRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from_account_id", req.fromAccountId());
        details.put("to_account_number", req.toAccountNumber());
        details.put("amount_minor", req.amountMinor());
        return details;
    }

    private static String description(CreateTransferRequest req) {
        String reference = req.reference().trim();
        return reference.isEmpty() ? "Transfer to " + req.toAccountNumber() : reference;
    }

    private static String actionFor(TransferStatus status) {
        return switch (status) {
            case COMPLETED -> Actions.TRANSFER_COMPLETED;
            case PENDING_APPROVAL -> Actions.TRANSFER_PENDING_APPROVAL;
            case BLOCKED -> Actions.TRANSFER_BLOCKED;
            default -> Actions.TRANSFER_CREATED;
        };
    }

    private static String fraudAction(FraudDecision decision) {
        return switch (decision) {
            case ALLOW -> Actions.FRAUD_ALLOW;
            case REVIEW -> Actions.FRAUD_REVIEW;
            case BLOCK -> Actions.FRAUD_BLOCK;
        };
    }
}

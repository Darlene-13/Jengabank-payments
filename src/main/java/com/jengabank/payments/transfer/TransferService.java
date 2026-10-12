package com.jengabank.payments.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jengabank.payments.account.Account;
import com.jengabank.payments.account.AccountRepository;
import com.jengabank.payments.audit.AuditService;
import com.jengabank.payments.auth.Actor;
import com.jengabank.payments.config.AppProperties;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.fraud.FraudContext;
import com.jengabank.payments.fraud.FraudDecision;
import com.jengabank.payments.fraud.FraudEngine;
import com.jengabank.payments.fraud.FraudVerdict;
import com.jengabank.payments.idempotency.IdempotencyRecord;
import com.jengabank.payments.idempotency.IdempotencyService;
import com.jengabank.payments.limits.LimitService;
import com.jengabank.payments.limits.LimitViolation;
import com.jengabank.payments.limits.WindowTotals;
import com.jengabank.payments.ledger.LedgerService;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LimitService limits;
    private final FraudEngine fraud;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final SecurityEventLogger events;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public TransferService(AccountRepository accounts,
                           TransferRepository transfers,
                           LimitService limits,
                           FraudEngine fraud,
                           LedgerService ledger,
                           IdempotencyService idempotency,
                           AuditService audit,
                           SecurityEventLogger events,
                           AppProperties properties,
                           ObjectMapper mapper,
                           Clock clock) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.limits = limits;
        this.fraud = fraud;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.events = events;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public TransferResponse create(TransferRequest request, Actor maker, String idempotencyKey) {
        // 0. Idempotency-Key required for money-changing POST (team-plan: missing key -> 400).
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            events.emit(Actions.REQUEST_INVALID, 400, maker.id(),
                    Map.of("path", "/transfers", "reason", "missing_idempotency_key"));
            throw new BusinessException(HttpStatus.BAD_REQUEST, Actions.REQUEST_INVALID,
                    "Idempotency-Key header is required for money-changing requests");
        }

        String requestHash = idempotency.hash(request);

        // 1. Idempotency replay / conflict
        Optional<IdempotencyRecord> cached = idempotency.find(idempotencyKey);
        if (cached.isPresent()) {
            IdempotencyRecord record = cached.get();
            if (!record.requestHash().equals(requestHash)) {
                rejectIdempotencyMismatch(idempotencyKey, requestHash, maker);
            }
            events.emit(Actions.IDEMPOTENCY_REPLAY, record.responseStatus(), maker.id(),
                    Map.of("key", idempotencyKey.trim(), "path", "/transfers"));
            return readResponse(record.responseBody());
        }

        String currency = normalizeCurrency(request, maker);
        String sourceAccountNo = trim(request.sourceAccountNo(), "sourceAccountNo");
        String targetAccountNo = trim(request.targetAccountNo(), "targetAccountNo");
        long amount = request.amountMinor();

        if (sourceAccountNo.equals(targetAccountNo)) {
            events.emit(Actions.TRANSFER_SAME_ACCOUNT, 422, maker.id(),
                    Map.of("path", "/transfers", "account", sourceAccountNo, "amount_minor", amount));
            audit.record(maker.id(), Actions.TRANSFER_SAME_ACCOUNT, sourceAccountNo,
                    Map.of("amount_minor", amount));
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, Actions.TRANSFER_SAME_ACCOUNT,
                    "Source and target account must differ");
        }

        // 2. Lock accounts, check funds.
        List<Account> locked = accounts.lockPair(sourceAccountNo, targetAccountNo);
        Account source = locked.stream().filter(a -> a.accountNo().equals(sourceAccountNo)).findFirst()
                .orElseThrow(() -> notFound(sourceAccountNo, maker));
        Account target = locked.stream().filter(a -> a.accountNo().equals(targetAccountNo)).findFirst()
                .orElseThrow(() -> notFound(targetAccountNo, maker));

        long balance = accounts.balanceOf(source.accountNo());
        if (balance < amount) {
            events.emit(Actions.TRANSFER_INSUFFICIENT_FUNDS, 422, maker.id(),
                    Map.of("path", "/transfers", "account", source.accountNo(),
                            "balance_minor", balance, "amount_minor", amount));
            audit.record(maker.id(), Actions.TRANSFER_INSUFFICIENT_FUNDS, source.accountNo(),
                    Map.of("balance_minor", balance, "amount_minor", amount));
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, Actions.TRANSFER_INSUFFICIENT_FUNDS,
                    "Insufficient funds: balance " + balance + " < " + amount);
        }

        // 3. Limits.
        Instant now = Instant.now(clock);
        WindowTotals totals = limits.totals(source.ownerId(), now);
        List<LimitViolation> violations = limits.check(totals, amount);
        if (!violations.isEmpty()) {
            List<String> descriptions = violations.stream().map(LimitViolation::message).toList();
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("path", "/transfers");
            extra.put("owner", source.ownerId());
            extra.put("account", source.accountNo());
            extra.put("amount_minor", amount);
            extra.put("violations", descriptions);
            extra.put("hourly_total_minor", totals.hourlyMinor());
            extra.put("daily_total_minor", totals.dailyMinor());
            extra.put("weekly_total_minor", totals.weeklyMinor());
            events.emit(Actions.LIMIT_EXCEEDED, 422, maker.id(), extra);
            audit.record(maker.id(), Actions.LIMIT_EXCEEDED, source.accountNo(),
                    Map.of("amount_minor", amount, "violations", descriptions));
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, Actions.LIMIT_EXCEEDED,
                    String.join("; ", descriptions));
        }

        // 4. Fraud engine.
        boolean newPayee = transfers.countTransfersBetween(source.ownerId(), target.ownerId()) == 0;
        long blockedLast24h = transfers.countBlockedSince(source.ownerId(), now.minus(24, ChronoUnit.HOURS));
        FraudContext context = new FraudContext(
                source.ownerId(),
                target.ownerId(),
                source.accountNo(),
                target.accountNo(),
                amount,
                currency,
                LocalDateTime.now(clock).getHour(),
                totals.hourlyMinor(),
                totals.dailyMinor(),
                newPayee,
                (int) blockedLast24h,
                properties.getPerTransferLimitMinor(),
                properties.getHourlyLimitMinor(),
                properties.getDailyLimitMinor(),
                properties.getStructuringRatio());
        FraudVerdict verdict = fraud.evaluate(context);

        TransferStatus status = switch (verdict.decision()) {
            case ALLOW -> TransferStatus.COMPLETED;
            case REVIEW -> TransferStatus.PENDING_APPROVAL;
            case BLOCK -> TransferStatus.BLOCKED;
        };

        // 5. Persist with the final state.
        String reference = newReference();
        Long id = transfers.insert(reference, source.id(), target.id(),
                source.ownerId(), target.ownerId(), amount, currency, status,
                verdict.decision(), verdict.reasons(), maker.id());
        Transfer transfer = transfers.findById(id).orElseThrow();
        if (status.isPosted()) {
            ledger.post(transfer);
        }

        TransferResponse response = TransferResponse.from(transfer);

        // 5b. Idempotency cache.
        int cachedStatus = status == TransferStatus.BLOCKED ? 422 : 201;
        idempotency.remember(idempotencyKey, requestHash, cachedStatus, writeResponse(response));

        // 5c. Audit + stdout telemetry.
        Map<String, Object> decisionDetails = new LinkedHashMap<>();
        decisionDetails.put("transfer_id", transfer.id());
        decisionDetails.put("reference", reference);
        decisionDetails.put("amount_minor", amount);
        decisionDetails.put("currency", currency);
        decisionDetails.put("source", sourceAccountNo);
        decisionDetails.put("target", targetAccountNo);
        decisionDetails.put("decision", verdict.decision().name());
        decisionDetails.put("reasons", verdict.reasons());

        String action = actionFor(verdict.decision());
        audit.record(maker.id(), action, reference, decisionDetails);

        int httpStatus = switch (status) {
            case COMPLETED -> 201;
            case PENDING_APPROVAL -> 202;
            case BLOCKED -> 422;
            default -> 200;
        };
        events.emit(action, httpStatus, maker.id(), decisionDetails);

        return response;
    }

    public TransferResponse get(Long id) {
        return transfers.findById(id)
                .map(TransferResponse::from)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, Actions.TRANSFER_NOT_FOUND,
                        "No such transfer: " + id));
    }

    private String actionFor(FraudDecision decision) {
        return switch (decision) {
            case ALLOW -> Actions.FRAUD_ALLOW;
            case REVIEW -> Actions.FRAUD_REVIEW;
            case BLOCK -> Actions.FRAUD_BLOCK;
        };
    }

    private void rejectIdempotencyMismatch(String key, String requestHash, Actor maker) {
        String safeKey = key.trim();
        events.emit(Actions.IDEMPOTENCY_MISMATCH, 409, maker.id(),
                Map.of("path", "/transfers", "key", safeKey, "payload_hash", requestHash));
        audit.record(maker.id(), Actions.IDEMPOTENCY_MISMATCH, safeKey,
                Map.of("payload_hash", requestHash));
        throw new BusinessException(HttpStatus.CONFLICT, Actions.IDEMPOTENCY_MISMATCH,
                "Idempotency-Key was already used with a different request payload");
    }

    private BusinessException notFound(String accountNo, Actor maker) {
        events.emit(Actions.TRANSFER_NOT_FOUND, 404, maker.id(),
                Map.of("path", "/transfers", "account", accountNo));
        audit.record(maker.id(), Actions.TRANSFER_NOT_FOUND, accountNo, Map.of());
        return new BusinessException(HttpStatus.NOT_FOUND, Actions.TRANSFER_NOT_FOUND,
                "No such account: " + accountNo);
    }

    private String normalizeCurrency(TransferRequest request, Actor maker) {
        String currency = request.currency() == null || request.currency().isBlank()
                ? properties.getCurrency()
                : request.currency().trim().toUpperCase(Locale.ROOT);
        if (!currency.equalsIgnoreCase(properties.getCurrency())) {
            events.emit(Actions.TRANSFER_UNSUPPORTED_CURRENCY, 422, maker.id(),
                    Map.of("path", "/transfers", "currency", currency));
            audit.record(maker.id(), Actions.TRANSFER_UNSUPPORTED_CURRENCY, currency, Map.of());
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, Actions.TRANSFER_UNSUPPORTED_CURRENCY,
                    "Unsupported currency: " + currency);
        }
        return properties.getCurrency();
    }

    private String trim(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "validation.failed", field + " is required");
        }
        return value.trim();
    }

    private String newReference() {
        String raw = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        return "TRF-" + raw;
    }

    private TransferResponse readResponse(String body) {
        try {
            return mapper.readValue(body, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cached idempotent response is not readable", e);
        }
    }

    private String writeResponse(TransferResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("transfer response is not serialisable", e);
        }
    }
}
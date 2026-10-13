package com.jengabank.payments.account;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.jengabank.payments.account.AccountDtos.AccountDto;
import com.jengabank.payments.account.AccountDtos.LedgerEntryDto;
import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.common.Cursors;
import com.jengabank.payments.common.Page;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.RateLimiter;
import com.jengabank.payments.logging.SecurityEventLogger;

@Service
public class AccountService {

    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9]{6,20}$");
    private static final int ENTRIES_RATE_WINDOW_SECONDS = 60;

    private final AccountRepository accounts;
    private final SecurityEventLogger events;
    private final RateLimiter rateLimiter;
    private final int entriesRatePerMinute;

    public AccountService(AccountRepository accounts, SecurityEventLogger events, RateLimiter rateLimiter,
                           @Value("${jengabank.security.account-entries-rate-per-minute:30}") int entriesRatePerMinute) {
        this.accounts = accounts;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.entriesRatePerMinute = entriesRatePerMinute;
    }

    public Page<AccountDto> list(AuthenticatedUser me, String cursor, Integer limit) {
        int lim = Cursors.limit(limit);
        String after = Cursors.decode(cursor);
        if (after != null && !ACCOUNT_NUMBER.matcher(after).matches()) {
            throw Cursors.invalid();
        }
        return Page.of(accounts.listOwned(me.id(), after, lim + 1), lim, AccountDto::accountNumber);
    }

    public AccountDto get(AuthenticatedUser me, UUID accountId) {
        AccountDto account = requireOwned(me, accountId);
        events.info(Actions.ACCOUNT_VIEWED, "username", me.username(), "account_id", accountId);
        return account;
    }

    public Page<LedgerEntryDto> entries(AuthenticatedUser me, UUID accountId, String cursor, Integer limit) {
        if (!rateLimiter.allow("account.entries:" + me.id(), entriesRatePerMinute, ENTRIES_RATE_WINDOW_SECONDS)) {
            events.warn(Actions.RATE_LIMITED, "username", me.username(), "endpoint", "accounts.entries");
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many requests. Wait a minute, then try again.");
        }
        requireOwned(me, accountId);
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
        return Page.of(accounts.entries(accountId, beforeTs, beforeId, lim + 1), lim,
                e -> e.createdAt() + "|" + e.id());
    }

    /** Not found and not yours look identical (404), but the log records the attempt. */
    private AccountDto requireOwned(AuthenticatedUser me, UUID accountId) {
        return accounts.findOwned(accountId, me.id()).orElseThrow(() -> {
            events.warn(Actions.AUTHZ_ACCESS_DENIED, "username", me.username(),
                    "resource", "account", "account_id", accountId, "reason", "not_found_or_not_owner");
            return BusinessException.notFound();
        });
    }
}

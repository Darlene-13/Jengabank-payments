package com.jengabank.payments.account;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.account.AccountDtos.AccountDto;
import com.jengabank.payments.account.AccountDtos.LedgerEntryDto;
import com.jengabank.payments.auth.CurrentUser;

import com.jengabank.payments.common.Page;

import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Accounts")
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping
    public Page<AccountDto> list(@RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        return service.list(CurrentUser.require(), cursor, limit);
    }

    @GetMapping("/{accountId}")
    public AccountDto get(@PathVariable UUID accountId) {
        return service.get(CurrentUser.require(), accountId);
    }

    @GetMapping("/{accountId}/entries")
    public Page<LedgerEntryDto> entries(@PathVariable UUID accountId,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer limit) {
        return service.entries(CurrentUser.require(), accountId, cursor, limit);
    }
}

package com.jengabank.payments.account;

import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.ledger.LedgerService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final LedgerService ledger;

    public AccountService(AccountRepository accounts, LedgerService ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public List<AccountResponse> list() {
        return accounts.findAll().stream()
                .map(a -> AccountResponse.from(a, accounts.balanceOf(a.accountNo())))
                .toList();
    }

    public AccountResponse get(String accountNo) {
        Account account = require(accountNo);
        return AccountResponse.from(account, accounts.balanceOf(accountNo));
    }

    public Account require(String accountNo) {
        return accounts.findByAccountNo(accountNo)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.NOT_FOUND, "account.not_found", "No such account: " + accountNo));
    }

    @Transactional
    public AccountResponse create(AccountRequest request) {
        if (request.accountNo() == null || request.accountNo().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "validation.failed", "accountNo is required");
        }
        if (accounts.findByAccountNo(request.accountNo()).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "account.exists",
                    "Account already exists: " + request.accountNo());
        }
        if (request.initialBalanceMinor() < 0) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "validation.failed",
                    "initialBalanceMinor must not be negative");
        }
        String ownerId = request.ownerId() == null || request.ownerId().isBlank()
                ? "unknown" : request.ownerId().trim();
        String ownerName = request.ownerName() == null || request.ownerName().isBlank()
                ? ownerId : request.ownerName().trim();

        accounts.insert(request.accountNo().trim(), ownerId, ownerName, "CUSTOMER");
        Account account = accounts.findByAccountNo(request.accountNo().trim()).orElseThrow();
        if (request.initialBalanceMinor() > 0) {
            ledger.postOpening(account, request.initialBalanceMinor());
        }
        return AccountResponse.from(account, request.initialBalanceMinor());
    }

    public long balanceOf(String accountNo) {
        require(accountNo);
        return accounts.balanceOf(accountNo);
    }
}
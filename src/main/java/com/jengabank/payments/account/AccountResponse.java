package com.jengabank.payments.account;

public record AccountResponse(
        Long id,
        String accountNo,
        String ownerId,
        String ownerName,
        String kind,
        String status,
        long balanceMinor) {

    public static AccountResponse from(Account account, long balanceMinor) {
        return new AccountResponse(
                account.id(), account.accountNo(), account.ownerId(), account.ownerName(),
                account.kind(), account.status(), balanceMinor);
    }
}
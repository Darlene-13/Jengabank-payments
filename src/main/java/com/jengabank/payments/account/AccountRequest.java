package com.jengabank.payments.account;

/** Request body for {@code POST /accounts}. */
public record AccountRequest(String accountNo, String ownerId, String ownerName, long initialBalanceMinor) {
}

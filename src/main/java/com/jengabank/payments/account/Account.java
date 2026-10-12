package com.jengabank.payments.account;

public record Account(
        Long id,
        String accountNo,
        String ownerId,
        String ownerName,
        String kind,
        String status) {

    public boolean isBank() {
        return "BANK".equals(kind);
    }
}
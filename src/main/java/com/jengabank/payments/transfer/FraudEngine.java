package com.jengabank.payments.transfer;

import java.util.List;
import java.util.UUID;

/**
 * Dev 3: implement this as a @Service in fraud/ and it replaces the baseline automatically.
 * Runs synchronously, inside the transaction, before the transfer row is written.
 */
public interface FraudEngine {

    enum Decision { ALLOW, REVIEW, BLOCK }

    record Context(UUID customerId, UUID fromAccountId, UUID toAccountId, long amountMinor) {}

    record Result(Decision decision, List<String> reasons) {}

    Result evaluate(Context context);
}

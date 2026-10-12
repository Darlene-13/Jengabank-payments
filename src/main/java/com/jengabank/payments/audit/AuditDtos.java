package com.jengabank.payments.audit;

import java.time.OffsetDateTime;

public final class AuditDtos {

    private AuditDtos() {}

    public record AuditRecordDto(long sequence, OffsetDateTime ts, String actor, String action, String target,
                                 String hash, String prevHash) {}

    public record AuditVerification(boolean valid, long checked, Long brokenAtSequence, OffsetDateTime verifiedAt) {}
}

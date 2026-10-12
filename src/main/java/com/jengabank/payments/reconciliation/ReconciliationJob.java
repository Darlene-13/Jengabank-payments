package com.jengabank.payments.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** End-of-day job: proves the ledger balances while the bank sleeps. */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.reconciliation-cron:0 0 2 * * *}")
    public void endOfDay() {
        try {
            ReconciliationReport report = service.run("scheduler");
            log.info("end-of-day reconciliation {} in {} ms", report.status(), report.durationMs());
        } catch (Exception e) {
            log.error("end-of-day reconciliation failed to run", e);
        }
    }
}

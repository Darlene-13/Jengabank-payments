package com.jengabank.payments.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** End-of-day job: proves the books balance while the bank sleeps. */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final ReconciliationService service;

    public ReconciliationJob(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${jengabank.reconciliation.cron}", zone = "${jengabank.reconciliation.zone}")
    public void endOfDay() {
        try {
            service.run(null, "system");
        } catch (RuntimeException e) {
            log.error("end-of-day reconciliation could not run", e);
        }
    }
}

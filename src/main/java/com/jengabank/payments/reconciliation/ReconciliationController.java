package com.jengabank.payments.reconciliation;

import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.SecurityEventLogger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public ReconciliationReport run(
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId) {
        String actor = userId == null || userId.isBlank() ? "operator" : userId.trim();
        return service.run(actor);
    }

    @GetMapping("/latest")
    public ReconciliationReport latest() {
        ReconciliationReport report = service.latest();
        if (report == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "reconciliation.not_found",
                    "No reconciliation run recorded yet");
        }
        return report;
    }
}
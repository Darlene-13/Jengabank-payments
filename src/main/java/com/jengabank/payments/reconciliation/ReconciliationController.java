package com.jengabank.payments.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.auth.AuthenticatedUser;
import com.jengabank.payments.auth.CurrentUser;

import com.jengabank.payments.error.BusinessException;

import io.swagger.v3.oas.annotations.tags.Tag;

/** POST: CHECKER. GET: MAKER, CHECKER (SecurityConfig). */
@Tag(name = "Reconciliation")
@RestController
@RequestMapping("/api/v1/reconciliation/runs")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    public ReconciliationRun run() {
        AuthenticatedUser me = CurrentUser.require();
        return service.run(me.id(), me.username());
    }

    @GetMapping("/latest")
    public ReconciliationRun latest() {
        return service.latest().orElseThrow(BusinessException::notFound);
    }
}

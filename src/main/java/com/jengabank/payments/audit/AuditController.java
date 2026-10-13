package com.jengabank.payments.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jengabank.payments.audit.AuditDtos.AuditRecordDto;
import com.jengabank.payments.audit.AuditDtos.AuditVerification;
import com.jengabank.payments.auth.CurrentUser;

import com.jengabank.payments.common.Page;

import io.swagger.v3.oas.annotations.tags.Tag;

/** MAKER and CHECKER only (SecurityConfig). */
@Tag(name = "Audit")
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public Page<AuditRecordDto> list(@RequestParam(required = false) String cursor,
                                     @RequestParam(required = false) Integer limit) {
        return service.page(cursor, limit);
    }

    @GetMapping("/verify")
    public AuditVerification verify() {
        return service.verify(CurrentUser.require().username());
    }
}

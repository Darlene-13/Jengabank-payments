package com.jengabank.payments.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Sends browser routes to the React app so a refresh on /transfers/123 doesn't 404. */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/login", "/send", "/transfers", "/transfers/{id}",
        "/accounts/{id}", "/staff/approvals", "/staff/reversals",
        "/staff/reconciliation", "/staff/audit"
    })
    public String forward() {
        return "forward:/index.html";
    }
}

package com.jengabank.payments.account;

import com.jengabank.payments.auth.Actor;
import com.jengabank.payments.auth.ActorResolver;
import com.jengabank.payments.error.BusinessException;
import com.jengabank.payments.logging.Actions;
import com.jengabank.payments.logging.SecurityEventLogger;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;
    private final ActorResolver actors;
    private final SecurityEventLogger events;

    public AccountController(AccountService service, ActorResolver actors, SecurityEventLogger events) {
        this.service = service;
        this.actors = actors;
        this.events = events;
    }

    @GetMapping
    public List<AccountResponse> list() {
        return service.list();
    }

    @GetMapping("/{accountNo}")
    public AccountResponse get(@PathVariable String accountNo) {
        return service.get(accountNo);
    }

    /** Admin-only: opening an account moves value in from the bank equity account. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(
            @RequestBody AccountRequest request,
            @RequestHeader(name = SecurityEventLogger.ID_HEADER, required = false) String userId,
            @RequestHeader(name = SecurityEventLogger.ROLE_HEADER, required = false) String userRole) {
        Actor actor = actors.resolve(userId, userRole);
        if (!actor.role().canAdminister()) {
            events.emit(Actions.AUTH_FORBIDDEN, 403, actor.id(),
                    Map.of("path", "/api/v1/accounts", "reason", "requires_role_ADMIN"));
            throw new BusinessException(HttpStatus.FORBIDDEN, Actions.AUTH_FORBIDDEN,
                    "Only ADMIN may open accounts");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }
}
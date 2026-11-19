package com.tiwalo.banking_core_api.account.internal;

import com.tiwalo.banking_core_api.security.BankingActor;
import com.tiwalo.banking_core_api.shared.ApiProblem;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController {

    private final OwnedAccounts accounts;

    AccountController(OwnedAccounts accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    AccountPage list(@AuthenticationPrincipal BankingActor actor, @RequestParam(required = false) String limit,
                     @RequestParam(required = false) String cursor) {
        try {
            return accounts.list(actor.requireCustomer(), limit == null ? 20 : Integer.parseInt(limit), cursor);
        } catch (NumberFormatException ex) {
            throw new InvalidAccountQuery();
        }
    }

    @GetMapping("/{accountId}")
    ResponseEntity<?> get(@AuthenticationPrincipal BankingActor actor, @PathVariable String accountId) {
        return accounts.find(actor.requireCustomer(), InvalidAccountQuery.parseUuid(accountId))
                .<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(ApiProblem.NOT_FOUND::response);
    }

    @ExceptionHandler(InvalidAccountQuery.class)
    ResponseEntity<ApiProblem> invalidRequest() {
        return ApiProblem.INVALID_REQUEST.response();
    }
}

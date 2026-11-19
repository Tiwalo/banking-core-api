package com.tiwalo.banking_core_api.reconciliation.internal;

import java.io.IOException;
import java.net.URI;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import com.tiwalo.banking_core_api.security.BankingActor;
import com.tiwalo.banking_core_api.shared.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/reconciliations")
class ReconciliationController {
    private final Reconciliations service;

    ReconciliationController(Reconciliations service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<?> capture(@AuthenticationPrincipal BankingActor operator, HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            return ApiProblem.INVALID_REQUEST.response();
        }
        var run = service.capture(operator.actorId());
        return ResponseEntity.created(URI.create("/api/v1/operations/reconciliations/" + run.runId() + "/findings")).body(run);
    }

    @GetMapping("/{runId}/findings")
    ResponseEntity<?> findings(@AuthenticationPrincipal BankingActor operator, @PathVariable String runId,
                               @RequestParam MultiValueMap<String, String> query) {
        if (!Set.of("limit", "cursor").containsAll(query.keySet()) || query.values().stream().anyMatch(v -> v.size() != 1)) {
            return ApiProblem.INVALID_REQUEST.response();
        }
        UUID run;
        int limit;
        try {
            run = UUID.fromString(runId);
            limit = query.containsKey("limit") ? Integer.parseInt(query.getFirst("limit")) : 20;
        } catch (IllegalArgumentException malformed) {
            return ApiProblem.INVALID_REQUEST.response();
        }
        String cursor = query.getFirst("cursor");
        if (!run.toString().equalsIgnoreCase(runId) || limit < 1 || limit > 100
                || (cursor != null && !cursor.matches("[0-9a-f]{64}"))) {
            return ApiProblem.INVALID_REQUEST.response();
        }
        var result = service.findings(operator.actorId(), run, cursor, limit);
        return switch (result.status()) {
            case "OK" -> ResponseEntity.ok(result.page());
            case "NOT_FOUND" -> ApiProblem.NOT_FOUND.response();
            case "INVALID_CURSOR" -> ApiProblem.INVALID_REQUEST.response();
            default -> throw new IllegalStateException("Unexpected reconciliation lookup outcome");
        };
    }

    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    ResponseEntity<ApiProblem> failed(NestedRuntimeException failure) {
        if (failure.getMostSpecificCause() instanceof SQLException sql && "TW403".equals(sql.getSQLState())) {
            return ApiProblem.FORBIDDEN.response();
        }
        return new ApiProblem("urn:tiwalo:problem:internal-error", "Reconciliation service error", 500, "INTERNAL_ERROR").response();
    }
}

package com.tiwalo.banking_core_api.transfer.internal;

import java.net.URI;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.core.NestedRuntimeException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tiwalo.banking_core_api.security.BankingActor;
import com.tiwalo.banking_core_api.shared.ApiProblem;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/transfers")
class TransferController {
    private final InternalTransfers transfers;

    TransferController(InternalTransfers transfers) {
        this.transfers = transfers;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> submit(@AuthenticationPrincipal BankingActor actor, @RequestHeader HttpHeaders headers,
                             @RequestBody JsonNode body) {
        var command = TransferCommand.parse(headers.get("Idempotency-Key"), body);
        var outcome = transfers.submit(actor.actorId(), command);
        return switch (outcome.code()) {
            case "POSTED" -> ResponseEntity.created(URI.create("/api/v1/transfers/" + outcome.receipt().transferId()))
                    .body(outcome.receipt());
            case "IDEMPOTENCY_CONFLICT" -> problem(409, outcome.code(), "Idempotency key conflict");
            case "INSUFFICIENT_FUNDS" -> problem(422, outcome.code(), "Insufficient funds");
            case "TRANSFER_LIMIT_EXCEEDED" -> problem(422, outcome.code(), "Transfer limit exceeded");
            case "SAME_ACCOUNT" -> problem(422, outcome.code(), "Source and destination are the same account");
            case "ACCOUNT_UNAVAILABLE" -> problem(422, outcome.code(), "Account unavailable");
            default -> throw new IllegalStateException("Unknown transfer outcome");
        };
    }

    @GetMapping("/{transferId}")
    ResponseEntity<?> receipt(@AuthenticationPrincipal BankingActor actor, @PathVariable String transferId) {
        return transfers.receipt(actor.actorId(), TransferCommand.id(transferId))
                .<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(ApiProblem.NOT_FOUND::response);
    }

    @ExceptionHandler({TransferCommand.InvalidRequest.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiProblem> invalidRequest() {
        return ApiProblem.INVALID_REQUEST.response();
    }

    @ExceptionHandler({DataAccessException.class, TransactionSystemException.class})
    ResponseEntity<ApiProblem> databaseFailure(NestedRuntimeException failure) {
        if (failure.getMostSpecificCause() instanceof SQLException sql && sql.getSQLState() != null) {
            return switch (sql.getSQLState()) {
                case "TW404" -> ApiProblem.NOT_FOUND.response();
                case "55P03", "40P01", "40001" -> problem(503, "TRANSFER_BUSY", "Transfer temporarily unavailable");
                default -> problem(500, "INTERNAL_ERROR", "Transfer service error");
            };
        }
        return problem(500, "INTERNAL_ERROR", "Transfer service error");
    }

    private static ResponseEntity<ApiProblem> problem(int status, String code, String title) {
        String type = "urn:tiwalo:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-');
        return new ApiProblem(type, title, status, code).response();
    }
}

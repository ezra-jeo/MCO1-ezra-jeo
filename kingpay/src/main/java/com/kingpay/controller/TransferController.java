package com.kingpay.controller;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.service.TransferService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated standalone transfer and owner-only history routes. */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService service;

    /**
     * Receives the application service through constructor injection.
     *
     * <p>Preconditions: Spring supplies the service instance.
     * <p>Postconditions: The controller delegates operations to the supplied service.
     * @param service owning application service
     */
    public TransferController(TransferService service) {
        this.service = service;
    }

    /**
     * Routes standalone money transfer to the Phase 4 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The service applies the transfer atomically or reads owner-only history; failures use centralized errors.
     * @param request validated request body
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the persisted transfer or private history DTOs
     */
    @Operation(summary = "Transfer between wallets",
            description = "Only source owner can authorize. Distinct active owners, sufficient funds and recipient capacity are required. Debit, credit and one ledger row commit together. Successful retries are independent transfers.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping
    public ResponseEntity<ApiResponse<TransferResponse>> transferMoney(@Valid @RequestBody TransferRequest request, Principal caller) {
        TransferResponse result = service.transferMoney(request, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Transfer successful", result));
    }

    /**
     * Routes private transfer history to the Phase 4 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The service applies the transfer atomically or reads owner-only history; failures use centralized errors.
     * @param accountId resource identifier
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the persisted transfer or private history DTOs
     */
    @Operation(summary = "Read private transfer history",
            description = "Only requested wallet's owner can read incoming/outgoing successful records, newest first with descending ID tie-break. Unpaginated; no ledger edit/delete endpoints.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping("/history/{accountId}")
    public ResponseEntity<ApiResponse<List<TransferResponse>>> getTransferHistory(@PathVariable("accountId") Long accountId, Principal caller) {
        List<TransferResponse> result = service.getTransferHistory(accountId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Transfer history retrieved", result));
    }
}

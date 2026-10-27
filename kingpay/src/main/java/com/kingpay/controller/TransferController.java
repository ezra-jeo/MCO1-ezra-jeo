package com.kingpay.controller;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.service.TransferService;
import jakarta.validation.Valid;
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
    @GetMapping("/history/{accountId}")
    public ResponseEntity<ApiResponse<List<TransferResponse>>> getTransferHistory(@PathVariable("accountId") Long accountId, Principal caller) {
        List<TransferResponse> result = service.getTransferHistory(accountId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Transfer history retrieved", result));
    }
}

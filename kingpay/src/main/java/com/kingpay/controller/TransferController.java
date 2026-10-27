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

/** Draft HTTP routes; service stubs deliberately report HTTP 501. */
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
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param request validated request body
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
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
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param accountId resource identifier
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @GetMapping("/history/{accountId}")
    public ResponseEntity<ApiResponse<List<TransferResponse>>> getTransferHistory(@PathVariable("accountId") Long accountId, Principal caller) {
        List<TransferResponse> result = service.getTransferHistory(accountId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Transfer history retrieved", result));
    }
}

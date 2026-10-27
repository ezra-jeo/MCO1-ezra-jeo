package com.kingpay.controller;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.response.AccountResponse;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.service.AccountService;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Draft HTTP routes; service stubs deliberately report HTTP 501. */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService service;

    /**
     * Receives the application service through constructor injection.
     *
     * <p>Preconditions: Spring supplies the service instance.
     * <p>Postconditions: The controller delegates operations to the supplied service.
     * @param service owning application service
     */
    public AccountController(AccountService service) {
        this.service = service;
    }

    /**
     * Routes zero-balance account creation to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(Principal caller) {
        AccountResponse result = service.create(caller);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Account created", result));
    }

    /**
     * Routes simulated wallet loading to the Phase 3 service.
     *
     * <p>Preconditions: request syntax is valid; the future service enforces
     * authenticated ownership, active status, and the account's balance limits.
     * <p>Postconditions: the skeleton service throws without changing state.
     * @param request validated positive amount with at most two decimal places
     * @param caller framework-supplied principal; may be absent until Phase 2
     * @return the planned updated account; currently HTTP 501 via the exception handler
     */
    @PostMapping("/me/load")
    public ResponseEntity<ApiResponse<AccountResponse>> load(@Valid @RequestBody LoadAccountRequest request, Principal caller) {
        AccountResponse result = service.load(request, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Wallet loaded", result));
    }

    /**
     * Routes own account lookup to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AccountResponse>> getMe(Principal caller) {
        AccountResponse result = service.getMe(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Account retrieved", result));
    }
}

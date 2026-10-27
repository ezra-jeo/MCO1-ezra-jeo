package com.kingpay.controller;

import com.kingpay.dto.request.CreateAccountRequest;
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
     * Routes demo account creation to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param request validated request body
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(@Valid @RequestBody CreateAccountRequest request, Principal caller) {
        AccountResponse result = service.create(request, caller);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Account created", result));
    }

    /**
     * Routes own account lookup to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AccountResponse>> getMe(Principal caller) {
        AccountResponse result = service.getMe(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Account retrieved", result));
    }
}

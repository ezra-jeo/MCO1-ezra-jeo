package com.kingpay.controller;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.response.AccountResponse;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.service.AccountService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated Phase 3 routes; persistence and domain checks belong to the service. */
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
     * <p>Postconditions: The service returns the owned DTO or applies the documented authorized change.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 201 with the newly created zero-balance owner wallet
     */
    @Operation(summary = "Create my missing wallet",
            description = "Creates a zero-balance wallet only for an accountless identity. Normal registration already creates one, so this normally returns 409. Opening balances are not accepted.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> create(Principal caller) {
        AccountResponse result = service.create(caller);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Account created", result));
    }

    /**
     * Routes simulated wallet loading to the Phase 3 service.
     *
     * <p>Preconditions: request syntax is valid; the service enforces
     * authenticated ownership, active status, and the account's balance limits.
     * <p>Postconditions: the exact validated amount is added to the caller's wallet.
     * @param request validated positive amount with at most two decimal places
     * @param caller framework-supplied authenticated principal
     * @return HTTP 200 with the updated owner wallet
     */
    @Operation(summary = "Load my simulated wallet",
            description = "Adds an exact positive amount fitting 17 integer and 2 fractional digits. Each successful retry is another credit. No external payment or transfer-ledger entry.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
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
     * <p>Postconditions: The service returns the owned DTO or applies the documented authorized change.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the owner's current wallet
     */
    @Operation(summary = "Read my wallet",
            description = "Returns the wallet selected by the authenticated stable user ID, including balance.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AccountResponse>> getMe(Principal caller) {
        AccountResponse result = service.getMe(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Account retrieved", result));
    }
}

package com.kingpay.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.kingpay.dto.request.LoginRequest;
import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.AuthResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public registration and login routes; resource access uses bearer authentication. */
@SecurityRequirements
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService service;

    /**
     * Receives the application service through constructor injection.
     *
     * <p>Preconditions: Spring supplies the service instance.
     * <p>Postconditions: The controller delegates operations to the supplied service.
     * @param service owning application service
     */
    public AuthController(AuthService service) {
        this.service = service;
    }

    /**
     * Registers a user and its initial zero-balance wallet.
     *
     * <p>Preconditions: request syntax passes Bean Validation.
     * <p>Postconditions: success returns HTTP 201 without credentials; duplicate
     * usernames are translated to HTTP 409 by the exception handler.
     * @param request validated request body
     * @return HTTP 201 and the registered user's response fields
     */
    @Operation(summary = "Register a user",
            description = "Atomically creates a user and a zero-balance wallet. Passwords are limited to 72 UTF-8 bytes. Public operation.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse result = service.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("User registered", result));
    }

    /**
     * Authenticates credentials and returns a bearer token.
     *
     * <p>Preconditions: request syntax is valid.
     * <p>Postconditions: success returns HTTP 200 and an expiring token; invalid credentials return 401.
     * @param request validated request body
     * @return HTTP 200 and bearer token metadata
     */
    @Operation(summary = "Log in",
            description = "Returns a short-lived bearer token for an active identity. Public operation; no refresh/logout endpoint.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse result = service.login(request);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Login successful", result));
    }
}

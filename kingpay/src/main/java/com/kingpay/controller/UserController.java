package com.kingpay.controller;

import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.SuperheroResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.service.UserService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated Phase 3 routes; persistence and domain checks belong to the service. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    /**
     * Receives the application service through constructor injection.
     *
     * <p>Preconditions: Spring supplies the service instance.
     * <p>Postconditions: The controller delegates operations to the supplied service.
     * @param service owning application service
     */
    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * Routes own profile lookup to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The service returns the owned DTO or applies the documented authorized change.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the owner's profile
     */
    @Operation(summary = "Read my profile",
            description = "Returns only the authenticated user's current profile and contact details.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(Principal caller) {
        UserResponse result = service.getMe(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Profile retrieved", result));
    }

    /**
     * Routes own profile update to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The service returns the owned DTO or applies the documented authorized change.
     * @param request validated request body
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the updated owner's profile
     */
    @Operation(summary = "Replace my editable profile",
            description = "Replaces username, fullName, phone, email and isSuperhero. Server-owned fields cannot be changed. Outstanding provider work prevents removing eligibility.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(@Valid @RequestBody UpdateUserRequest request, Principal caller) {
        UserResponse result = service.updateMe(request, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Profile updated", result));
    }

    /**
     * Routes user deactivation to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The service returns the owned DTO or applies the documented authorized change.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 204 with no body after guarded deactivation
     */
    @Operation(summary = "Deactivate my user",
            description = "Requires zero wallet balance and no outstanding booking in either role. Freezes only the user; all financial/history rows are retained.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @DeleteMapping("/me")
    public ResponseEntity<Void> deactivateMe(Principal caller) {
        service.deactivateMe(caller);
        return ResponseEntity.noContent().build();
    }

    /**
     * Routes superhero browsing to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The service returns the owned DTO or applies the documented authorized change.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with sorted bookable superhero summaries
     */
    @Operation(summary = "List bookable superheroes",
            description = "Active eligible providers with wallet IDs, without contact details or balances.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping("/superheroes")
    public ResponseEntity<ApiResponse<List<SuperheroResponse>>> getSuperheroes(Principal caller) {
        List<SuperheroResponse> result = service.getSuperheroes(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Superheroes retrieved", result));
    }
}

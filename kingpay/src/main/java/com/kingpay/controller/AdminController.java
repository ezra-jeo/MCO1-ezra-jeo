package com.kingpay.controller;

import com.kingpay.dto.response.AdminOverviewResponse;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only global overview for externally configured admins. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService service;

    /** Preconditions: Spring supplies the service. Postconditions: no data is accessed.
     * @param service admin authorization and snapshot reader */
    public AdminController(AdminService service) { this.service = service; }

    /** Preconditions: bearer identifies an active user in KINGPAY_ADMIN_USER_IDS.
     * Postconditions: returns credential-free global DTOs without modifying records.
     * @param caller framework principal; cannot be replaced by a query/body identity
     * @return HTTP 200 with all users, balances, bookings and transfers; forbidden callers receive 403 */
    @Operation(summary = "Read all application records",
            description = "Active caller's stable user ID must be in KINGPAY_ADMIN_USER_IDS; empty configuration denies everyone. Returns all users (including contact details/inactive users), balances, bookings and transfers as credential-free DTOs in ascending ID order. Read-only snapshot; unpaginated.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<AdminOverviewResponse>> overview(Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Admin overview retrieved", service.overview(caller)));
    }
}

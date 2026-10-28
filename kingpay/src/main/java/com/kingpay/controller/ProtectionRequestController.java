package com.kingpay.controller;

import com.kingpay.dto.request.CreateProtectionRequest;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.dto.response.ProtectionRequestResponse;
import com.kingpay.service.ProtectionRequestService;
import java.util.List;
import com.kingpay.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Initial engagement requests, distinct from later completion confirmations. */
@RestController @RequestMapping("/api/v1/protection-requests")
public class ProtectionRequestController {
    private final ProtectionRequestService service;
    /** Preconditions: service supplied by Spring. Postconditions: no queries.
     * @param service initial-agreement rules */
    public ProtectionRequestController(ProtectionRequestService service) { this.service = service; }

    /** Preconditions: active distinct parties and matching current displayed rate. Postconditions: Pending request only.
     * @param request hero user ID and expected-price check
     * @param caller authenticated customer
     * @return HTTP 201 submitted proposal */
    @Operation(summary = "Request protection at the displayed price", description = "Customer submits serviceProviderUserId and expectedAmount. The server checks its current rate and snapshots it. A stale price returns 409. No booking or money movement until provider accepts.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ProtectionRequestResponse>> create(@Valid @RequestBody CreateProtectionRequest request, Principal caller) {
        return ResponseEntity.status(201).body(new ApiResponse<>("Protection requested", service.create(request, caller)));
    }

    /** Preconditions: active caller. Postconditions: private list in either role, no writes.
     * @param caller framework principal
     * @return HTTP 200 own proposals */
    @Operation(summary = "List my protection requests", description = "Private submissions and provider inbox, newest first with descending ID tie-break. Unpaginated.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProtectionRequestResponse>>> getMine(Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Protection requests retrieved", service.getMine(caller)));
    }

    /** Preconditions: caller participates in requested proposal. Postconditions: private DTO, no writes.
     * @param requestId proposal ID
     * @param caller framework principal
     * @return HTTP 200 participant proposal */
    @Operation(summary = "Read a protection request", description = "Only the assigned customer or provider can read the proposal and its preserved price.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<ProtectionRequestResponse>> getById(@PathVariable("requestId") Long requestId, Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Protection request retrieved", service.getById(requestId, caller)));
    }

    /** Preconditions: active assigned provider; pending engagement remains available. Postconditions: one accepted booking.
     * @param requestId proposal ID
     * @param caller assigned provider
     * @return HTTP 200 original or new booking */
    @Operation(summary = "Accept a protection request", description = "Assigned provider only. Preserves submitted price even after rate changes. Agreement and exactly one Unconfirmed booking commit atomically. Repeated acceptance returns the same booking; a declined request returns 409.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @PostMapping("/{requestId}/accept")
    public ResponseEntity<ApiResponse<BookingResponse>> accept(@PathVariable("requestId") Long requestId, Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Protection request accepted", service.accept(requestId, caller)));
    }

    /** Preconditions: active assigned provider and proposal not accepted. Postconditions: Declined with no booking.
     * @param requestId proposal ID
     * @param caller assigned provider
     * @return HTTP 200 declined proposal */
    @Operation(summary = "Decline a protection request", description = "Assigned active provider only. Repeat decline is harmless; accepted proposals cannot be declined. No booking or financial records are created.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @PostMapping("/{requestId}/decline")
    public ResponseEntity<ApiResponse<ProtectionRequestResponse>> decline(@PathVariable("requestId") Long requestId, Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Protection request declined", service.decline(requestId, caller)));
    }

}

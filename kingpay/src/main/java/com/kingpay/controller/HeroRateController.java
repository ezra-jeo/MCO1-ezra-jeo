package com.kingpay.controller;

import com.kingpay.dto.request.PublishRateRequest;
import com.kingpay.dto.response.HeroRateResponse;
import com.kingpay.service.HeroRateService;
import com.kingpay.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Hero-owned publication and authenticated lookup by hero user ID. */
@RestController @RequestMapping("/api/v1/users")
public class HeroRateController {
    private final HeroRateService service;
    /** Preconditions: service supplied by Spring. Postconditions: no queries.
     * @param service publication rules */
    public HeroRateController(HeroRateService service) { this.service = service; }

    /** Preconditions: active provider with wallet and valid price. Postconditions: own flat price published.
     * @param request exact positive amount
     * @param caller authenticated owner
     * @return HTTP 200 current publication */
    @Operation(summary = "Publish my flat rate", description = "Active superhero only. One rate per provider; updates affect future requests only. No money moves.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @PutMapping("/me/rate")
    public ResponseEntity<ApiResponse<HeroRateResponse>> publish(@Valid @RequestBody PublishRateRequest request, Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Rate published", service.publish(request, caller)));
    }

    /** Preconditions: active caller, available published hero. Postconditions: public rate DTO, no writes.
     * @param userId hero user ID
     * @param caller framework principal
     * @return HTTP 200 current rate */
    @Operation(summary = "Read a superhero flat rate", description = "Lookup uses the hero user ID. Missing publication or unavailable provider returns 404.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
    })
    @GetMapping("/{userId}/rate")
    public ResponseEntity<ApiResponse<HeroRateResponse>> getByUserId(@PathVariable("userId") Long userId, Principal caller) {
        return ResponseEntity.ok(new ApiResponse<>("Rate retrieved", service.getByUserId(userId, caller)));
    }

}

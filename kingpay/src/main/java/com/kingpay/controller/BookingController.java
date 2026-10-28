package com.kingpay.controller;

import com.kingpay.dto.request.CreateBookingRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.service.BookingService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
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

/** Private booking creation, completion and explicit customer-authorized payment. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService service;

    /**
     * Receives the application service through constructor injection.
     *
     * <p>Preconditions: Spring supplies the service instance.
     * <p>Postconditions: The controller delegates operations to the supplied service.
     * @param service owning application service
     */
    public BookingController(BookingService service) {
        this.service = service;
    }

    /**
     * Routes booking creation to the Phase 5 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: Stores fixed parties/price in Unconfirmed state without moving money.
     * @param request validated request body
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 201 with the newly created booking
     */
    @Operation(summary = "Create a fixed-price booking",
            description = "Customer is the authenticated identity. Requires distinct wallets and an active superhero provider. Quote is fixed and positive; funds are not required and no money moves. Repeated creates are separate jobs.")
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
    public ResponseEntity<ApiResponse<BookingResponse>> create(@Valid @RequestBody CreateBookingRequest request, Principal caller) {
        BookingResponse result = service.create(request, caller);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Booking created", result));
    }

    /**
     * Routes participant booking lookup to the Phase 5 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: Either assigned participant receives the booking without changing state.
     * @param bookingId resource identifier
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the private booking DTO
     */
    @Operation(summary = "Read a participant booking",
            description = "Only assigned customer or provider can read the booking. Parties and price remain fixed.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResponse<BookingResponse>> getById(@PathVariable("bookingId") Long bookingId, Principal caller) {
        BookingResponse result = service.getById(bookingId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Booking retrieved", result));
    }

    /**
     * Routes participant booking list to the Phase 5 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: Returns only the caller's bookings in either role, newest first.
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with participant booking DTOs, or an empty list
     */
    @Operation(summary = "List my bookings",
            description = "Returns caller's bookings in either role, newest first with descending ID tie-break. Empty array when none; unpaginated.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingResponse>>> getMine(Principal caller) {
        List<BookingResponse> result = service.getMine(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Bookings retrieved", result));
    }

    /**
     * Routes customer completion confirmation to the Phase 5 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: Records the assigned customer's confirmation; repeats preserve state and no money moves.
     * @param bookingId resource identifier
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the current booking state
     */
    @Operation(summary = "Confirm customer completion",
            description = "Assigned customer only. Either confirmation order is supported; both reach ConfirmedUnpaid. Repeats preserve current state/link, including paid jobs. No money moves.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping("/{bookingId}/confirmations/customer")
    public ResponseEntity<ApiResponse<BookingResponse>> confirmCustomer(@PathVariable("bookingId") Long bookingId, Principal caller) {
        BookingResponse result = service.confirmCustomer(bookingId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Customer confirmation recorded", result));
    }

    /**
     * Routes service provider completion confirmation to the Phase 5 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: Records the assigned provider's confirmation; repeats preserve state and no money moves.
     * @param bookingId resource identifier
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the current booking state
     */
    @Operation(summary = "Confirm provider completion",
            description = "Assigned provider only. Either confirmation order is supported; both reach ConfirmedUnpaid. Repeats preserve current state/link, including paid jobs. No money moves.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping("/{bookingId}/confirmations/service-provider")
    public ResponseEntity<ApiResponse<BookingResponse>> confirmServiceProvider(@PathVariable("bookingId") Long bookingId, Principal caller) {
        BookingResponse result = service.confirmServiceProvider(bookingId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Provider confirmation recorded", result));
    }

    /**
     * Routes explicit payment to the atomic booking service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: Wallets, ledger and booking commit together; successful repeats return the original result.
     * @param bookingId resource identifier
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return HTTP 200 with the paid booking and its transfer ID
     */
    @Operation(summary = "Pay a completed booking",
            description = "Active customer only, after both confirmations. Uses stored parties/price and atomically commits wallets, ledger and paid link. Failure stays unpaid without partial effects; successful retries return original payment, including concurrent requests. Body values cannot substitute financial fields.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success", useReturnTypeSchema = true),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", ref = "#/components/responses/InvalidRequest"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", ref = "#/components/responses/Unauthenticated"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", ref = "#/components/responses/Forbidden"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", ref = "#/components/responses/NotFound"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", ref = "#/components/responses/Conflict"),
                @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", ref = "#/components/responses/ServerError")
            })
    @PostMapping("/{bookingId}/payment")
    public ResponseEntity<ApiResponse<BookingResponse>> pay(@PathVariable("bookingId") Long bookingId, Principal caller) {
        BookingResponse result = service.pay(bookingId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Booking paid", result));
    }
}

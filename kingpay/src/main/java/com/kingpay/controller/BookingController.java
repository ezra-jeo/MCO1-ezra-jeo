package com.kingpay.controller;

import com.kingpay.dto.request.CreateBookingRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.service.BookingService;
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

/** Private booking creation, reads and completion; payment remains the Phase 6 placeholder. */
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
    @PostMapping("/{bookingId}/confirmations/service-provider")
    public ResponseEntity<ApiResponse<BookingResponse>> confirmServiceProvider(@PathVariable("bookingId") Long bookingId, Principal caller) {
        BookingResponse result = service.confirmServiceProvider(bookingId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Provider confirmation recorded", result));
    }

    /**
     * Routes booking payment to the Phase 6 service.
     *
     * <p>Preconditions: Request syntax is valid and the security filter has authenticated an active user.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param bookingId resource identifier
     * @param caller framework-supplied authenticated principal; never taken from the request body
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @PostMapping("/{bookingId}/payment")
    public ResponseEntity<ApiResponse<BookingResponse>> pay(@PathVariable("bookingId") Long bookingId, Principal caller) {
        BookingResponse result = service.pay(bookingId, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Booking paid", result));
    }
}

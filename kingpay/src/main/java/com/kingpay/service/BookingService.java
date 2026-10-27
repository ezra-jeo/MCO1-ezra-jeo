package com.kingpay.service;

import com.kingpay.dto.request.CreateBookingRequest;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.exception.UnimplementedOperationException;
import com.kingpay.security.OwnershipGuard;
import java.security.Principal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Draft booking boundary; no business operations are implemented. */
@Service
public class BookingService {

    private final OwnershipGuard ownership;

    /**
     * Receives participant authorization for the later booking implementation.
     * <p>Preconditions: ownership is non-null.
     * <p>Postconditions: identifier-based operations verify the correct participant role.
     * @param ownership authorization guard
     */
    public BookingService(OwnershipGuard ownership) {
        this.ownership = ownership;
    }

    /**
     * Reserves booking creation for Phase 5.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public BookingResponse create(CreateBookingRequest request, Principal caller) {
        throw new UnimplementedOperationException("Booking creation");
    }

    /**
     * Reserves participant booking lookup for Phase 5.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param bookingId operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public BookingResponse getById(Long bookingId, Principal caller) {
        ownership.bookingParticipant(bookingId, caller);
        throw new UnimplementedOperationException("Participant booking lookup");
    }

    /**
     * Reserves participant booking list for Phase 5.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public List<BookingResponse> getMine(Principal caller) {
        throw new UnimplementedOperationException("Participant booking list");
    }

    /**
     * Reserves customer completion confirmation for Phase 5.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param bookingId operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public BookingResponse confirmCustomer(Long bookingId, Principal caller) {
        ownership.bookingCustomer(bookingId, caller);
        throw new UnimplementedOperationException("Customer completion confirmation");
    }

    /**
     * Reserves service provider completion confirmation for Phase 5.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param bookingId operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public BookingResponse confirmServiceProvider(Long bookingId, Principal caller) {
        ownership.bookingProvider(bookingId, caller);
        throw new UnimplementedOperationException("Service provider completion confirmation");
    }

    /**
     * Reserves booking payment for Phase 6.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param bookingId operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public BookingResponse pay(Long bookingId, Principal caller) {
        ownership.bookingCustomer(bookingId, caller);
        throw new UnimplementedOperationException("Booking payment");
    }
}

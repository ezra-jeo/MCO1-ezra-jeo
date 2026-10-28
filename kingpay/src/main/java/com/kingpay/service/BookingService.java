package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.User;
import com.kingpay.entity.TransferRecord;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.BookingRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.security.Principal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Fixed-price bookings, private completion and atomic customer-authorized payment. */
@Service
@Validated
@Transactional(readOnly = true)
public class BookingService {
    private final BookingRepository bookings;
    private final CurrentUser identities;
    private final EntityManager entities;
    private final TransferService transfers;

    /** Preconditions: Spring supplies persistence/security. Postconditions: no queries or writes.
     * @param bookings booking persistence
     * @param identities active caller resolver
     * @param entities ordered locking context
     * @param transfers transactional wallet/ledger writer */
    public BookingService(BookingRepository bookings, CurrentUser identities, EntityManager entities,
            TransferService transfers) {
        this.bookings = bookings;
        this.identities = identities;
        this.entities = entities;
        this.transfers = transfers;
    }

    /** Preconditions: active caller is either participant. Postconditions: private DTO only; no writes.
     * @param bookingId requested booking ID
     * @param caller framework principal
     * @return participant's booking */
    public BookingResponse getById(Long bookingId, Principal caller) {
        User actor = identities.active(caller);
        Booking booking = booking(bookingId);
        if (!booking.getCustomer().getUser().getId().equals(actor.getId())
                && !booking.getServiceProvider().getUser().getId().equals(actor.getId())) {
            throw new AccessDeniedException("Access denied.");
        }
        return response(booking);
    }

    /** Preconditions: active caller. Postconditions: only caller's bookings in either role, newest-first; no writes.
     * @param caller framework principal
     * @return participant DTOs, empty when no bookings exist */
    public List<BookingResponse> getMine(Principal caller) {
        User actor = identities.active(caller);
        return bookings.findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(actor.getId(), actor.getId())
                .stream().map(this::response).toList();
    }

    /** Preconditions: active assigned customer. Postconditions: records own confirmation; repeats are no-ops, including paid jobs.
     * @param bookingId fixed-party booking ID
     * @param caller framework principal
     * @return current booking state without financial changes */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse confirmCustomer(Long bookingId, Principal caller) {
        return confirm(bookingId, caller, true);
    }

    /** Preconditions: active assigned provider. Postconditions: records own confirmation; repeats are no-ops, including paid jobs.
     * @param bookingId fixed-party booking ID
     * @param caller framework principal
     * @return current booking state without financial changes */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse confirmServiceProvider(Long bookingId, Principal caller) {
        return confirm(bookingId, caller, false);
    }

    /** Preconditions: active customer owns booking; first payment requires both confirmations and sufficient funds.
     * Postconditions: debit, credit, ledger and paid association commit together; any failure rolls them all back.
     * Successful repeats return the original paid result without another transfer.
     * @param bookingId requested booking ID
     * @param caller framework principal
     * @return paid booking and its original successful transfer ID
     * @throws BusinessConflictException if both confirmations are not yet present
     * @throws com.kingpay.exception.InsufficientBalanceException if current funds do not cover the price */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse pay(Long bookingId, Principal caller) {
        User actor = identities.active(caller);
        Booking booking = booking(bookingId);
        requireRole(booking, actor, true);
        // All owners precede the booking lock; the joined transfer then locks wallets in ascending ID order.
        lockOwners(booking.getCustomer(), booking.getServiceProvider());
        entities.refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        requireActive(booking.getCustomer().getUser());
        if (booking.getStatus() == BookingStatus.ConfirmedPaid) {
            return response(booking);
        }
        if (booking.getStatus() != BookingStatus.ConfirmedUnpaid) {
            throw new BusinessConflictException("Both participants must confirm before payment.");
        }
        TransferResponse transfer = transfers.transferMoney(new TransferRequest(booking.getCustomer().getId(),
                booking.getServiceProvider().getId(), booking.getAmount()), caller);
        booking.markPaid(entities.getReference(TransferRecord.class, transfer.id()));
        return response(bookings.saveAndFlush(booking));
    }

    /** Preconditions: caller authenticated. Postconditions: authorized transition serializes with other booking writers.
     * @param id booking ID
     * @param caller framework principal
     * @param asCustomer true for customer confirmation, false for provider
     * @return current state; never transfers funds */
    private BookingResponse confirm(Long id, Principal caller, boolean asCustomer) {
        User actor = identities.active(caller);
        Booking booking = booking(id);
        requireRole(booking, actor, asCustomer);
        lockOwners(booking.getCustomer(), booking.getServiceProvider());
        entities.refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        requireActive(asCustomer ? booking.getCustomer().getUser() : booking.getServiceProvider().getUser());
        BookingStatus before = booking.getStatus();
        if (asCustomer) { booking.confirmCustomer(); } else { booking.confirmServiceProvider(); }
        if (booking.getStatus() != before) { bookings.saveAndFlush(booking); }
        return response(booking);
    }

    /** Preconditions: parties are distinct persisted owners, service write transaction active.
     * Postconditions: current owner state locked in ascending user-ID order, coordinating eligibility/deactivation.
     * @param customer customer wallet
     * @param provider assigned provider wallet */
    private void lockOwners(Account customer, Account provider) {
        List<User> owners = Stream.of(customer.getUser(), provider.getUser()).sorted(Comparator.comparing(User::getId)).toList();
        for (User owner : owners) { entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE); }
    }

    /** Preconditions: none. Postconditions: invalid identifier or absent record fails safely.
     * @param id booking ID
     * @return existing booking */
    private Booking booking(Long id) {
        if (id == null || id <= 0) { throw new InvalidRequestException("A positive booking ID is required."); }
        return bookings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
    }

    /** Preconditions: actor and parties persisted. Postconditions: other role/outsiders receive 403.
     * @param booking protected booking
     * @param actor authenticated identity
     * @param asCustomer required participant role */
    private void requireRole(Booking booking, User actor, boolean asCustomer) {
        Long expected = asCustomer ? booking.getCustomer().getUser().getId() : booking.getServiceProvider().getUser().getId();
        if (!expected.equals(actor.getId())) { throw new AccessDeniedException("Access denied."); }
    }

    /** Preconditions: identity just refreshed under lock. Postconditions: inactive actor cannot mutate booking.
     * @param actor persisted participant */
    private void requireActive(User actor) {
        if (!actor.isActive()) { throw new AccessDeniedException("Access denied."); }
    }

    /** Preconditions: authorized persisted booking. Postconditions: only declared IDs/status/price/time exposed.
     * @param booking protected booking
     * @return immutable response; payment link null before payment */
    private BookingResponse response(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getCreatedAt(), booking.getCustomer().getId(),
                booking.getServiceProvider().getId(), booking.getAmount(), booking.getStatus(),
                booking.getPaymentTransfer() == null ? null : booking.getPaymentTransfer().getId(),
                booking.getProtectionRequest() == null ? null : booking.getProtectionRequest().getId());
    }
}

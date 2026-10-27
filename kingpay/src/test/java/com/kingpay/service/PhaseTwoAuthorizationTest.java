package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.User;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.exception.UnimplementedOperationException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.security.OwnershipGuard;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** P2-A: each protected service operation is exercised before its future business placeholder. */
class PhaseTwoAuthorizationTest {
    enum Action { TRANSFER, HISTORY, BOOKING_READ, CUSTOMER_CONFIRM, PROVIDER_CONFIRM, PAY }
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final OwnershipGuard guard = new OwnershipGuard(accounts, bookings);
    private final TransferService transfers = new TransferService(guard);
    private final BookingService service = new BookingService(guard);

    /** Preconditions: real owner/assigned actor calls. Postconditions: passes guard and reaches future-phase placeholder.
     * @param action protected operation */
    @ParameterizedTest @EnumSource(Action.class)
    void allowedActorReachesPlaceholder(Action action) {
        seed();
        String actor = action == Action.PROVIDER_CONFIRM ? "provider" : "customer";
        assertThrows(UnimplementedOperationException.class, () -> invoke(action, 1L, () -> actor));
    }

    /** Preconditions: third party calls an existing resource. Postconditions: forbidden, without writes.
     * @param action protected operation */
    @ParameterizedTest @EnumSource(Action.class)
    void unrelatedActorIsDenied(Action action) {
        seed();
        assertThrows(AccessDeniedException.class, () -> invoke(action, 1L, () -> "outsider"));
        verify(accounts, never()).save(any());
        verify(bookings, never()).save(any());
    }

    /** Preconditions: resource absent. Postconditions: missing-resource failure precedes business execution.
     * @param action protected operation */
    @ParameterizedTest @EnumSource(Action.class)
    void missingResourceIsRejected(Action action) {
        assertThrows(ResourceNotFoundException.class, () -> invoke(action, 1L, () -> "customer"));
    }

    /** Preconditions: direct service caller lacks principal. Postconditions: authentication required before repository access.
     * @param action protected operation */
    @ParameterizedTest @EnumSource(Action.class)
    void absentPrincipalIsRejected(Action action) {
        assertThrows(InsufficientAuthenticationException.class, () -> invoke(action, 1L, null));
        verifyNoInteractions(accounts, bookings);
    }

    /** Preconditions: invalid wallet/booking ID. Postconditions: rejects before lookup.
     * @param id absent or nonpositive identifier */
    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1})
    void invalidIdentifiersAreRejected(Long id) {
        assertThrows(InvalidRequestException.class, () -> guard.accountOwner(id, () -> "customer"));
        assertThrows(InvalidRequestException.class, () -> guard.bookingParticipant(id, () -> "customer"));
        verifyNoInteractions(accounts, bookings);
    }

    /** Preconditions: no fixtures. Postconditions: existing resource stubs have distinct owners. */
    private void seed() {
        Account customer = new Account(new User("customer", "hash", "Customer", "0", "c@example.test", false), BigDecimal.ZERO);
        Account provider = new Account(new User("provider", "hash", "Provider", "0", "p@example.test", true), BigDecimal.ZERO);
        when(accounts.findById(1L)).thenReturn(Optional.of(customer));
        when(bookings.findById(1L)).thenReturn(Optional.of(new Booking(customer, provider, BigDecimal.ONE)));
    }

    /** Preconditions: dependencies configured. Postconditions: invokes exactly the chosen protected method.
     * @param action operation
     * @param id resource identifier
     * @param caller framework principal */
    private void invoke(Action action, Long id, Principal caller) {
        switch (action) {
            case TRANSFER -> transfers.transferMoney(new TransferRequest(id, 2L, BigDecimal.ONE), caller);
            case HISTORY -> transfers.getTransferHistory(id, caller);
            case BOOKING_READ -> service.getById(id, caller);
            case CUSTOMER_CONFIRM -> service.confirmCustomer(id, caller);
            case PROVIDER_CONFIRM -> service.confirmServiceProvider(id, caller);
            case PAY -> service.pay(id, caller);
        }
    }
}

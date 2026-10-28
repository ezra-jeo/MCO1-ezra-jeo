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
import com.kingpay.security.CurrentUser;
import com.kingpay.repository.UserRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.entity.TransferRecord;
import jakarta.persistence.EntityManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/** P2-A regressions: implemented transfer/history authorization and guarded future booking operations. */
class PhaseTwoAuthorizationTest {
    enum Action { TRANSFER, HISTORY, BOOKING_READ, CUSTOMER_CONFIRM, PROVIDER_CONFIRM, PAY }
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final OwnershipGuard guard = new OwnershipGuard(accounts, bookings);
    private final UserRepository users = mock(UserRepository.class);
    private final TransferRecordRepository ledger = mock(TransferRecordRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final TransferService transfers = new TransferService(accounts, ledger, new CurrentUser(users, entities), entities);
    private final BookingService service = new BookingService(accounts, bookings, new CurrentUser(users, entities), entities);

    /** Preconditions: mocks initialized. Postconditions: trusted test principals resolve persisted synthetic IDs. */
    @BeforeEach
    void identities() {
        for (String name : new String[] {"customer", "provider", "outsider"}) {
            User user = new User(name, "hash", "Test", "0", "test@example.test", name.equals("provider"));
            ReflectionTestUtils.setField(user, "id", name.equals("customer") ? 1L : name.equals("provider") ? 2L : 3L);
            when(users.findByUsername(name)).thenReturn(Optional.of(user));
        }
        when(ledger.saveAndFlush(any(TransferRecord.class))).thenAnswer(invocation -> {
            TransferRecord record = invocation.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 100L);
            return record;
        });
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /** Preconditions: real owner/assigned actor calls. Postconditions: transfer/history succeed or booking guard reaches placeholder.
     * @param action protected operation */
    @ParameterizedTest @EnumSource(Action.class)
    void allowedActorReachesPlaceholder(Action action) {
        seed();
        String actor = action == Action.PROVIDER_CONFIRM ? "provider" : "customer";
        if (action != Action.PAY) {
            assertDoesNotThrow(() -> invoke(action, 1L, () -> actor));
        } else {
            assertThrows(UnimplementedOperationException.class, () -> invoke(action, 1L, () -> actor));
        }
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
        Account customer = new Account(users.findByUsername("customer").orElseThrow(), BigDecimal.TEN);
        Account provider = new Account(users.findByUsername("provider").orElseThrow(), BigDecimal.ZERO);
        ReflectionTestUtils.setField(customer, "id", 1L);
        ReflectionTestUtils.setField(provider, "id", 2L);
        when(accounts.findById(1L)).thenReturn(Optional.of(customer));
        when(accounts.findById(2L)).thenReturn(Optional.of(provider));
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

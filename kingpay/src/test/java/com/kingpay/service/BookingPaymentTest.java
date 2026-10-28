package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InsufficientBalanceException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Payment coordination rules; committed money/rollback evidence belongs to BookingPaymentPersistenceTest. */
class BookingPaymentTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final TransferService transfers = mock(TransferService.class);
    private final BookingService service = new BookingService(accounts, bookings, new CurrentUser(users, entities), entities, transfers);
    private User customer;
    private User provider;
    private Booking booking;

    /** Preconditions: isolated mocks. Postconditions: reverse-ID owners and an unpaid quote are available. */
    @BeforeEach
    void fixtures() {
        customer = new User("customer", "hash", "Customer", "0", "c@example.test", false);
        provider = new User("provider", "hash", "Provider", "0", "p@example.test", true);
        ReflectionTestUtils.setField(customer, "id", 2L);
        ReflectionTestUtils.setField(provider, "id", 1L);
        Account source = new Account(customer, BigDecimal.TEN);
        Account destination = new Account(provider, BigDecimal.ZERO);
        ReflectionTestUtils.setField(source, "id", 20L);
        ReflectionTestUtils.setField(destination, "id", 10L);
        booking = new Booking(source, destination, new BigDecimal("10.00"));
        ReflectionTestUtils.setField(booking, "id", 30L);
        booking.confirmCustomer();
        booking.confirmServiceProvider();
        when(users.findByUsername("customer")).thenReturn(Optional.of(customer));
        when(bookings.findById(30L)).thenReturn(Optional.of(booking));
        when(bookings.saveAndFlush(booking)).thenReturn(booking);
    }

    /** Preconditions: both confirmed; exact price boundary. Postconditions: fixed transfer command/link and ordered locks.
     * @param price ordinary, minimum or maximum quote */
    @ParameterizedTest @ValueSource(strings = {"10.00", "0.01", "99999999999999999.99"})
    void paymentUsesOnlyFixedBookingValues(String price) {
        ReflectionTestUtils.setField(booking, "amount", new BigDecimal(price));
        TransferRecord record = successfulTransfer();
        var result = service.pay(30L, () -> "customer");
        assertThat(result.status()).isEqualTo(BookingStatus.ConfirmedPaid);
        assertThat(result.paymentTransferId()).isEqualTo(50L);
        assertThat(booking.getPaymentTransfer()).isSameAs(record);
        var ordered = inOrder(entities, transfers, bookings);
        ordered.verify(entities).refresh(provider, LockModeType.PESSIMISTIC_WRITE);
        ordered.verify(entities).refresh(customer, LockModeType.PESSIMISTIC_WRITE);
        ordered.verify(entities).refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        ordered.verify(transfers).transferMoney(eq(new TransferRequest(20L, 10L, new BigDecimal(price))), any());
        ordered.verify(bookings).saveAndFlush(booking);
    }

    /** Preconditions: one or neither confirmation present. Postconditions: conflict without transfer or booking save.
     * @param state nonpayable status */
    @ParameterizedTest @EnumSource(value = BookingStatus.class, names = {"Unconfirmed", "CustomerConfirmed", "ServiceProviderConfirmed"})
    void incompleteBookingCannotPay(BookingStatus state) {
        ReflectionTestUtils.setField(booking, "status", state);
        assertThrows(BusinessConflictException.class, () -> service.pay(30L, () -> "customer"));
        verifyNoInteractions(transfers);
        verify(bookings, never()).saveAndFlush(any());
        assertThat(booking.getStatus()).isEqualTo(state);
    }

    /** Preconditions: paid job. Postconditions: same link returned, no transfer or save. */
    @Test
    void paidRetryReturnsOriginalResult() {
        booking.markPaid(record());
        var result = service.pay(30L, () -> "customer");
        assertThat(result.paymentTransferId()).isEqualTo(50L);
        verifyNoInteractions(transfers);
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: stale unpaid entity refreshes to paid. Postconditions: refresh prevents a second transfer. */
    @Test
    void paymentRechecksStatusAfterWaiting() {
        doAnswer(call -> { booking.markPaid(record()); return null; }).when(entities).refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        assertThat(service.pay(30L, () -> "customer").status()).isEqualTo(BookingStatus.ConfirmedPaid);
        verifyNoInteractions(transfers);
    }

    /** Preconditions: customer deactivates before lock acquisition. Postconditions: no transfer. */
    @Test
    void paymentRechecksCustomerActivity() {
        doAnswer(call -> { customer.deactivate(); return null; }).when(entities).refresh(customer, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(AccessDeniedException.class, () -> service.pay(30L, () -> "customer"));
        verifyNoInteractions(transfers);
    }

    /** Preconditions: transfer rejects funds. Postconditions: unpaid/link-null remains retryable. */
    @Test
    void insufficientFundsDoNotMarkPaid() {
        when(transfers.transferMoney(any(), any())).thenThrow(new InsufficientBalanceException("Debit"));
        assertThrows(InsufficientBalanceException.class, () -> service.pay(30L, () -> "customer"));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.ConfirmedUnpaid);
        assertThat(booking.getPaymentTransfer()).isNull();
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: transfer succeeded but booking flush fails. Postconditions: failure propagates to outer transaction. */
    @Test
    void bookingSaveFailurePropagates() {
        successfulTransfer();
        when(bookings.saveAndFlush(booking)).thenThrow(new IllegalStateException("test failure"));
        assertThrows(IllegalStateException.class, () -> service.pay(30L, () -> "customer"));
        verify(transfers).transferMoney(any(), any());
    }

    /** Preconditions: fixed persisted party IDs. Postconditions: synthetic matching ledger row returned.
     * @return ledger fixture */
    private TransferRecord record() {
        TransferRecord record = new TransferRecord(booking.getCustomer(), booking.getServiceProvider(), booking.getAmount());
        ReflectionTestUtils.setField(record, "id", 50L);
        return record;
    }

    /** Preconditions: unpaid fixture. Postconditions: transfer adapter returns a matching managed record.
     * @return expected linked record */
    private TransferRecord successfulTransfer() {
        TransferRecord record = record();
        when(transfers.transferMoney(any(), any())).thenReturn(new TransferResponse(50L, 20L, 10L, booking.getAmount(), record.getTransferDate()));
        when(entities.getReference(TransferRecord.class, 50L)).thenReturn(record);
        return record;
    }
}

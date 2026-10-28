package com.kingpay.service;

import com.kingpay.dto.request.CreateBookingRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.exception.UnimplementedOperationException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** P5-U: isolated booking rules; confirmation and creation never write balances or ledger entries. */
class BookingServiceTest {
    enum Operation { CREATE, READ, LIST, CUSTOMER_CONFIRM, PROVIDER_CONFIRM, PAY }
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final BookingService service = new BookingService(accounts, bookings, new CurrentUser(users, entities), entities);
    private User customer;
    private User provider;
    private User outsider;
    private Account customerAccount;
    private Account providerAccount;
    private Booking booking;

    /** Preconditions: mocks initialized. Postconditions: owners/IDs arranged opposite to customer/provider order. */
    @BeforeEach
    void fixtures() {
        customer = user("customer", 2L, false);
        provider = user("provider", 1L, true);
        outsider = user("outsider", 3L, false);
        customerAccount = new Account(customer, BigDecimal.ZERO);
        providerAccount = new Account(provider, BigDecimal.ZERO);
        ReflectionTestUtils.setField(customerAccount, "id", 20L);
        ReflectionTestUtils.setField(providerAccount, "id", 10L);
        booking = new Booking(customerAccount, providerAccount, new BigDecimal("100.00"));
        ReflectionTestUtils.setField(booking, "id", 30L);
        when(users.findByUsername("customer")).thenReturn(Optional.of(customer));
        when(users.findByUsername("provider")).thenReturn(Optional.of(provider));
        when(users.findByUsername("outsider")).thenReturn(Optional.of(outsider));
        when(accounts.findByUser_Id(2L)).thenReturn(Optional.of(customerAccount));
        when(accounts.findById(10L)).thenReturn(Optional.of(providerAccount));
        when(accounts.findById(20L)).thenReturn(Optional.of(customerAccount));
        when(bookings.findById(30L)).thenReturn(Optional.of(booking));
        when(bookings.saveAndFlush(any(Booking.class))).thenAnswer(call -> {
            Booking saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 30L);
            return saved;
        });
    }

    /** Preconditions: active distinct parties with zero funds. Postconditions: exact fixed quote saved without funding.
     * @param price ordinary, minimum, or maximum representable positive quote */
    @ParameterizedTest @ValueSource(strings = {"100.00", "0.01", "99999999999999999.99"})
    void createStoresFixedPartiesAndPriceWithoutMoneyMovement(String price) {
        var result = service.create(new CreateBookingRequest(10L, new BigDecimal(price)), () -> "customer");
        assertThat(result.id()).isEqualTo(30L);
        assertThat(result.customerAccountId()).isEqualTo(20L);
        assertThat(result.serviceProviderAccountId()).isEqualTo(10L);
        assertThat(result.amount()).isEqualByComparingTo(price);
        assertThat(result.status()).isEqualTo(BookingStatus.Unconfirmed);
        assertThat(result.paymentTransferId()).isNull();
        assertThat(result.createdAt()).isNotNull();
        verify(accounts).findByUser_Id(2L);
        verify(accounts, never()).findByUser_Username(anyString());
        var order = inOrder(entities);
        order.verify(entities).refresh(provider, LockModeType.PESSIMISTIC_WRITE);
        order.verify(entities).refresh(customer, LockModeType.PESSIMISTIC_WRITE);
        noMoneyMovement();
    }

    /** Preconditions: invalid create request. Postconditions: no lookup, insert or financial effect.
     * @param id documented case ID
     * @param request malformed booking shape */
    @ParameterizedTest(name = "{0}") @MethodSource("invalidRequests")
    void invalidCreationFailsBeforePersistence(String id, CreateBookingRequest request) {
        assertThrows(InvalidRequestException.class, () -> service.create(request, () -> "customer"));
        verifyNoInteractions(users, accounts, bookings, entities);
    }

    /** Preconditions: customer has no wallet. Postconditions: missing-resource failure without booking insert. */
    @Test
    void accountlessCustomerCannotBook() {
        when(accounts.findByUser_Id(2L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.create(request(), () -> "customer"));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: provider account absent. Postconditions: missing-resource failure, no financial effects. */
    @Test
    void missingProviderCannotBeBooked() {
        when(accounts.findById(10L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.create(request(), () -> "customer"));
        noMoneyMovement();
    }

    /** Preconditions: proposed provider is customer. Postconditions: self-booking rejected before insert. */
    @Test
    void selfBookingIsRejected() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(new CreateBookingRequest(20L, BigDecimal.TEN), () -> "customer"));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: provider lacks eligibility. Postconditions: conflict, no booking or financial writes. */
    @Test
    void nonHeroProviderIsRejected() {
        provider.setSuperhero(false);
        assertThrows(BusinessConflictException.class, () -> service.create(request(), () -> "customer"));
        verify(bookings, never()).saveAndFlush(any());
        noMoneyMovement();
    }

    /** Preconditions: provider inactive. Postconditions: conflict without insert. */
    @Test
    void inactiveProviderIsRejected() {
        provider.deactivate();
        assertThrows(BusinessConflictException.class, () -> service.create(request(), () -> "customer"));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: customer deactivates while create waits. Postconditions: refreshed activity prevents insert. */
    @Test
    void customerActivityIsCheckedAfterLock() {
        doAnswer(call -> { customer.deactivate(); return null; }).when(entities).refresh(customer, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(AccessDeniedException.class, () -> service.create(request(), () -> "customer"));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: eligibility removed while create waits. Postconditions: refreshed provider state prevents insert. */
    @Test
    void providerEligibilityIsCheckedAfterLock() {
        doAnswer(call -> { provider.setSuperhero(false); return null; }).when(entities).refresh(provider, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(BusinessConflictException.class, () -> service.create(request(), () -> "customer"));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: confirmation actor deactivates while waiting. Postconditions: refresh rejects without status change.
     * @param asCustomer selected actor */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void confirmationRechecksActivityAfterLock(boolean asCustomer) {
        User actor = asCustomer ? customer : provider;
        doAnswer(call -> { actor.deactivate(); return null; }).when(entities).refresh(actor, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(AccessDeniedException.class, () -> {
            if (asCustomer) { service.confirmCustomer(30L, () -> "customer"); }
            else { service.confirmServiceProvider(30L, () -> "provider"); }
        });
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.Unconfirmed);
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: either participant reads. Postconditions: same immutable-party DTO, no writes.
     * @param actor customer or provider username */
    @ParameterizedTest @ValueSource(strings = {"customer", "provider"})
    void eitherParticipantCanRead(String actor) {
        var result = service.getById(30L, () -> actor);
        assertThat(result.customerAccountId()).isEqualTo(20L);
        assertThat(result.serviceProviderAccountId()).isEqualTo(10L);
        noMoneyMovement();
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: caller participates in both roles across different jobs. Postconditions: ordered DTOs retained. */
    @Test
    void listUsesStableUserIdAndMapsBothRoles() {
        Booking reverse = new Booking(providerAccount, customerAccount, BigDecimal.ONE);
        ReflectionTestUtils.setField(reverse, "id", 31L);
        when(bookings.findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(2L, 2L))
                .thenReturn(List.of(reverse, booking));
        assertThat(service.getMine(() -> "customer")).extracting("id").containsExactly(31L, 30L);
        verify(bookings).findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(2L, 2L);
        noMoneyMovement();
    }

    /** Preconditions: no participant bookings. Postconditions: empty list, no inserts. */
    @Test
    void listWithoutBookingsIsEmpty() {
        assertThat(service.getMine(() -> "customer")).isEmpty();
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: one of five states and assigned actor. Postconditions: monotone/idempotent transition with no money.
     * @param asCustomer selected confirmation role
     * @param initial initial persisted state
     * @param expected resulting state */
    @ParameterizedTest @MethodSource("confirmationCases")
    void confirmationTransitionsAreIdempotent(boolean asCustomer, BookingStatus initial, BookingStatus expected) {
        ReflectionTestUtils.setField(booking, "status", initial);
        if (initial == BookingStatus.ConfirmedPaid) {
            TransferRecord paid = new TransferRecord(customerAccount, providerAccount, booking.getAmount());
            ReflectionTestUtils.setField(paid, "id", 50L);
            ReflectionTestUtils.setField(booking, "paymentTransfer", paid);
        }
        var result = asCustomer ? service.confirmCustomer(30L, () -> "customer") : service.confirmServiceProvider(30L, () -> "provider");
        assertThat(result.status()).isEqualTo(expected);
        assertThat(result.amount()).isEqualByComparingTo("100.00");
        assertThat(result.paymentTransferId()).isEqualTo(initial == BookingStatus.ConfirmedPaid ? 50L : null);
        var locks = inOrder(entities);
        locks.verify(entities).refresh(provider, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entities).refresh(customer, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entities).refresh(booking, LockModeType.PESSIMISTIC_WRITE);
        if (initial == expected) { verify(bookings, never()).saveAndFlush(any()); }
        else { verify(bookings).saveAndFlush(booking); }
        noMoneyMovement();
    }

    /** Preconditions: participant invokes the other role's action. Postconditions: forbidden, booking unchanged.
     * @param asCustomer which route is requested */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void participantsCannotConfirmTheOtherSide(boolean asCustomer) {
        assertThrows(AccessDeniedException.class, () -> {
            if (asCustomer) { service.confirmCustomer(30L, () -> "provider"); }
            else { service.confirmServiceProvider(30L, () -> "customer"); }
        });
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.Unconfirmed);
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: unrelated authenticated caller. Postconditions: read/confirmation/payment denied.
     * @param operation protected identifier operation */
    @ParameterizedTest @EnumSource(value = Operation.class, names = {"READ", "CUSTOMER_CONFIRM", "PROVIDER_CONFIRM", "PAY"})
    void outsiderCannotAccessBooking(Operation operation) {
        assertThrows(AccessDeniedException.class, () -> invoke(operation, () -> "outsider", 30L));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: no principal. Postconditions: each public method requires authentication.
     * @param operation selected operation */
    @ParameterizedTest @EnumSource(Operation.class)
    void absentPrincipalFails(Operation operation) {
        assertThrows(InsufficientAuthenticationException.class, () -> invoke(operation, null, 30L));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: caller row absent. Postconditions: missing identity cannot access or mutate bookings.
     * @param operation selected operation */
    @ParameterizedTest @EnumSource(Operation.class)
    void missingIdentityFails(Operation operation) {
        assertThrows(ResourceNotFoundException.class, () -> invoke(operation, () -> "absent", 30L));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: caller inactive. Postconditions: every direct service operation denied.
     * @param operation selected operation */
    @ParameterizedTest @EnumSource(Operation.class)
    void inactiveIdentityFails(Operation operation) {
        customer.deactivate();
        assertThrows(AccessDeniedException.class, () -> invoke(operation, () -> "customer", 30L));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: booking absent. Postconditions: resource error before updates.
     * @param operation selected identifier operation */
    @ParameterizedTest @EnumSource(value = Operation.class, names = {"READ", "CUSTOMER_CONFIRM", "PROVIDER_CONFIRM", "PAY"})
    void missingBookingFails(Operation operation) {
        assertThrows(ResourceNotFoundException.class, () -> invoke(operation, () -> "customer", 99L));
        verify(bookings, never()).saveAndFlush(any());
    }

    /** Preconditions: null or nonpositive ID. Postconditions: all identifier methods reject before booking lookup.
     * @param operation selected method
     * @param id malformed ID */
    @ParameterizedTest @MethodSource("invalidIds")
    void invalidBookingIdsFail(Operation operation, Long id) {
        assertThrows(InvalidRequestException.class, () -> invoke(operation, () -> "customer", id));
        verifyNoInteractions(bookings);
    }

    /** Preconditions: active customer owns booking. Postconditions: payment remains explicit 501 placeholder. */
    @Test
    void paymentRemainsPhaseSix() {
        assertThrows(UnimplementedOperationException.class, () -> service.pay(30L, () -> "customer"));
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.Unconfirmed);
        noMoneyMovement();
    }

    /** Preconditions: synthetic values. Postconditions: persisted-ID identity created without database.
     * @param name username
     * @param id user ID
     * @param hero provider eligibility
     * @return fixture user */
    private User user(String name, Long id, boolean hero) {
        User user = new User(name, "hash", "Test", "0", "test@example.test", hero);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** Preconditions: none. Postconditions: valid fixture request.
     * @return provider account and quote */
    private CreateBookingRequest request() { return new CreateBookingRequest(10L, new BigDecimal("100.00")); }

    /** Preconditions: fixtures initialized. Postconditions: balances stay zero and account repository is never written. */
    private void noMoneyMovement() {
        assertThat(customerAccount.getBalance()).isEqualByComparingTo("0");
        assertThat(providerAccount.getBalance()).isEqualByComparingTo("0");
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: fixture collaborators initialized. Postconditions: dispatches one public method.
     * @param operation method to call
     * @param caller trusted principal
     * @param id booking ID */
    private void invoke(Operation operation, Principal caller, Long id) {
        switch (operation) {
            case CREATE -> service.create(request(), caller);
            case READ -> service.getById(id, caller);
            case LIST -> service.getMine(caller);
            case CUSTOMER_CONFIRM -> service.confirmCustomer(id, caller);
            case PROVIDER_CONFIRM -> service.confirmServiceProvider(id, caller);
            case PAY -> service.pay(id, caller);
        }
    }

    /** Preconditions: none. Postconditions: all ten actor/state combinations supplied.
     * @return initial and resulting confirmation states */
    private static Stream<Arguments> confirmationCases() {
        return Stream.of(true, false).flatMap(customer -> Stream.of(BookingStatus.values()).map(initial -> {
            BookingStatus expected = initial;
            if (initial == BookingStatus.Unconfirmed) { expected = customer ? BookingStatus.CustomerConfirmed : BookingStatus.ServiceProviderConfirmed; }
            if (customer && initial == BookingStatus.ServiceProviderConfirmed || !customer && initial == BookingStatus.CustomerConfirmed) { expected = BookingStatus.ConfirmedUnpaid; }
            return Arguments.of(customer, initial, expected);
        }));
    }

    /** Preconditions: none. Postconditions: invalid syntax/range matrix supplied.
     * @return nine invalid create cases */
    private static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("P5-U02a", null),
                Arguments.of("P5-U02b", new CreateBookingRequest(null, BigDecimal.ONE)),
                Arguments.of("P5-U02c", new CreateBookingRequest(0L, BigDecimal.ONE)),
                Arguments.of("P5-U02d", new CreateBookingRequest(-1L, BigDecimal.ONE)),
                Arguments.of("P5-U02e", new CreateBookingRequest(10L, null)),
                Arguments.of("P5-U02f", new CreateBookingRequest(10L, BigDecimal.ZERO)),
                Arguments.of("P5-U02g", new CreateBookingRequest(10L, BigDecimal.ONE.negate())),
                Arguments.of("P5-U02h", new CreateBookingRequest(10L, new BigDecimal("1.001"))),
                Arguments.of("P5-U02i", new CreateBookingRequest(10L, new BigDecimal("1E+17"))));
    }

    /** Preconditions: none. Postconditions: four identifier methods receive three invalid-ID cases each.
     * @return twelve operation/ID combinations */
    private static Stream<Arguments> invalidIds() {
        return Stream.of(Operation.READ, Operation.CUSTOMER_CONFIRM, Operation.PROVIDER_CONFIRM, Operation.PAY)
                .flatMap(operation -> Stream.of(null, 0L, -1L).map(id -> Arguments.of(operation, id)));
    }
}

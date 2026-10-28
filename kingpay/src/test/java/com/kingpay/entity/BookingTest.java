package com.kingpay.entity;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Isolated booking behavior; case inputs and outcomes are in test-cases/booking.md.
 * Reflection supplies database-style account IDs without changing production entities.
 */
class BookingTest {

    private static final long CUSTOMER_ACCOUNT_ID = 1_000L;
    private static final long PROVIDER_ACCOUNT_ID = 2_000L;
    private static final long OTHER_ACCOUNT_ID = 3_000L;
    private static final String CUSTOMER_BALANCE = "1000.00";
    private static final String PROVIDER_BALANCE = "50.00";
    private static final String BOOKING_PRICE = "100.00";

    /**
     * Verifies BKG-C01 through BKG-C05 across every valid starting state.
     * <p>Preconditions: the fixture has the specified status and initialized accounts.
     * <p>Postconditions: status matches the expected transition; payment and money are unchanged.
     * @param caseId documented case identifier
     * @param initialStatus state before customer confirmation
     * @param expectedStatus state after customer confirmation
     */
    @ParameterizedTest(name = "{0}: customer confirms {1} -> {2}")
    @MethodSource("customerConfirmationCases")
    void customerConfirmationPreservesProgress(String caseId, BookingStatus initialStatus,
            BookingStatus expectedStatus) {
        BookingFixture fixture = fixtureAt(initialStatus);
        TransferRecord originalPayment = fixture.booking().getPaymentTransfer();

        fixture.booking().confirmCustomer();

        assertThat(fixture.booking().getStatus()).isEqualTo(expectedStatus);
        assertThat(fixture.booking().getPaymentTransfer()).isSameAs(originalPayment);
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-S01 through BKG-S05 across every valid starting state.
     * <p>Preconditions: the fixture has the specified status and initialized accounts.
     * <p>Postconditions: status matches the expected transition; payment and money are unchanged.
     * @param caseId documented case identifier
     * @param initialStatus state before provider confirmation
     * @param expectedStatus state after provider confirmation
     */
    @ParameterizedTest(name = "{0}: provider confirms {1} -> {2}")
    @MethodSource("providerConfirmationCases")
    void providerConfirmationPreservesProgress(String caseId, BookingStatus initialStatus,
            BookingStatus expectedStatus) {
        BookingFixture fixture = fixtureAt(initialStatus);
        TransferRecord originalPayment = fixture.booking().getPaymentTransfer();

        fixture.booking().confirmServiceProvider();

        assertThat(fixture.booking().getStatus()).isEqualTo(expectedStatus);
        assertThat(fixture.booking().getPaymentTransfer()).isSameAs(originalPayment);
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-P01: link a matching transfer after both confirmations.
     * <p>Preconditions: booking is ConfirmedUnpaid; transfer matches its parties and price.
     * <p>Postconditions: status is ConfirmedPaid and the exact transfer is linked; money is unchanged.
     */
    @Test
    @DisplayName("BKG-P01: link a matching payment after both confirmations")
    void matchingPaymentMarksBookingPaid() {
        BookingFixture fixture = fixtureAt(BookingStatus.ConfirmedUnpaid);
        TransferRecord transfer = new TransferRecord(fixture.customer(), fixture.provider(),
                new BigDecimal(BOOKING_PRICE));

        fixture.booking().markPaid(transfer);

        assertThat(fixture.booking().getStatus()).isEqualTo(BookingStatus.ConfirmedPaid);
        assertThat(fixture.booking().getPaymentTransfer()).isSameAs(transfer);
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-P02: persistent account identity is independent of object identity.
     * <p>Preconditions: transfer accounts are distinct objects with the booking accounts' IDs.
     * <p>Postconditions: payment is accepted and linked; the booking is ConfirmedPaid.
     */
    @Test
    @DisplayName("BKG-P02: accept different account objects representing the same IDs")
    void matchingAccountIdsAcceptDistinctObjects() {
        BookingFixture fixture = fixtureAt(BookingStatus.ConfirmedUnpaid);
        TransferRecord transfer = transferWith(CUSTOMER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, BOOKING_PRICE);
        assertThat(transfer.getSourceAccount()).isNotSameAs(fixture.customer());
        assertThat(transfer.getDestinationAccount()).isNotSameAs(fixture.provider());

        fixture.booking().markPaid(transfer);

        assertThat(fixture.booking().getStatus()).isEqualTo(BookingStatus.ConfirmedPaid);
        assertThat(fixture.booking().getPaymentTransfer()).isSameAs(transfer);
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-P03: equivalent prices with different scales match numerically.
     * <p>Preconditions: booking amount is 100.00 and matching transfer amount is 100.0.
     * <p>Postconditions: payment is accepted and linked; money is unchanged.
     */
    @Test
    @DisplayName("BKG-P03: accept numerically equal payment amounts with different scales")
    void matchingAmountIgnoresDecimalScale() {
        BookingFixture fixture = fixtureAt(BookingStatus.ConfirmedUnpaid);
        TransferRecord transfer = transferWith(CUSTOMER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, "100.0");

        fixture.booking().markPaid(transfer);

        assertThat(fixture.booking().getStatus()).isEqualTo(BookingStatus.ConfirmedPaid);
        assertThat(fixture.booking().getPaymentTransfer()).isSameAs(transfer);
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-P04 through BKG-P08 for invalid or mismatched transfer inputs.
     * <p>Preconditions: booking is ConfirmedUnpaid with no linked payment.
     * <p>Postconditions: IllegalArgumentException is thrown; status, payment, and money are unchanged.
     * @param caseId documented case identifier
     * @param transfer null or mismatched payment candidate
     */
    @ParameterizedTest(name = "{0}: reject invalid payment without changing the booking")
    @MethodSource("invalidPaymentCases")
    void invalidPaymentLeavesBookingUnchanged(String caseId, TransferRecord transfer) {
        BookingFixture fixture = fixtureAt(BookingStatus.ConfirmedUnpaid);

        assertThrows(IllegalArgumentException.class, () -> fixture.booking().markPaid(transfer));

        assertThat(fixture.booking().getStatus()).isEqualTo(BookingStatus.ConfirmedUnpaid);
        assertThat(fixture.booking().getPaymentTransfer()).isNull();
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-P09 through BKG-P11 before both participants have confirmed.
     * <p>Preconditions: transfer matches; booking has zero or one confirmation.
     * <p>Postconditions: IllegalArgumentException is thrown; starting state and money remain unchanged.
     * @param caseId documented case identifier
     * @param initialStatus incomplete confirmation state
     */
    @ParameterizedTest(name = "{0}: reject payment in {1}")
    @MethodSource("unreadyPaymentCases")
    void incompleteConfirmationRejectsPayment(String caseId, BookingStatus initialStatus) {
        BookingFixture fixture = fixtureAt(initialStatus);
        TransferRecord transfer = transferWith(CUSTOMER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, BOOKING_PRICE);

        assertThrows(IllegalArgumentException.class, () -> fixture.booking().markPaid(transfer));

        assertThat(fixture.booking().getStatus()).isEqualTo(initialStatus);
        assertThat(fixture.booking().getPaymentTransfer()).isNull();
        assertBalancesUnchanged(fixture);
    }

    /**
     * Verifies BKG-P12: an already paid booking retains its original transfer.
     * <p>Preconditions: booking is ConfirmedPaid and a second matching transfer is supplied.
     * <p>Postconditions: IllegalArgumentException is thrown; original payment, status, and money remain.
     */
    @Test
    @DisplayName("BKG-P12: reject a second payment and retain the original transfer")
    void duplicatePaymentPreservesOriginalTransfer() {
        BookingFixture fixture = fixtureAt(BookingStatus.ConfirmedPaid);
        TransferRecord originalPayment = fixture.booking().getPaymentTransfer();
        TransferRecord replacement = transferWith(CUSTOMER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, BOOKING_PRICE);

        assertThrows(IllegalArgumentException.class, () -> fixture.booking().markPaid(replacement));

        assertThat(fixture.booking().getStatus()).isEqualTo(BookingStatus.ConfirmedPaid);
        assertThat(fixture.booking().getPaymentTransfer()).isSameAs(originalPayment);
        assertBalancesUnchanged(fixture);
    }

    /**
     * Supplies the five customer-confirmation state transitions.
     * <p>Preconditions: none.
     * <p>Postconditions: each case defines an independent initial and expected state.
     * @return customer confirmation cases and their documentation IDs
     */
    private static Stream<Arguments> customerConfirmationCases() {
        return Stream.of(
                Arguments.of("BKG-C01", BookingStatus.Unconfirmed, BookingStatus.CustomerConfirmed),
                Arguments.of("BKG-C02", BookingStatus.ServiceProviderConfirmed, BookingStatus.ConfirmedUnpaid),
                Arguments.of("BKG-C03", BookingStatus.CustomerConfirmed, BookingStatus.CustomerConfirmed),
                Arguments.of("BKG-C04", BookingStatus.ConfirmedUnpaid, BookingStatus.ConfirmedUnpaid),
                Arguments.of("BKG-C05", BookingStatus.ConfirmedPaid, BookingStatus.ConfirmedPaid));
    }

    /**
     * Supplies the five provider-confirmation state transitions.
     * <p>Preconditions: none.
     * <p>Postconditions: each case defines an independent initial and expected state.
     * @return provider confirmation cases and their documentation IDs
     */
    private static Stream<Arguments> providerConfirmationCases() {
        return Stream.of(
                Arguments.of("BKG-S01", BookingStatus.Unconfirmed, BookingStatus.ServiceProviderConfirmed),
                Arguments.of("BKG-S02", BookingStatus.CustomerConfirmed, BookingStatus.ConfirmedUnpaid),
                Arguments.of("BKG-S03", BookingStatus.ServiceProviderConfirmed, BookingStatus.ServiceProviderConfirmed),
                Arguments.of("BKG-S04", BookingStatus.ConfirmedUnpaid, BookingStatus.ConfirmedUnpaid),
                Arguments.of("BKG-S05", BookingStatus.ConfirmedPaid, BookingStatus.ConfirmedPaid));
    }

    /**
     * Supplies invalid payments with otherwise initialized data.
     * <p>Preconditions: none.
     * <p>Postconditions: each case changes one payment requirement or supplies null.
     * @return invalid transfer cases and their documentation IDs
     */
    private static Stream<Arguments> invalidPaymentCases() {
        return Stream.of(
                Arguments.of("BKG-P04", (TransferRecord) null),
                Arguments.of("BKG-P05", transferWith(OTHER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, BOOKING_PRICE)),
                Arguments.of("BKG-P06", transferWith(CUSTOMER_ACCOUNT_ID, OTHER_ACCOUNT_ID, BOOKING_PRICE)),
                Arguments.of("BKG-P07", transferWith(CUSTOMER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, "99.99")),
                Arguments.of("BKG-P08", transferWith(CUSTOMER_ACCOUNT_ID, PROVIDER_ACCOUNT_ID, "100.01")));
    }

    /**
     * Supplies all valid booking states that lack both confirmations.
     * <p>Preconditions: none.
     * <p>Postconditions: each case identifies a payment-ineligible confirmation state.
     * @return incomplete confirmation states and their documentation IDs
     */
    private static Stream<Arguments> unreadyPaymentCases() {
        return Stream.of(
                Arguments.of("BKG-P09", BookingStatus.Unconfirmed),
                Arguments.of("BKG-P10", BookingStatus.CustomerConfirmed),
                Arguments.of("BKG-P11", BookingStatus.ServiceProviderConfirmed));
    }

    /**
     * Arranges a fresh booking using its public operations to reach a valid state.
     * <p>Preconditions: targetStatus is one of the five defined booking states.
     * <p>Postconditions: fixture status is verified; accounts retain their initial balances.
     * @param targetStatus valid state needed by the test
     * @return isolated accounts and booking
     */
    private static BookingFixture fixtureAt(BookingStatus targetStatus) {
        Account customer = accountWith(CUSTOMER_ACCOUNT_ID, CUSTOMER_BALANCE);
        Account provider = accountWith(PROVIDER_ACCOUNT_ID, PROVIDER_BALANCE);
        Booking booking = new Booking(customer, provider, new BigDecimal(BOOKING_PRICE));

        switch (targetStatus) {
            case Unconfirmed -> { }
            case CustomerConfirmed -> booking.confirmCustomer();
            case ServiceProviderConfirmed -> booking.confirmServiceProvider();
            case ConfirmedUnpaid, ConfirmedPaid -> {
                booking.confirmCustomer();
                booking.confirmServiceProvider();
                if (targetStatus == BookingStatus.ConfirmedPaid) {
                    booking.markPaid(new TransferRecord(customer, provider, new BigDecimal(BOOKING_PRICE)));
                }
            }
        }

        assertThat(booking.getStatus()).as("fixture must reach its requested starting state")
                .isEqualTo(targetStatus);
        return new BookingFixture(customer, provider, booking);
    }

    /**
     * Creates a real account with an existing-wallet balance and simulated database ID.
     * <p>Preconditions: id is positive and balance is a valid nonnegative decimal string.
     * <p>Postconditions: a new account exposes the supplied ID and balance without persistence.
     * @param id account identity supplied to the fixture
     * @param balance existing-wallet balance
     * @return initialized account fixture
     */
    private static Account accountWith(long id, String balance) {
        User owner = new User("owner-" + id, "test-only-hash", "Test Owner",
                "00000000000", "owner@example.test", false);
        Account account = new Account(owner, new BigDecimal(balance));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    /**
     * Creates a payment candidate from independently instantiated account fixtures.
     * <p>Preconditions: IDs are positive and amount is a non-null decimal string.
     * <p>Postconditions: a transfer references real accounts with the selected identities.
     * @param sourceId source account identity
     * @param destinationId destination account identity
     * @param amount transfer value
     * @return initialized transfer candidate, not a persisted financial operation
     */
    private static TransferRecord transferWith(long sourceId, long destinationId, String amount) {
        return new TransferRecord(accountWith(sourceId, CUSTOMER_BALANCE),
                accountWith(destinationId, PROVIDER_BALANCE), new BigDecimal(amount));
    }

    /**
     * Checks that booking operations leave money movement to the application service.
     * <p>Preconditions: fixture began with the defined customer/provider balances.
     * <p>Postconditions: assertions pass only when neither balance has changed.
     * @param fixture accounts whose balances must be preserved
     */
    private static void assertBalancesUnchanged(BookingFixture fixture) {
        assertThat(fixture.customer().getBalance()).isEqualByComparingTo(CUSTOMER_BALANCE);
        assertThat(fixture.provider().getBalance()).isEqualByComparingTo(PROVIDER_BALANCE);
    }

    /**
     * Groups real objects belonging to one isolated scenario.
     * @param customer booking customer account
     * @param provider assigned provider account
     * @param booking booking under test
     */
    private record BookingFixture(Account customer, Account provider, Booking booking) {
    }
}

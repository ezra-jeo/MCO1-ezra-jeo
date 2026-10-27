package com.kingpay.repository;

import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL query verification; each test's inserts are rolled back by Spring Test.
 * Documented cases are in docs/test-cases/repositories.md.
 */
@SpringBootTest
@Transactional
class RepositoryQueriesTest {

    private static final BigDecimal WALLET_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal BOOKING_AMOUNT = new BigDecimal("10.00");
    private static final Instant EARLIER = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T11:00:00Z");
    private static final Instant LATEST = Instant.parse("2026-01-01T12:00:00Z");

    private final UserRepository users;
    private final AccountRepository accounts;
    private final BookingRepository bookings;
    private final TransferRecordRepository transfers;
    private final EntityManager entityManager;
    private final String prefix = "repo_" + UUID.randomUUID().toString().replace("-", "") + "_";

    /**
     * Receives real persistence components from the test application context.
     * <p>Preconditions: MySQL is reachable and its mapped schema validates.
     * <p>Postconditions: tests can persist and query fixtures inside rollback-only test transactions.
     * @param users user repository
     * @param accounts account repository
     * @param bookings booking repository
     * @param transfers ledger repository
     * @param entityManager persistence context to clear before query assertions
     */
    @Autowired
    RepositoryQueriesTest(UserRepository users, AccountRepository accounts,
            BookingRepository bookings, TransferRecordRepository transfers, EntityManager entityManager) {
        this.users = users;
        this.accounts = accounts;
        this.bookings = bookings;
        this.transfers = transfers;
        this.entityManager = entityManager;
    }

    /**
     * Verifies REPO-U01: find a persisted active login identity.
     * <p>Preconditions: an active user has been flushed to MySQL.
     * <p>Postconditions: lookup returns that user's ID after the persistence context is cleared.
     */
    @Test
    @DisplayName("REPO-U01: find an active user by username")
    void usernameLookupFindsActiveUser() {
        User expected = saveUser("active", false, true);
        entityManager.clear();

        assertThat(users.findByUsername(expected.getUsername()).map(User::getId)).contains(expected.getId());
    }

    /**
     * Verifies REPO-U02: inactive identities remain available for service-level decisions.
     * <p>Preconditions: an inactive user has been flushed to MySQL.
     * <p>Postconditions: username lookup returns that user rather than silently excluding it.
     */
    @Test
    @DisplayName("REPO-U02: find an inactive identity for explicit authentication checks")
    void usernameLookupIncludesInactiveUser() {
        User expected = saveUser("inactive", false, false);
        entityManager.clear();

        assertThat(users.findByUsername(expected.getUsername()).map(User::getId)).contains(expected.getId());
    }

    /**
     * Verifies REPO-U03: a missing login identity produces an empty result.
     * <p>Preconditions: the uniquely prefixed username has no row.
     * <p>Postconditions: lookup returns Optional.empty.
     */
    @Test
    @DisplayName("REPO-U03: return empty for an unknown username")
    void usernameLookupReturnsEmptyWhenMissing() {
        assertThat(users.findByUsername(prefix + "missing")).isEmpty();
    }

    /**
     * Verifies REPO-U04: registration can distinguish existing and available names.
     * <p>Preconditions: one existing username and one unused username are selected.
     * <p>Postconditions: existence is true only for the stored name.
     */
    @Test
    @DisplayName("REPO-U04: distinguish existing and available usernames")
    void usernameExistenceDistinguishesAvailableNames() {
        User existing = saveUser("existing", false, true);
        entityManager.clear();

        assertThat(users.existsByUsername(existing.getUsername())).isTrue();
        assertThat(users.existsByUsername(prefix + "missing")).isFalse();
    }

    /**
     * Verifies REPO-U05: username update checks exclude the user's own row.
     * <p>Preconditions: two users exist and a third name is unused.
     * <p>Postconditions: self is allowed, another user's name conflicts, and an unused name is allowed.
     */
    @Test
    @DisplayName("REPO-U05: exclude self while detecting another user's username")
    void usernameUpdateCheckExcludesCurrentUser() {
        User current = saveUser("current", false, true);
        User other = saveUser("other", false, true);
        entityManager.clear();

        assertThat(users.existsByUsernameAndIdNot(current.getUsername(), current.getId())).isFalse();
        assertThat(users.existsByUsernameAndIdNot(other.getUsername(), current.getId())).isTrue();
        assertThat(users.existsByUsernameAndIdNot(prefix + "unused", current.getId())).isFalse();
    }

    /**
     * Verifies REPO-U06: browsing requires both eligibility and active status.
     * <p>Preconditions: two active heroes, an inactive hero, and an ordinary active user exist.
     * <p>Postconditions: only the two eligible fixture users appear, in username order.
     */
    @Test
    @DisplayName("REPO-U06: list active superheroes in username order")
    void superheroListRequiresEligibilityAndActiveStatus() {
        User zeta = saveUser("zeta", true, true);
        User alpha = saveUser("alpha", true, true);
        saveUser("inactive", true, false);
        saveUser("ordinary", false, true);
        entityManager.clear();

        List<User> matchingFixtures = users.findByIsSuperheroTrueAndActiveTrueOrderByUsernameAsc().stream()
                .filter(user -> user.getUsername().startsWith(prefix))
                .toList();

        assertThat(matchingFixtures).extracting(User::getId).containsExactly(alpha.getId(), zeta.getId());
    }

    /**
     * Verifies REPO-A01: nested owner-name lookup selects the intended account only.
     * <p>Preconditions: two users have separate persisted accounts; another name has none.
     * <p>Postconditions: each name resolves its own account and an absent name returns empty.
     */
    @Test
    @DisplayName("REPO-A01: resolve an account through its owner's username")
    void accountLookupFollowsOwnerUsername() {
        Account first = saveAccount("first");
        Account second = saveAccount("second");
        String firstUsername = first.getUser().getUsername();
        String secondUsername = second.getUser().getUsername();
        entityManager.clear();

        assertThat(accounts.findByUser_Username(firstUsername).map(Account::getId)).contains(first.getId());
        assertThat(accounts.findByUser_Username(secondUsername).map(Account::getId)).contains(second.getId());
        assertThat(accounts.findByUser_Username(prefix + "missing")).isEmpty();
    }

    /**
     * Verifies REPO-A02: duplicate-account checks follow the owner's database ID.
     * <p>Preconditions: one user gains an account; another user remains without one.
     * <p>Postconditions: the check changes from false to true only for the account owner.
     */
    @Test
    @DisplayName("REPO-A02: detect whether a user already owns an account")
    void accountExistenceFollowsOwnerId() {
        User owner = saveUser("owner", false, true);
        User other = saveUser("other", false, true);
        assertThat(accounts.existsByUser_Id(owner.getId())).isFalse();
        accounts.saveAndFlush(new Account(owner, WALLET_BALANCE));
        entityManager.clear();

        assertThat(accounts.existsByUser_Id(owner.getId())).isTrue();
        assertThat(accounts.existsByUser_Id(other.getId())).isFalse();
    }

    /**
     * Verifies REPO-T01: history includes both directions, excludes unrelated rows, and orders ties.
     * <p>Preconditions: three matching transfers and one unrelated transfer have controlled timestamps.
     * <p>Postconditions: matching rows appear newest first with descending-ID ties.
     */
    @Test
    @DisplayName("REPO-T01: return incoming/outgoing history with deterministic ordering")
    void transferHistoryIncludesBothDirectionsAndOrdersTies() {
        Account caller = saveAccount("caller");
        Account other = saveAccount("other");
        Account third = saveAccount("third");
        TransferRecord earlyOutgoing = saveTransfer(caller, other, EARLIER);
        TransferRecord laterIncoming = saveTransfer(other, caller, LATER);
        TransferRecord tiedOutgoing = saveTransfer(caller, third, LATER);
        saveTransfer(other, third, LATEST);
        entityManager.clear();

        List<TransferRecord> history = transfers
                .findBySourceAccount_IdOrDestinationAccount_IdOrderByTransferDateDescIdDesc(
                        caller.getId(), caller.getId());

        assertThat(history).extracting(TransferRecord::getId)
                .containsExactly(tiedOutgoing.getId(), laterIncoming.getId(), earlyOutgoing.getId());
    }

    /**
     * Verifies REPO-B01: participant lists include both roles and exclude unrelated bookings.
     * <p>Preconditions: two tied matching bookings and a newer unrelated booking exist.
     * <p>Postconditions: only participant bookings appear, ordered by descending timestamp and ID.
     */
    @Test
    @DisplayName("REPO-B01: list a user's customer/provider bookings only")
    void bookingListIncludesBothParticipantRoles() {
        Account caller = saveAccount("caller");
        Account other = saveAccount("other");
        Account third = saveAccount("third");
        Booking asCustomer = saveBooking(caller, other, BookingStatus.Unconfirmed, LATER);
        Booking asProvider = saveBooking(third, caller, BookingStatus.Unconfirmed, LATER);
        saveBooking(other, third, BookingStatus.Unconfirmed, LATEST);
        String username = caller.getUser().getUsername();
        entityManager.clear();

        List<Booking> result = bookings
                .findByCustomer_User_UsernameOrServiceProvider_User_UsernameOrderByCreatedAtDescIdDesc(
                        username, username);

        assertThat(result).extracting(Booking::getId).containsExactly(asProvider.getId(), asCustomer.getId());
    }

    /**
     * Verifies REPO-B02 through REPO-B09 for every outstanding state in both roles.
     * <p>Preconditions: caller participates in one non-paid booking in the selected role.
     * <p>Postconditions: the outstanding-booking query returns true.
     * @param caseId documented test case identifier
     * @param status unfinished/unpaid booking state
     * @param asCustomer whether the caller occupies the customer role
     */
    @ParameterizedTest(name = "{0}: outstanding {1}, asCustomer={2}")
    @MethodSource("outstandingBookingCases")
    void outstandingBookingBlocksEitherParticipantRole(String caseId, BookingStatus status,
            boolean asCustomer) {
        Account caller = saveAccount("caller");
        Account other = saveAccount("other");
        saveBooking(asCustomer ? caller : other, asCustomer ? other : caller, status, EARLIER);
        entityManager.clear();

        assertThat(hasOutstandingBooking(caller.getId())).isTrue();
    }

    /**
     * Verifies REPO-B10: no bookings means no outstanding work.
     * <p>Preconditions: caller has a persisted account and no booking references it.
     * <p>Postconditions: the outstanding-booking query returns false.
     */
    @Test
    @DisplayName("REPO-B10: allow the outstanding-work check when no bookings exist")
    void noBookingsMeansNoOutstandingWork() {
        Account caller = saveAccount("caller");
        entityManager.clear();

        assertThat(hasOutstandingBooking(caller.getId())).isFalse();
    }

    /**
     * Verifies REPO-B11: paid bookings in either role are excluded from outstanding work.
     * <p>Preconditions: caller has a paid customer booking and a paid provider booking.
     * <p>Postconditions: the query returns false despite those historical bookings.
     */
    @Test
    @DisplayName("REPO-B11: exclude paid customer and provider bookings")
    void paidBookingsDoNotCountAsOutstanding() {
        Account caller = saveAccount("caller");
        Account other = saveAccount("other");
        saveBooking(caller, other, BookingStatus.ConfirmedPaid, EARLIER);
        saveBooking(other, caller, BookingStatus.ConfirmedPaid, LATER);
        entityManager.clear();

        assertThat(hasOutstandingBooking(caller.getId())).isFalse();
    }

    /**
     * Verifies REPO-B12: OR grouping does not associate other users' unfinished work with the caller.
     * <p>Preconditions: caller's booking is paid; an unrelated booking is unfinished.
     * <p>Postconditions: the caller has no outstanding work.
     */
    @Test
    @DisplayName("REPO-B12: ignore unrelated unfinished work with a paid caller booking")
    void unrelatedOutstandingBookingDoesNotBlockCaller() {
        Account caller = saveAccount("caller");
        Account other = saveAccount("other");
        Account third = saveAccount("third");
        saveBooking(caller, other, BookingStatus.ConfirmedPaid, EARLIER);
        saveBooking(other, third, BookingStatus.Unconfirmed, LATER);
        entityManager.clear();

        assertThat(hasOutstandingBooking(caller.getId())).isFalse();
    }

    /**
     * Supplies the four outstanding lifecycle states in each participant role.
     * <p>Preconditions: none.
     * <p>Postconditions: eight separately executed case inputs are available.
     * @return documented case IDs, states, and participant roles
     */
    private static Stream<Arguments> outstandingBookingCases() {
        return Stream.of(
                Arguments.of("REPO-B02", BookingStatus.Unconfirmed, true),
                Arguments.of("REPO-B03", BookingStatus.CustomerConfirmed, true),
                Arguments.of("REPO-B04", BookingStatus.ServiceProviderConfirmed, true),
                Arguments.of("REPO-B05", BookingStatus.ConfirmedUnpaid, true),
                Arguments.of("REPO-B06", BookingStatus.Unconfirmed, false),
                Arguments.of("REPO-B07", BookingStatus.CustomerConfirmed, false),
                Arguments.of("REPO-B08", BookingStatus.ServiceProviderConfirmed, false),
                Arguments.of("REPO-B09", BookingStatus.ConfirmedUnpaid, false));
    }

    /**
     * Calls the deactivation query with its required repeated account/status arguments.
     * <p>Preconditions: accountId is a persisted account ID.
     * <p>Postconditions: no data changes; ConfirmedPaid is excluded independently in both roles.
     * @param accountId caller's account identity
     * @return whether unfinished/unpaid work references that account
     */
    private boolean hasOutstandingBooking(Long accountId) {
        return bookings.existsByCustomer_IdAndStatusNotOrServiceProvider_IdAndStatusNot(
                accountId, BookingStatus.ConfirmedPaid, accountId, BookingStatus.ConfirmedPaid);
    }

    /**
     * Persists and flushes a uniquely named synthetic user.
     * <p>Preconditions: suffix keeps the full username within 50 characters.
     * <p>Postconditions: the row exists inside the test transaction and has a generated ID.
     * @param suffix unique name within this test
     * @param superhero whether the user is eligible to provide protection
     * @param active whether the user is active
     * @return persisted fixture user
     */
    private User saveUser(String suffix, boolean superhero, boolean active) {
        String username = prefix + suffix;
        User user = new User(username, "test-only-hash", "Repository Test User",
                "00000000000", username + "@example.test", superhero);
        if (!active) {
            user.deactivate();
        }
        return users.saveAndFlush(user);
    }

    /**
     * Persists an existing-wallet fixture owned by an active eligible user.
     * <p>Preconditions: suffix is unique within this test.
     * <p>Postconditions: account ownership and its generated ID are stored inside the transaction.
     * @param suffix owner username suffix
     * @return persisted account fixture
     */
    private Account saveAccount(String suffix) {
        return accounts.saveAndFlush(new Account(saveUser(suffix, true, true), WALLET_BALANCE));
    }

    /**
     * Persists a ledger fixture with a controlled timestamp for ordering assertions.
     * <p>Preconditions: both accounts have persisted IDs and timestamp is non-null.
     * <p>Postconditions: the row has a generated ID; balances have not been changed.
     * @param source sender account
     * @param destination recipient account
     * @param timestamp transfer timestamp to store
     * @return persisted transfer fixture
     */
    private TransferRecord saveTransfer(Account source, Account destination, Instant timestamp) {
        TransferRecord transfer = new TransferRecord(source, destination, BOOKING_AMOUNT);
        ReflectionTestUtils.setField(transfer, "transferDate", timestamp);
        return transfers.saveAndFlush(transfer);
    }

    /**
     * Persists a booking in the requested valid lifecycle state.
     * <p>Preconditions: accounts have persisted IDs; status and timestamp are initialized.
     * <p>Postconditions: booking state, parties, timestamp, and optional payment link are flushed.
     * @param customer customer account
     * @param provider provider account
     * @param status lifecycle state to arrange
     * @param timestamp creation time used by ordering assertions
     * @return persisted booking fixture
     */
    private Booking saveBooking(Account customer, Account provider, BookingStatus status, Instant timestamp) {
        Booking booking = new Booking(customer, provider, BOOKING_AMOUNT);
        ReflectionTestUtils.setField(booking, "createdAt", timestamp);
        switch (status) {
            case Unconfirmed -> { }
            case CustomerConfirmed -> booking.confirmCustomer();
            case ServiceProviderConfirmed -> booking.confirmServiceProvider();
            case ConfirmedUnpaid, ConfirmedPaid -> {
                booking.confirmCustomer();
                booking.confirmServiceProvider();
                if (status == BookingStatus.ConfirmedPaid) {
                    booking.markPaid(saveTransfer(customer, provider, timestamp));
                }
            }
        }
        assertThat(booking.getStatus()).isEqualTo(status);
        return bookings.saveAndFlush(booking);
    }
}

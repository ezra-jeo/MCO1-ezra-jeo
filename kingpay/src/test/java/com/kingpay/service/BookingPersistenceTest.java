package com.kingpay.service;

import com.kingpay.dto.request.CreateBookingRequest;
import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** P5-H/C: committed HTTP/MySQL lifecycle and concurrency, with targeted fixture cleanup and no outer transaction. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BookingPersistenceTest {
    enum Route { CREATE, READ, LIST, CUSTOMER_CONFIRM, PROVIDER_CONFIRM, PAY }
    private static final int WAIT_SECONDS = 30;
    @Autowired private MockMvc mvc;
    @Autowired private BookingService service;
    @Autowired private UserService profiles;
    @Autowired private TokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private BookingRepository bookings;
    @Autowired private TransferRecordRepository ledger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private User customer;
    private User provider;
    private User outsider;
    private User accountless;
    private Long customerAccountId;
    private Long providerAccountId;
    private Long outsiderAccountId;

    /** Preconditions: schema validates. Postconditions: four committed identities, three zero wallets, no bookings. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            customer = users.saveAndFlush(identity("p5c_" + suffix, false));
            provider = users.saveAndFlush(identity("p5p_" + suffix, true));
            outsider = users.saveAndFlush(identity("p5o_" + suffix, false));
            accountless = users.saveAndFlush(identity("p5n_" + suffix, false));
            customerAccountId = accounts.saveAndFlush(new Account(customer, new BigDecimal("0.00"))).getId();
            providerAccountId = accounts.saveAndFlush(new Account(provider, new BigDecimal("0.00"))).getId();
            outsiderAccountId = accounts.saveAndFlush(new Account(outsider, new BigDecimal("0.00"))).getId();
        });
    }

    /** Preconditions: valid quote plus forged server-owned fields. Postconditions: 201 initial fixed-party booking, no money.
     * @throws Exception on HTTP failure */
    @Test
    void creationPersistsServerChosenCustomerAndInitialState() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        String json = body(providerAccountId, "100.00").replace("}", ",\"customerAccountId\":" + outsiderAccountId
                + ",\"status\":\"ConfirmedPaid\",\"paymentTransferId\":999,\"createdAt\":\"2000-01-01T00:00:00Z\"}");
        var result = mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(json), customer))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.customerAccountId").value(customerAccountId))
                .andExpect(jsonPath("$.data.serviceProviderAccountId").value(providerAccountId))
                .andExpect(jsonPath("$.data.amount").value(100)).andExpect(jsonPath("$.data.status").value("Unconfirmed"))
                .andExpect(jsonPath("$.data.paymentTransferId").isEmpty()).andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();
        Number id = read(result.getResponse().getContentAsString(), "$.data.id");
        Booking saved = bookings.findById(id.longValue()).orElseThrow();
        assertThat(saved.getCreatedAt()).isBetween(before, Instant.now().plusSeconds(1));
        assertFixed(id.longValue(), "100.00");
        assertThat(bookingCount()).isEqualTo(1);
        noMoney();
    }

    /** Preconditions: zero funds, exact positive boundary price. Postconditions: booking succeeds without requiring funding.
     * @param price minimum or maximum DECIMAL(19,2) quote
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"0.01", "99999999999999999.99"})
    void zeroFundedCustomerCanBookAnExactPositivePrice(String price) throws Exception {
        mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body(providerAccountId, price)), customer))
                .andExpect(status().isCreated());
        assertThat(bookingCount()).isEqualTo(1);
        noMoney();
    }

    /** Preconditions: invalid request shape. Postconditions: 400 with no booking, balance or ledger changes.
     * @param shape malformed provider/price/JSON
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"missing-provider", "null-provider", "zero-provider", "negative-provider", "missing-price", "null-price", "zero-price", "negative-price", "fraction", "overflow", "malformed"})
    void invalidCreationHasNoEffects(String shape) throws Exception {
        String json = switch (shape) {
            case "missing-provider" -> "{\"amount\":100}";
            case "null-provider" -> "{\"serviceProviderAccountId\":null,\"amount\":100}";
            case "zero-provider" -> body(0L, "100");
            case "negative-provider" -> body(-1L, "100");
            case "missing-price" -> "{\"serviceProviderAccountId\":" + providerAccountId + "}";
            case "null-price" -> body(providerAccountId, "null");
            case "zero-price" -> body(providerAccountId, "0");
            case "negative-price" -> body(providerAccountId, "-1");
            case "fraction" -> body(providerAccountId, "1.001");
            case "overflow" -> body(providerAccountId, "100000000000000000");
            default -> "{broken";
        };
        mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(json), customer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        assertThat(bookingCount()).isZero();
        noMoney();
    }

    /** Preconditions: accountless customer or absent provider wallet. Postconditions: 404 without creating booking.
     * @param missingCustomer whether caller lacks a wallet
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingPartyWalletIsRejected(boolean missingCustomer) throws Exception {
        mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(body(missingCustomer ? providerAccountId : Long.MAX_VALUE, "100")), missingCustomer ? accountless : customer))
                .andExpect(status().isNotFound());
        assertThat(bookingCount()).isZero();
        noMoney();
    }

    /** Preconditions: customer proposes own wallet. Postconditions: self-booking rejected without insert.
     * @throws Exception on HTTP failure */
    @Test
    void selfBookingIsRejected() throws Exception {
        mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body(customerAccountId, "100")), customer))
                .andExpect(status().isBadRequest());
        assertThat(bookingCount()).isZero();
        noMoney();
    }

    /** Preconditions: proposed provider inactive or ineligible. Postconditions: 409 without booking.
     * @param inactive which provider rule is violated
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unavailableProviderIsRejected(boolean inactive) throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User saved = users.findById(provider.getId()).orElseThrow();
            if (inactive) { saved.deactivate(); } else { saved.setSuperhero(false); }
        });
        mvc.perform(auth(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body(providerAccountId, "100")), customer))
                .andExpect(status().isConflict());
        assertThat(bookingCount()).isZero();
        noMoney();
    }

    /** Preconditions: no bearer. Postconditions: all six routes reject before domain writes.
     * @param route controller operation
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Route.class)
    void everyRouteRequiresAuthentication(Route route) throws Exception {
        mvc.perform(request(route, Long.MAX_VALUE)).andExpect(status().isUnauthorized());
        assertThat(bookingCount()).isZero();
        noMoney();
    }

    /** Preconditions: token predates user deactivation. Postconditions: each route rejects inactive caller.
     * @param route controller operation
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Route.class)
    void everyRouteRejectsInactiveBearer(Route route) throws Exception {
        String token = tokens.issue(customer).token();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> users.findById(customer.getId()).orElseThrow().deactivate());
        mvc.perform(request(route, Long.MAX_VALUE).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertThat(bookingCount()).isZero();
    }

    /** Preconditions: persisted booking, either assigned participant. Postconditions: private DTO returned without mutation.
     * @param asCustomer selected participant
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void bothParticipantsCanRead(boolean asCustomer) throws Exception {
        long id = createBooking();
        mvc.perform(auth(get("/api/v1/bookings/" + id), asCustomer ? customer : provider)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id)).andExpect(jsonPath("$.data.status").value("Unconfirmed"))
                .andExpect(jsonPath("$.data.customerAccountId").value(customerAccountId))
                .andExpect(jsonPath("$.data.serviceProviderAccountId").value(providerAccountId));
        noMoney();
    }

    /** Preconditions: unrelated user knows booking ID. Postconditions: private read denied with no state change.
     * @throws Exception on HTTP failure */
    @Test
    void unrelatedUserCannotRead() throws Exception {
        long id = createBooking();
        mvc.perform(auth(get("/api/v1/bookings/" + id), outsider)).andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("Unconfirmed");
        noMoney();
    }

    /** Preconditions: wrong participant/outsider calls a confirmation route. Postconditions: 403, initial state retained.
     * @param attempt caller/route combination
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"provider-customer", "customer-provider", "outsider-customer", "outsider-provider"})
    void onlyAssignedActorCanConfirmItsSide(String attempt) throws Exception {
        long id = createBooking();
        User actor = attempt.startsWith("outsider") ? outsider : attempt.startsWith("provider") ? provider : customer;
        boolean customerRoute = attempt.endsWith("customer");
        mvc.perform(auth(confirmation(id, customerRoute), actor)).andExpect(status().isForbidden());
        assertThat(state(id)).isEqualTo("Unconfirmed");
        noMoney();
    }

    /** Preconditions: correct actors confirm in either order. Postconditions: half-state then unpaid, no price/funds change.
     * @param customerFirst confirmation order
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void bothConfirmationOrdersPersistUnpaidWithoutMoney(boolean customerFirst) throws Exception {
        long id = createBooking();
        mvc.perform(auth(confirmation(id, customerFirst), customerFirst ? customer : provider)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(customerFirst ? "CustomerConfirmed" : "ServiceProviderConfirmed"));
        assertThat(state(id)).isEqualTo(customerFirst ? "CustomerConfirmed" : "ServiceProviderConfirmed");
        noMoney();
        mvc.perform(auth(confirmation(id, !customerFirst), customerFirst ? provider : customer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ConfirmedUnpaid")).andExpect(jsonPath("$.data.paymentTransferId").isEmpty());
        assertThat(state(id)).isEqualTo("ConfirmedUnpaid");
        assertFixed(id, "100.00");
        noMoney();
    }

    /** Preconditions: duplicate confirmations by same participant. Postconditions: no extra progress; unpaid repeats stay unpaid.
     * @param asCustomer initial confirming actor
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void repeatedConfirmationsAreHarmless(boolean asCustomer) throws Exception {
        long id = createBooking();
        User actor = asCustomer ? customer : provider;
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(auth(confirmation(id, asCustomer), actor)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value(asCustomer ? "CustomerConfirmed" : "ServiceProviderConfirmed"));
        }
        mvc.perform(auth(confirmation(id, !asCustomer), asCustomer ? provider : customer)).andExpect(status().isOk());
        mvc.perform(auth(confirmation(id, asCustomer), actor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ConfirmedUnpaid"));
        assertThat(bookingCount()).isEqualTo(1);
        noMoney();
    }

    /** Preconditions: funded wallets, forged confirmation body. Postconditions: fixed quote/parties remain and funds do not move.
     * @throws Exception on HTTP failure */
    @Test
    void confirmationCannotReplacePricePartiesOrPayment() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accounts.findById(customerAccountId).orElseThrow().credit(new BigDecimal("1000"));
            accounts.findById(providerAccountId).orElseThrow().credit(new BigDecimal("500"));
        });
        long id = createBooking();
        mvc.perform(auth(confirmation(id, true).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1,\"serviceProviderAccountId\":" + outsiderAccountId + ",\"status\":\"ConfirmedPaid\"}"), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CustomerConfirmed"));
        mvc.perform(auth(confirmation(id, false), provider)).andExpect(status().isOk());
        assertFixed(id, "100.00");
        assertThat(balance(customerAccountId)).isEqualByComparingTo("1000.00");
        assertThat(balance(providerAccountId)).isEqualByComparingTo("500.00");
        assertThat(ledgerCount()).isZero();
    }

    /** Preconditions: paid historical fixture and active participants. Postconditions: repeats retain paid state/link.
     * @throws Exception on HTTP failure */
    @Test
    void paidFixtureConfirmationsDoNotReversePayment() throws Exception {
        long id = new TransactionTemplate(transactionManager).execute(status -> {
            Account customerWallet = accounts.findById(customerAccountId).orElseThrow();
            Account providerWallet = accounts.findById(providerAccountId).orElseThrow();
            Booking paid = new Booking(customerWallet, providerWallet, new BigDecimal("100.00"));
            paid.confirmCustomer(); paid.confirmServiceProvider();
            paid.markPaid(ledger.saveAndFlush(new TransferRecord(customerWallet, providerWallet, paid.getAmount())));
            return bookings.saveAndFlush(paid).getId();
        });
        Long link = jdbc.queryForObject("SELECT payment_transfer_id FROM bookings WHERE id = ?", Long.class, id);
        mvc.perform(auth(confirmation(id, true), customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ConfirmedPaid"));
        mvc.perform(auth(confirmation(id, false), provider)).andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentTransferId").value(link));
        assertThat(state(id)).isEqualTo("ConfirmedPaid");
        assertThat(ledgerCount()).isEqualTo(1);
        assertThat(balance(customerAccountId)).isEqualByComparingTo("0");
        assertThat(balance(providerAccountId)).isEqualByComparingTo("0");
    }

    /** Preconditions: caller participates in both roles; createdAt ties. Postconditions: private ID-filtered list ordered by ID.
     * @throws Exception on HTTP failure */
    @Test
    void listIncludesBothRolesExcludesOutsidersAndBreaksTimeTies() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> users.findById(customer.getId()).orElseThrow().setSuperhero(true));
        Instant tie = Instant.parse("2026-01-01T00:00:00Z");
        long first = fixtureBooking(customerAccountId, providerAccountId, tie);
        long second = fixtureBooking(providerAccountId, customerAccountId, tie);
        fixtureBooking(outsiderAccountId, providerAccountId, tie);
        mvc.perform(auth(get("/api/v1/bookings"), customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(second)).andExpect(jsonPath("$.data[1].id").value(first));
        mvc.perform(auth(get("/api/v1/bookings"), provider)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(auth(get("/api/v1/bookings"), accountless)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        noMoney();
    }

    /** Preconditions: persisted booking and token predating rename. Postconditions: reads/list/confirmation use stable IDs.
     * @throws Exception on HTTP failure */
    @Test
    void renamedParticipantKeepsAccessWithExistingToken() throws Exception {
        long id = createBooking();
        String token = tokens.issue(customer).token();
        profiles.updateMe(new UpdateUserRequest("p5new_" + UUID.randomUUID().toString().replace("-", ""),
                "Booking Test", "0", "test@example.test", false), () -> customer.getUsername());
        mvc.perform(get("/api/v1/bookings/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/bookings").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(confirmation(id, true).header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CustomerConfirmed"));
        noMoney();
    }

    /** Preconditions: booking does not exist. Postconditions: identifier operations return 404.
     * @param route selected identifier route
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(value = Route.class, names = {"READ", "CUSTOMER_CONFIRM", "PROVIDER_CONFIRM", "PAY"})
    void missingBookingReturnsNotFound(Route route) throws Exception {
        mvc.perform(auth(request(route, Long.MAX_VALUE), customer)).andExpect(status().isNotFound());
        noMoney();
    }

    /** Preconditions: malformed path ID. Postconditions: safe 400 and no writes.
     * @param route identifier route
     * @param id malformed or nonpositive value
     * @throws Exception on HTTP failure */
    @ParameterizedTest @MethodSource("invalidPaths")
    void invalidBookingPathIsRejected(Route route, String id) throws Exception {
        String path = "/api/v1/bookings/" + id + switch (route) {
            case CUSTOMER_CONFIRM -> "/confirmations/customer";
            case PROVIDER_CONFIRM -> "/confirmations/service-provider";
            case PAY -> "/payment";
            default -> "";
        };
        mvc.perform(auth(route == Route.READ ? get(path) : post(path), customer)).andExpect(status().isBadRequest());
        assertThat(bookingCount()).isZero();
    }

    /** Preconditions: ordinary immutable booking. Postconditions: generic edit/delete routes do not exist.
     * @param method attempted mutation verb
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void genericBookingMutationsAreUnavailable(String method) throws Exception {
        long id = createBooking();
        mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.valueOf(method), "/api/v1/bookings/" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":999}"), customer)).andExpect(status().is4xxClientError());
        assertFixed(id, "100.00");
        assertThat(state(id)).isEqualTo("Unconfirmed");
        noMoney();
    }

    /** Preconditions: both confirmed. Postconditions: payment remains 501 and no money moves.
     * @throws Exception on HTTP failure */
    @Test
    void completedBookingStillRequiresPhaseSixPayment() throws Exception {
        long id = createBooking();
        service.confirmCustomer(id, () -> customer.getUsername());
        service.confirmServiceProvider(id, () -> provider.getUsername());
        mvc.perform(auth(post("/api/v1/bookings/" + id + "/payment"), customer)).andExpect(status().isNotImplemented());
        assertThat(state(id)).isEqualTo("ConfirmedUnpaid");
        noMoney();
    }

    /** Preconditions: same valid create submitted twice. Postconditions: two independent jobs; no retry deduplication. */
    @Test
    void repeatedCreateRequestsAreIndependentBookings() {
        long first = createBooking();
        long second = createBooking();
        assertThat(first).isNotEqualTo(second);
        assertThat(bookingCount()).isEqualTo(2);
        noMoney();
    }

    /** Preconditions: both participants confirm simultaneously. Postconditions: both confirmations persist, no lost update.
     * @throws Exception on worker failure */
    @Test
    void simultaneousDifferentConfirmationsReachUnpaid() throws Exception {
        long id = createBooking();
        race(List.of(() -> { service.confirmCustomer(id, () -> customer.getUsername()); return "confirmed"; },
                () -> { service.confirmServiceProvider(id, () -> provider.getUsername()); return "confirmed"; }));
        assertThat(state(id)).isEqualTo("ConfirmedUnpaid");
        assertThat(bookingCount()).isEqualTo(1);
        noMoney();
    }

    /** Preconditions: four duplicate customer confirmations race one provider confirmation. Postconditions: one unpaid job.
     * @throws Exception on worker failure */
    @Test
    void concurrentRepeatedConfirmationsRemainIdempotent() throws Exception {
        long id = createBooking();
        Callable<String> customerConfirm = () -> { service.confirmCustomer(id, () -> customer.getUsername()); return "confirmed"; };
        Callable<String> providerConfirm = () -> { service.confirmServiceProvider(id, () -> provider.getUsername()); return "confirmed"; };
        assertThat(race(List.of(customerConfirm, customerConfirm, customerConfirm, customerConfirm, providerConfirm))).containsOnly("confirmed");
        assertThat(state(id)).isEqualTo("ConfirmedUnpaid");
        assertThat(bookingCount()).isEqualTo(1);
        noMoney();
    }

    /** Preconditions: no bookings; creation races provider eligibility removal. Postconditions: no outstanding ineligible provider.
     * @throws Exception on worker failure */
    @Test
    void creationAndEligibilityRemovalAreSerialized() throws Exception {
        Callable<String> create = () -> { try { createBooking(); return "booked"; } catch (BusinessConflictException exception) { return "unavailable"; } };
        Callable<String> remove = () -> {
            try { profiles.updateMe(new UpdateUserRequest(provider.getUsername(), "Booking Test", "0", "test@example.test", false), () -> provider.getUsername()); return "changed"; }
            catch (BusinessConflictException exception) { return "outstanding"; }
        };
        List<String> results = race(List.of(create, remove));
        boolean eligible = jdbc.queryForObject("SELECT is_superhero FROM users WHERE id = ?", Boolean.class, provider.getId());
        if (eligible) { assertThat(results).containsExactlyInAnyOrder("booked", "outstanding"); assertThat(bookingCount()).isEqualTo(1); }
        else { assertThat(results).containsExactlyInAnyOrder("unavailable", "changed"); assertThat(bookingCount()).isZero(); }
        noMoney();
    }

    /** Preconditions: no bookings; zero-wallet participant deactivation competes with booking creation.
     * Postconditions: either active booked participant or inactive user without booking.
     * @param providerSide which participant deactivates
     * @throws Exception on worker failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void creationAndParticipantDeactivationAreSerialized(boolean providerSide) throws Exception {
        User actor = providerSide ? provider : customer;
        Callable<String> create = () -> {
            try { createBooking(); return "booked"; }
            catch (BusinessConflictException | AccessDeniedException exception) { return "unavailable"; }
        };
        Callable<String> deactivate = () -> {
            try { profiles.deactivateMe(() -> actor.getUsername()); return "deactivated"; }
            catch (BusinessConflictException exception) { return "outstanding"; }
        };
        List<String> results = race(List.of(create, deactivate));
        boolean active = jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, actor.getId());
        if (active) { assertThat(results).containsExactlyInAnyOrder("booked", "outstanding"); assertThat(bookingCount()).isEqualTo(1); }
        else { assertThat(results).containsExactlyInAnyOrder("unavailable", "deactivated"); assertThat(bookingCount()).isZero(); }
        noMoney();
    }

    /** Preconditions: both identities eligible and create opposite-party jobs concurrently. Postconditions: no deadlock; two jobs.
     * @throws Exception on worker failure */
    @Test
    void oppositePartyCreationsUseOneOwnerLockOrder() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> users.findById(customer.getId()).orElseThrow().setSuperhero(true));
        race(List.of(() -> { createBooking(); return "booked"; }, () -> {
            service.create(new CreateBookingRequest(customerAccountId, new BigDecimal("50")), () -> provider.getUsername()); return "booked";
        }));
        assertThat(bookingCount()).isEqualTo(2);
        noMoney();
    }

    /** Preconditions: test rows may have committed. Postconditions: only captured booking/ledger/wallet/user fixtures removed. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM bookings WHERE customer_account_id IN (?, ?, ?) OR provider_account_id IN (?, ?, ?)",
                customerAccountId, providerAccountId, outsiderAccountId, customerAccountId, providerAccountId, outsiderAccountId);
        jdbc.update("DELETE FROM transfer_records WHERE source_account_id IN (?, ?, ?) OR destination_account_id IN (?, ?, ?)",
                customerAccountId, providerAccountId, outsiderAccountId, customerAccountId, providerAccountId, outsiderAccountId);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", customerAccountId, providerAccountId, outsiderAccountId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?)", customer == null ? null : customer.getId(), provider == null ? null : provider.getId(),
                outsider == null ? null : outsider.getId(), accountless == null ? null : accountless.getId());
    }

    /** Preconditions: active fixtures exist. Postconditions: a real committed booking is created, no money moved.
     * @return generated ID */
    private long createBooking() { return service.create(new CreateBookingRequest(providerAccountId, new BigDecimal("100")), () -> customer.getUsername()).id(); }

    /** Preconditions: fixture IDs exist. Postconditions: no writes.
     * @param id booking ID
     * @return committed state */
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, id); }

    /** Preconditions: wallet exists. Postconditions: no writes.
     * @param id wallet ID
     * @return observed balance */
    private BigDecimal balance(Long id) { return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id); }

    /** Preconditions: operation completed. Postconditions: asserts three wallet balances remain zero and ledger absent. */
    private void noMoney() {
        assertThat(balance(customerAccountId)).isEqualByComparingTo("0");
        assertThat(balance(providerAccountId)).isEqualByComparingTo("0");
        assertThat(balance(outsiderAccountId)).isEqualByComparingTo("0");
        assertThat(ledgerCount()).isZero();
    }

    /** Preconditions: fixture IDs captured. Postconditions: no writes.
     * @return fixture booking count */
    private long bookingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM bookings WHERE customer_account_id IN (?, ?, ?) OR provider_account_id IN (?, ?, ?)",
                Long.class, customerAccountId, providerAccountId, outsiderAccountId, customerAccountId, providerAccountId, outsiderAccountId);
    }

    /** Preconditions: fixture IDs captured. Postconditions: no writes.
     * @return fixture ledger count */
    private long ledgerCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transfer_records WHERE source_account_id IN (?, ?, ?) OR destination_account_id IN (?, ?, ?)",
                Long.class, customerAccountId, providerAccountId, outsiderAccountId, customerAccountId, providerAccountId, outsiderAccountId);
    }

    /** Preconditions: booking persisted. Postconditions: asserts fixed price and IDs.
     * @param id booking ID
     * @param price expected original price */
    private void assertFixed(long id, String price) {
        assertThat(jdbc.queryForObject("SELECT amount FROM bookings WHERE id = ?", BigDecimal.class, id)).isEqualByComparingTo(price);
        assertThat(jdbc.queryForObject("SELECT customer_account_id FROM bookings WHERE id = ?", Long.class, id)).isEqualTo(customerAccountId);
        assertThat(jdbc.queryForObject("SELECT provider_account_id FROM bookings WHERE id = ?", Long.class, id)).isEqualTo(providerAccountId);
    }

    /** Preconditions: valid participant fixtures. Postconditions: ordered-list fixture with controlled creation time saved.
     * @param customerId customer wallet
     * @param providerId provider wallet
     * @param created fixed creation time
     * @return generated fixture ID */
    private long fixtureBooking(Long customerId, Long providerId, Instant created) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            Booking fixture = new Booking(accounts.findById(customerId).orElseThrow(), accounts.findById(providerId).orElseThrow(), BigDecimal.TEN);
            ReflectionTestUtils.setField(fixture, "createdAt", created);
            return bookings.saveAndFlush(fixture).getId();
        });
    }

    /** Preconditions: synthetic name. Postconditions: unsaved user fixture returned.
     * @param name unique username
     * @param hero eligibility
     * @return fixture identity */
    private User identity(String name, boolean hero) { return new User(name, "test-only-hash", "Booking Test", "0", "test@example.test", hero); }

    /** Preconditions: fixture values controlled. Postconditions: request JSON returned.
     * @param providerId provider wallet
     * @param amount numeric/null literal
     * @return JSON body */
    private String body(Long providerId, String amount) { return "{\"serviceProviderAccountId\":%d,\"amount\":%s}".formatted(providerId, amount); }

    /** Preconditions: persisted active test identity. Postconditions: genuine bearer added without printing it.
     * @param request HTTP operation
     * @param user fixture identity
     * @return authenticated request */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) { return request.header("Authorization", "Bearer " + tokens.issue(user).token()); }

    /** Preconditions: test ID controlled. Postconditions: participant-specific confirmation request returned.
     * @param id booking ID
     * @param customerSide selected side
     * @return request without authentication */
    private MockHttpServletRequestBuilder confirmation(long id, boolean customerSide) {
        return post("/api/v1/bookings/" + id + "/confirmations/" + (customerSide ? "customer" : "service-provider"));
    }

    /** Preconditions: fixtures exist. Postconditions: selected route request returned.
     * @param route controller operation
     * @param id booking ID
     * @return unauthenticated request */
    private MockHttpServletRequestBuilder request(Route route, long id) {
        return switch (route) {
            case CREATE -> post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(body(providerAccountId, "100"));
            case READ -> get("/api/v1/bookings/" + id);
            case LIST -> get("/api/v1/bookings");
            case CUSTOMER_CONFIRM -> confirmation(id, true);
            case PROVIDER_CONFIRM -> confirmation(id, false);
            case PAY -> post("/api/v1/bookings/" + id + "/payment");
        };
    }

    /** Preconditions: none. Postconditions: every identifier route receives all three bad path forms.
     * @return twelve route/path cases */
    private static Stream<Arguments> invalidPaths() {
        return Stream.of(Route.READ, Route.CUSTOMER_CONFIRM, Route.PROVIDER_CONFIRM, Route.PAY)
                .flatMap(route -> Stream.of("not-an-id", "0", "-1").map(id -> Arguments.of(route, id)));
    }

    /** Preconditions: each task calls a separate service transaction. Postconditions: all workers complete before cleanup.
     * @param tasks competing operations
     * @return outcomes
     * @throws Exception on timeout/unexpected worker failure */
    private List<String> race(List<Callable<String>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(tasks.size())) {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timed out."); }
                return task.call();
            })).toList();
            if (!ready.await(WAIT_SECONDS, TimeUnit.SECONDS)) { throw new IllegalStateException("Workers timed out."); }
            start.countDown();
            java.util.ArrayList<String> results = new java.util.ArrayList<>();
            for (var future : futures) { results.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS)); }
            return results;
        }
    }
}

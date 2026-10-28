package com.kingpay.controller;

import com.kingpay.entity.*;
import com.kingpay.repository.*;
import com.kingpay.service.TokenService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.stream.Stream;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** P3-H: real HTTP/security/services/MySQL, rollback-isolated fixtures and no bearer logging. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class PhaseThreeHttpTest {
    @Autowired private com.kingpay.repository.HeroRateRepository rates;
    enum Route { PROFILE_GET, PROFILE_UPDATE, DEACTIVATE, HEROES, ACCOUNT_CREATE, ACCOUNT_GET, LOAD }
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private BookingRepository bookings;
    @Autowired private TransferRecordRepository transfers;
    @Autowired private TokenService tokens;
    @Autowired private EntityManager entities;
    private User customer;
    private User hero;
    private User accountless;
    private Account customerAccount;
    private Account heroAccount;

    /** Preconditions: MySQL validates. Postconditions: distinct uniquely named identities and zero wallets exist. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        customer = users.saveAndFlush(identity("p3c_" + suffix, false));
        hero = users.saveAndFlush(identity("p3h_" + suffix, true));
        accountless = users.saveAndFlush(identity("p3n_" + suffix, false));
        customerAccount = accounts.saveAndFlush(new Account(customer, new BigDecimal("0.00")));
        heroAccount = accounts.saveAndFlush(new Account(hero, new BigDecimal("0.00")));
    }

    /** Preconditions: no bearer. Postconditions: all seven routes reject before mutation.
     * @param route target method
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Route.class)
    void missingAuthenticationRejectsEveryRoute(Route route) throws Exception {
        mvc.perform(request(route)).andExpect(status().isUnauthorized());
        assertThat(customerAccount.getBalance()).isEqualByComparingTo("0");
        assertThat(customer.isActive()).isTrue();
    }

    /** Preconditions: token issued then identity disabled. Postconditions: every route rejects without changing funds.
     * @param route target method
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Route.class)
    void inactiveBearerRejectsEveryRoute(Route route) throws Exception {
        String token = tokens.issue(customer).token();
        customer.deactivate();
        users.flush();
        mvc.perform(request(route).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertThat(customerAccount.getBalance()).isEqualByComparingTo("0");
    }

    /** Preconditions: two distinct authenticated identities. Postconditions: each reads only its own contact details.
     * @throws Exception on HTTP failure */
    @Test
    void profilesArePrivateAndCredentialFree() throws Exception {
        mvc.perform(auth(get("/api/v1/users/me?userId=" + hero.getId()), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(customer.getId()))
                .andExpect(jsonPath("$.data.email").value(customer.getEmail()))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
        mvc.perform(auth(get("/api/v1/users/me"), hero)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(hero.getId()));
    }

    /** Preconditions: valid full replacement with extra server-owned fields. Postconditions: only declared fields persist.
     * @throws Exception on HTTP failure */
    @Test
    void updatePersistsEditableFieldsAndExistingTokenUsesStableId() throws Exception {
        String token = tokens.issue(customer).token();
        String newName = "p3new_" + UUID.randomUUID().toString().replace("-", "");
        String body = profile(newName, true).replace("}", ",\"userId\":" + hero.getId()
                + ",\"balance\":999,\"passwordHash\":\"forged\",\"active\":false}");
        mvc.perform(put("/api/v1/users/me").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(newName))
                .andExpect(jsonPath("$.data.fullName").value("Updated Profile"));
        entities.clear();
        User saved = users.findById(customer.getId()).orElseThrow();
        assertThat(saved.getPasswordHash()).isEqualTo("test-only-hash");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.isSuperhero()).isTrue();
        assertThat(accounts.findByUser_Username(newName).orElseThrow().getBalance()).isEqualByComparingTo("0");
        assertThat(users.findById(hero.getId()).orElseThrow().getUsername()).isEqualTo(hero.getUsername());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(newName));
    }

    /** Preconditions: another identity owns requested username. Postconditions: 409; all previous profile data retained.
     * @throws Exception on HTTP failure */
    @Test
    void duplicateRenameLeavesProfileIntact() throws Exception {
        mvc.perform(auth(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content(profile(hero.getUsername(), true)), customer)).andExpect(status().isConflict());
        entities.clear();
        assertThat(users.findById(customer.getId()).orElseThrow().getUsername()).isEqualTo(customer.getUsername());
        assertThat(users.findById(customer.getId()).orElseThrow().getFullName()).isEqualTo("Phase Three");
    }

    /** Preconditions: invalid full replacement. Postconditions: useful 400 and no persisted edits.
     * @throws Exception on HTTP failure */
    @Test
    void invalidProfileDoesNotPersist() throws Exception {
        mvc.perform(auth(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content(profile("x", false).replace("test@example.test", "invalid-email")), customer))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.username").exists())
                .andExpect(jsonPath("$.data.email").exists());
        entities.clear();
        assertThat(users.findById(customer.getId()).orElseThrow().getUsername()).isEqualTo(customer.getUsername());
    }

    /** Preconditions: mixed active/eligible fixtures. Postconditions: sorted public summaries omit contact data.
     * @throws Exception on HTTP failure */
    @Test
    void browsingFiltersEligibilityAndActivityAndHidesContacts() throws Exception {
        rates.saveAndFlush(new com.kingpay.entity.HeroRate(heroAccount, new BigDecimal("100.00")));
        User inactive = users.saveAndFlush(identity("p3inactive_" + UUID.randomUUID().toString().substring(0, 8), true));
        inactive.deactivate();
        accountless.setSuperhero(true);
        users.flush();
        var result = mvc.perform(auth(get("/api/v1/users/superheroes"), customer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].email").isEmpty())
                .andExpect(jsonPath("$.data[*].phone").isEmpty()).andReturn();
        String json = result.getResponse().getContentAsString();
        assertThat(json).contains(hero.getUsername()).doesNotContain(customer.getUsername(), inactive.getUsername(),
                accountless.getUsername(), "password");
        java.util.List<String> names = com.jayway.jsonpath.JsonPath.read(json, "$.data[*].username");
        assertThat(names).isSorted();
        java.util.List<Number> selectedAccount = com.jayway.jsonpath.JsonPath.read(json,
                "$.data[?(@.username == '" + hero.getUsername() + "')].accountId");
        assertThat(selectedAccount).hasSize(1);
        assertThat(selectedAccount.getFirst().longValue()).isEqualTo(heroAccount.getId());
    }

    /** Preconditions: accountless active user. Postconditions: 201 zero balance; next creation 409 and one row remains.
     * @throws Exception on HTTP failure */
    @Test
    void accountCreationIsZeroAndUniqueRegardlessOfBody() throws Exception {
        mvc.perform(auth(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + hero.getId() + ",\"balance\":999}"), accountless))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.userId").value(accountless.getId()))
                .andExpect(jsonPath("$.data.balance").value(0));
        mvc.perform(auth(post("/api/v1/accounts"), accountless)).andExpect(status().isConflict());
        assertThat(accounts.findByUser_Username(accountless.getUsername()).orElseThrow().getBalance()).isEqualByComparingTo("0");
        assertThat(heroAccount.getBalance()).isEqualByComparingTo("0");
    }

    /** Preconditions: authenticated caller selects own route with forged query ID. Postconditions: only own wallet read.
     * @throws Exception on HTTP failure */
    @Test
    void walletLookupCannotSelectAnotherOwner() throws Exception {
        mvc.perform(auth(get("/api/v1/accounts/me?userId=" + hero.getId()), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(customer.getId()));
    }

    /** Preconditions: valid load and forged target fields. Postconditions: exact owner increase, no ledger row.
     * @throws Exception on HTTP failure */
    @Test
    void loadPersistsOnlyOwnersExactAmountWithoutTransferRecord() throws Exception {
        long ledgerBefore = transfers.count();
        mvc.perform(auth(post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25.50,\"accountId\":" + heroAccount.getId() + ",\"balance\":999}"), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.balance").value(25.50));
        mvc.perform(auth(post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.25}"), customer)).andExpect(status().isOk());
        entities.clear();
        assertThat(accounts.findById(customerAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo("25.75");
        assertThat(accounts.findById(heroAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo("0");
        assertThat(transfers.count()).isEqualTo(ledgerBefore);
    }

    /** Preconditions: invalid amount bodies. Postconditions: 400 with unchanged stored balance.
     * @param body missing/nonpositive/precision/overflow input
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"amount\":null}", "{\"amount\":0}", "{\"amount\":-1}", "{\"amount\":1.001}", "{\"amount\":100000000000000000}"})
    void invalidLoadsLeaveWalletUnchanged(String body) throws Exception {
        mvc.perform(auth(post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON).content(body), customer))
                .andExpect(status().isBadRequest());
        entities.clear();
        assertThat(accounts.findById(customerAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo("0");
    }

    /** Preconditions: wallet at capacity. Postconditions: load rejected, exact maximum retained.
     * @throws Exception on HTTP failure */
    @Test
    void resultingBalanceOverflowIsRejected() throws Exception {
        customerAccount.credit(new BigDecimal("99999999999999999.99"));
        accounts.flush();
        mvc.perform(auth(post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.01}"), customer)).andExpect(status().isBadRequest());
        entities.clear();
        assertThat(accounts.findById(customerAccount.getId()).orElseThrow().getBalance()).isEqualByComparingTo("99999999999999999.99");
    }

    /** Preconditions: user has no account. Postconditions: own lookup and load return 404, no row inserted.
     * @throws Exception on HTTP failure */
    @Test
    void missingWalletReturnsNotFoundForReadAndLoad() throws Exception {
        mvc.perform(auth(get("/api/v1/accounts/me"), accountless)).andExpect(status().isNotFound());
        mvc.perform(auth(post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1}"), accountless)).andExpect(status().isNotFound());
        assertThat(accounts.existsByUser_Id(accountless.getId())).isFalse();
    }

    /** Preconditions: balance positive. Postconditions: 409, user still active, funds intact.
     * @throws Exception on HTTP failure */
    @Test
    void deactivationRequiresZeroBalance() throws Exception {
        customerAccount.credit(BigDecimal.ONE);
        accounts.flush();
        mvc.perform(auth(delete("/api/v1/users/me"), customer)).andExpect(status().isConflict());
        assertThat(customer.isActive()).isTrue();
        assertThat(customerAccount.getBalance()).isEqualByComparingTo("1");
    }

    /** Preconditions: unfinished booking in selected role/status. Postconditions: 409; user/booking retained.
     * @param providerRole whether hero requests deactivation
     * @param status unfinished lifecycle state
     * @throws Exception on HTTP failure */
    @ParameterizedTest @MethodSource("outstandingCases")
    void outstandingBookingPreventsEitherParticipantDeactivation(boolean providerRole, BookingStatus status) throws Exception {
        Booking booking = new Booking(customerAccount, heroAccount, BigDecimal.TEN);
        if (status == BookingStatus.CustomerConfirmed || status == BookingStatus.ConfirmedUnpaid) { booking.confirmCustomer(); }
        if (status == BookingStatus.ServiceProviderConfirmed || status == BookingStatus.ConfirmedUnpaid) { booking.confirmServiceProvider(); }
        bookings.saveAndFlush(booking);
        User actor = providerRole ? hero : customer;
        mvc.perform(auth(delete("/api/v1/users/me"), actor)).andExpect(status().isConflict());
        assertThat(actor.isActive()).isTrue();
        assertThat(bookings.existsById(booking.getId())).isTrue();
    }

    /** Preconditions: zero wallet and paid booking/ledger fixtures. Postconditions: only activity freezes, references retained.
     * @throws Exception on HTTP failure */
    @Test
    void successfulDeactivationRetainsWalletBookingAndLedger() throws Exception {
        Booking booking = new Booking(customerAccount, heroAccount, BigDecimal.TEN);
        booking.confirmCustomer();
        booking.confirmServiceProvider();
        TransferRecord transfer = transfers.saveAndFlush(new TransferRecord(customerAccount, heroAccount, BigDecimal.TEN));
        booking.markPaid(transfer);
        bookings.saveAndFlush(booking);
        String token = tokens.issue(customer).token();
        mvc.perform(delete("/api/v1/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        entities.clear();
        assertThat(users.findById(customer.getId()).orElseThrow().isActive()).isFalse();
        assertThat(accounts.existsById(customerAccount.getId())).isTrue();
        assertThat(bookings.findById(booking.getId()).orElseThrow().getPaymentTransfer().getId()).isEqualTo(transfer.getId());
        assertThat(transfers.existsById(transfer.getId())).isTrue();
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    /** Preconditions: assigned unpaid provider booking. Postconditions: removing eligibility conflicts and preserves flag.
     * @throws Exception on HTTP failure */
    @Test
    void providerEligibilityCannotBeRemovedDuringOutstandingWork() throws Exception {
        bookings.saveAndFlush(new Booking(customerAccount, heroAccount, BigDecimal.TEN));
        mvc.perform(auth(put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content(profile(hero.getUsername(), false)), hero)).andExpect(status().isConflict());
        assertThat(hero.isSuperhero()).isTrue();
    }

    /** Preconditions: none. Postconditions: covers both participant roles and all four outstanding states.
     * @return eight role/state cases */
    private static Stream<Arguments> outstandingCases() {
        return Stream.of(false, true).flatMap(role -> Stream.of(BookingStatus.Unconfirmed,
                BookingStatus.CustomerConfirmed, BookingStatus.ServiceProviderConfirmed, BookingStatus.ConfirmedUnpaid)
                .map(status -> Arguments.of(role, status)));
    }

    /** Preconditions: fixture values are safe synthetic data. Postconditions: unsaved identity returned.
     * @param name unique username
     * @param eligible superhero flag
     * @return synthetic user */
    private User identity(String name, boolean eligible) {
        return new User(name, "test-only-hash", "Phase Three", "000", "test@example.test", eligible);
    }

    /** Preconditions: safe synthetic strings. Postconditions: complete editable profile JSON returned.
     * @param name desired username
     * @param eligible superhero flag
     * @return valid profile request */
    private String profile(String name, boolean eligible) {
        return "{\"username\":\"%s\",\"fullName\":\"Updated Profile\",\"phone\":\"123\",\"email\":\"test@example.test\",\"isSuperhero\":%s}"
                .formatted(name, eligible);
    }

    /** Preconditions: user is active and persisted. Postconditions: request has real signed bearer.
     * @param request HTTP operation
     * @param user fixture identity
     * @return authenticated request */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + tokens.issue(user).token());
    }

    /** Preconditions: fixtures exist. Postconditions: valid request for selected route returned.
     * @param route public controller method
     * @return unauthenticated request */
    private MockHttpServletRequestBuilder request(Route route) {
        return switch (route) {
            case PROFILE_GET -> get("/api/v1/users/me");
            case PROFILE_UPDATE -> put("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content(profile(customer.getUsername(), false));
            case DEACTIVATE -> delete("/api/v1/users/me");
            case HEROES -> get("/api/v1/users/superheroes");
            case ACCOUNT_CREATE -> post("/api/v1/accounts");
            case ACCOUNT_GET -> get("/api/v1/accounts/me");
            case LOAD -> post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}");
        };
    }
}

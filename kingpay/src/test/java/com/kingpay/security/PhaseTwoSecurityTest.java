package com.kingpay.security;

import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.User;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.service.TokenService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** P2-H: real security filters, controllers, services and MySQL; each fixture transaction rolls back. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class PhaseTwoSecurityTest {
    enum Action { TRANSFER, HISTORY, BOOKING_READ, CUSTOMER_CONFIRM, PROVIDER_CONFIRM, PAY }
    private static final String PASSWORD = "synthetic-test-password";
    private static final String FIXTURE_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private BookingRepository bookings;
    @Autowired private TokenService tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtSettings settings;
    @Autowired private PasswordEncoder passwords;
    private User customer;
    private User provider;
    private User outsider;
    private Account customerAccount;
    private Account providerAccount;
    private Booking booking;

    /** Preconditions: schema validates. Postconditions: test-only identities and booking exist inside rollback transaction. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        customer = users.saveAndFlush(identity("p2c_" + suffix, false));
        provider = users.saveAndFlush(identity("p2p_" + suffix, true));
        outsider = users.saveAndFlush(identity("p2o_" + suffix, false));
        customerAccount = accounts.saveAndFlush(new Account(customer, BigDecimal.TEN));
        providerAccount = accounts.saveAndFlush(new Account(provider, BigDecimal.ZERO));
        booking = bookings.saveAndFlush(new Booking(customerAccount, providerAccount, BigDecimal.TEN));
    }

    /** Preconditions: active credentials exist. Postconditions: public login issues usable JWT and no session.
     * @throws Exception on HTTP failure */
    @Test
    void validLoginIssuesUsableStatelessToken() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(customer.getUsername(), PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        String token = read(response.getResponse().getContentAsString(), "$.data.token");
        assertThat(decoder.decode(token).getSubject()).isEqualTo(customer.getId().toString());
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie"));
        assertThat(response.getRequest().getSession(false)).isNull();
    }

    /** Preconditions: unique valid public registration. Postconditions: encoded password/zero wallet, no credentials disclosed.
     * @param output captured application logs
     * @throws Exception on HTTP failure */
    @Test
    void publicRegistrationUsesPasswordEncoderWithoutCredentialDisclosure(CapturedOutput output) throws Exception {
        String username = "p2r_" + UUID.randomUUID().toString().replace("-", "");
        String body = """
                {"username":"%s","password":"%s","fullName":"Test User","phone":"000",
                 "email":"test@example.test","isSuperhero":false}
                """.formatted(username, PASSWORD);
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist()).andReturn();
        User saved = users.findByUsername(username).orElseThrow();
        assertThat(passwords.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(accounts.findByUser_Username(username).orElseThrow().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, saved.getPasswordHash(), settings.secret());
        assertThat(output.getAll()).doesNotContain(PASSWORD, saved.getPasswordHash(), settings.secret());
    }

    /** Preconditions: wrong/unknown/disabled credentials. Postconditions: identical safe 401 and no token.
     * @param scenario credential failure variant
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"wrong-password", "unknown-user", "inactive"})
    void rejectedLoginsHaveSameSafeResponse(String scenario) throws Exception {
        if (scenario.equals("inactive")) {
            customer.deactivate();
            users.flush();
        }
        String name = scenario.equals("unknown-user") ? "absent_" + UUID.randomUUID() : customer.getUsername();
        String password = scenario.equals("wrong-password") ? "wrong-password" : PASSWORD;
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials(name, password)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid credentials."))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /** Preconditions: missing, malformed, tampered, expired or wrong-issuer bearer. Postconditions: safe 401.
     * @param variant invalid authentication
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"missing", "malformed", "tampered", "expired", "wrong-issuer", "no-expiry", "bad-subject"})
    void invalidBearerCannotReachProtectedEndpoint(String variant) throws Exception {
        String valid = tokens.issue(customer).token();
        String token = switch (variant) {
            case "malformed" -> "not-a-jwt";
            case "tampered" -> "X" + valid.substring(1);
            case "expired" -> signed(customer.getId().toString(), settings.issuer(), Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));
            case "wrong-issuer" -> signed(customer.getId().toString(), "wrong-issuer", Instant.now(), Instant.now().plusSeconds(60));
            case "no-expiry" -> signed(customer.getId().toString(), settings.issuer(), Instant.now(), null);
            case "bad-subject" -> signed("not-an-id", settings.issuer(), Instant.now(), Instant.now().plusSeconds(60));
            default -> valid;
        };
        var request = get("/api/v1/accounts/me");
        if (!variant.equals("missing")) { request.header("Authorization", "Bearer " + token); }
        mvc.perform(request).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required or invalid."))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /** Preconditions: token issued while active. Postconditions: deactivation immediately rejects it.
     * @throws Exception on HTTP failure */
    @Test
    void deactivationInvalidatesExistingBearer() throws Exception {
        String token = tokens.issue(customer).token();
        customer.deactivate();
        users.flush();
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** Preconditions: user without wallet is removed after issuance. Postconditions: prior bearer is rejected.
     * @throws Exception on HTTP failure */
    @Test
    void deletionInvalidatesExistingBearer() throws Exception {
        String token = tokens.issue(outsider).token();
        users.delete(outsider);
        users.flush();
        mvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** Preconditions: persisted username changes after issuance. Postconditions: stable subject still permits private history.
     * @throws Exception on HTTP failure */
    @Test
    void usernameChangesDoNotStaleThePrincipal() throws Exception {
        String token = tokens.issue(customer).token();
        customer.setUsername("new_" + UUID.randomUUID().toString().replace("-", ""));
        users.flush();
        mvc.perform(get("/api/v1/transfers/history/" + customerAccount.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** Preconditions: assigned actor authenticates. Postconditions: implemented operations return 200; unconfirmed payment returns 409.
     * @param action protected method
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Action.class)
    void allowedActorsReachBusinessPlaceholder(Action action) throws Exception {
        call(action, false, action == Action.PROVIDER_CONFIRM ? provider : customer)
                .andExpect(status().is(action == Action.PAY ? 409 : 200));
    }

    /** Preconditions: unrelated caller authenticates. Postconditions: 403 without executing future mutation.
     * @param action protected method
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Action.class)
    void unrelatedActorsReceiveForbidden(Action action) throws Exception {
        call(action, false, outsider).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("Access denied."));
    }

    /** Preconditions: authenticated request references absent row. Postconditions: consistent safe 404.
     * @param action protected method
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(Action.class)
    void absentResourcesReceiveNotFound(Action action) throws Exception {
        call(action, true, customer).andExpect(status().isNotFound()).andExpect(jsonPath("$.data").isEmpty());
    }

    /** Preconditions: customer submits provider's source account and forged username. Postconditions: principal wins, 403.
     * @throws Exception on HTTP failure */
    @Test
    void requestBodyCannotImpersonateAnotherWalletOwner() throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + tokens.issue(customer).token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fromId":%d,"toId":%d,"amount":1,"authenticatedUsername":"%s"}
                                """.formatted(providerAccount.getId(), customerAccount.getId(), provider.getUsername())))
                .andExpect(status().isForbidden());
    }

    /** Preconditions: assigned participants call each other's commands. Postconditions: actor-specific gates return 403.
     * @throws Exception on HTTP failure */
    @Test
    void participantsCannotUseEachOthersCommands() throws Exception {
        call(Action.CUSTOMER_CONFIRM, false, provider).andExpect(status().isForbidden());
        call(Action.PAY, false, provider).andExpect(status().isForbidden());
        call(Action.PROVIDER_CONFIRM, false, customer).andExpect(status().isForbidden());
        call(Action.BOOKING_READ, false, provider).andExpect(status().isOk());
    }

    /** Preconditions: invalid password fields submitted. Postconditions: field errors useful, no rejected secret echoed/logged.
     * @param output captured logs
     * @throws Exception on HTTP failure */
    @Test
    void validationDoesNotDiscloseRejectedPassword(CapturedOutput output) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"valid-name","password":"shrt!","fullName":"Test User",
                                 "phone":"000","email":"test@example.test","isSuperhero":false}
                                """))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Request validation failed."))
                .andExpect(jsonPath("$.data.password").isString()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("shrt!", "rejectedValue", "Exception");
        assertThat(output.getAll()).doesNotContain("shrt!");
    }

    /** Preconditions: malformed JSON/invalid path supplied. Postconditions: safe consistent 400 envelopes.
     * @throws Exception on HTTP failure */
    @Test
    void malformedInputAndIdsHaveSafeErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed request."));
        String token = tokens.issue(customer).token();
        mvc.perform(get("/api/v1/bookings/not-a-number").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed request."));
        mvc.perform(get("/api/v1/bookings/0").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    /** Preconditions: fixture exists. Postconditions: builds a synthetic identity.
     * @param name unique username
     * @param hero eligibility
     * @return unsaved user */
    private User identity(String name, boolean hero) {
        return new User(name, FIXTURE_HASH, "Phase Two", "000", "test@example.test", hero);
    }

    /** Preconditions: simple test values. Postconditions: builds credentials JSON.
     * @param name username
     * @param password dummy password
     * @return JSON request */
    private String credentials(String name, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(name, password);
    }

    /** Preconditions: fixture and signer available. Postconditions: signs controlled test claims.
     * @param subject token subject
     * @param issuer issuer
     * @param issued issue time
     * @param expires optional expiry
     * @return serialized test JWT */
    private String signed(String subject, String issuer, Instant issued, Instant expires) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer(issuer).issuedAt(issued);
        if (expires != null) { claims.expiresAt(expires); }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    /** Preconditions: current fixtures exist. Postconditions: executes an authenticated protected HTTP operation.
     * @param action operation
     * @param absent whether to use missing ID
     * @param actor authenticated user
     * @return HTTP assertions
     * @throws Exception on request failure */
    private ResultActions call(Action action, boolean absent, User actor) throws Exception {
        long id = absent ? Long.MAX_VALUE : (action == Action.TRANSFER || action == Action.HISTORY
                ? customerAccount.getId() : booking.getId());
        MockHttpServletRequestBuilder request = switch (action) {
            case TRANSFER -> post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fromId\":%d,\"toId\":%d,\"amount\":1}".formatted(id, providerAccount.getId()));
            case HISTORY -> get("/api/v1/transfers/history/" + id);
            case BOOKING_READ -> get("/api/v1/bookings/" + id);
            case CUSTOMER_CONFIRM -> post("/api/v1/bookings/" + id + "/confirmations/customer");
            case PROVIDER_CONFIRM -> post("/api/v1/bookings/" + id + "/confirmations/service-provider");
            case PAY -> post("/api/v1/bookings/" + id + "/payment");
        };
        return mvc.perform(request.header("Authorization", "Bearer " + tokens.issue(actor).token()));
    }
}

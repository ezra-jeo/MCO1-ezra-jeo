package com.kingpay.controller;

import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.AdminSettings;
import com.kingpay.service.TokenService;
import com.kingpay.service.UserService;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security/controllers/MySQL plus generated OpenAPI contracts; fixtures roll back. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class OpenApiAdminHttpTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private BookingRepository bookings;
    @Autowired private TransferRecordRepository transfers;
    @Autowired private TokenService tokens;
    @Autowired private UserService profiles;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping routes;
    @MockitoBean private AdminSettings settings;
    private User admin;
    private User reader;
    private User historical;
    private Long walletId;
    private Long bookingId;
    private Long transferId;

    /** Preconditions: schema valid. Postconditions: unique admin/ordinary/historical identities and financial rows available. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        admin = users.saveAndFlush(identity("docs_a_" + suffix, false));
        reader = users.saveAndFlush(identity("docs_r_" + suffix, false));
        historical = users.saveAndFlush(identity("docs_h_" + suffix, true));
        Account source = accounts.saveAndFlush(new Account(admin, new BigDecimal("900")));
        Account destination = accounts.saveAndFlush(new Account(historical, new BigDecimal("600")));
        walletId = source.getId();
        TransferRecord transfer = transfers.saveAndFlush(new TransferRecord(source, destination, new BigDecimal("100")));
        transferId = transfer.getId();
        Booking booking = new Booking(source, destination, new BigDecimal("100"));
        booking.confirmCustomer(); booking.confirmServiceProvider(); booking.markPaid(transfer);
        bookingId = bookings.saveAndFlush(booking).getId();
        historical.deactivate(); users.saveAndFlush(historical);
        when(settings.userIds()).thenReturn(Set.of(admin.getId()));
    }

    /** Preconditions: no bearer. Postconditions: spec, UI and its assets/config are accessible.
     * @param path documentation route
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config", "/swagger-ui/index.html", "/swagger-ui/swagger-ui-bundle.js"})
    void documentationResourcesArePublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    /** Preconditions: public Swagger entry. Postconditions: redirects to bundled local UI.
     * @throws Exception on HTTP failure */
    @Test
    void swaggerEntryRedirectsToLocalUi() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("swagger-ui/index.html")));
    }

    /** Preconditions: controllers registered. Postconditions: generated schema contains exactly every business route, no framework routes.
     * @throws Exception on HTTP failure */
    @Test
    void generatedOperationsMatchControllers() throws Exception {
        Set<String> expected = new HashSet<>();
        routes.getHandlerMethods().forEach((mapping, handler) -> {
            if (handler.getBeanType().getPackageName().equals("com.kingpay.controller")) {
                mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
                        .forEach(method -> expected.add(method.name().toLowerCase() + " " + path)));
            }
        });
        Map<String, Map<String, Object>> paths = read(document(), "$.paths");
        Set<String> actual = new HashSet<>();
        paths.forEach((path, methods) -> methods.keySet().forEach(method -> actual.add(method + " " + path)));
        assertThat(actual).hasSize(18).isEqualTo(expected);
    }

    /** Preconditions: generated security metadata. Postconditions: auth public, business routes inherit bearer and admin rule is explicit.
     * @throws Exception on HTTP failure */
    @Test
    void generatedSecurityMatchesActualPolicy() throws Exception {
        String json = document();
        assertThat((String) read(json, "$.components.securitySchemes.bearerAuth.scheme")).isEqualTo("bearer");
        assertThat((List<?>) read(json, "$.security[0].bearerAuth")).isEmpty();
        assertThat((List<?>) read(json, "$.paths['/api/v1/auth/register'].post.security")).isEmpty();
        assertThat((List<?>) read(json, "$.paths['/api/v1/auth/login'].post.security")).isEmpty();
        Map<String, Map<String, Map<String, Object>>> paths = read(json, "$.paths");
        paths.forEach((path, methods) -> methods.forEach((method, operation) -> {
            if (!path.startsWith("/api/v1/auth/")) {
                assertThat(operation.get("security") == null || !((List<?>) operation.get("security")).isEmpty()).isTrue();
            }
            assertThat(operation.get("parameters") == null || !operation.get("parameters").toString().contains("caller")).isTrue();
        }));
        assertThat((String) read(json, "$.paths['/api/v1/admin/overview'].get.description")).contains("KINGPAY_ADMIN_USER_IDS");
        mvc.perform(get("/api/v1/accounts/me")).andExpect(status().isUnauthorized());
    }

    /** Preconditions: DTO-based schema generation. Postconditions: input validation and credential boundaries remain visible.
     * @throws Exception on HTTP failure */
    @Test
    void schemasExposeValidationAndSafeDtoFields() throws Exception {
        String json = document();
        Map<String, Object> booking = read(json, "$.components.schemas.CreateBookingRequest.properties");
        assertThat(booking.keySet()).containsExactlyInAnyOrder("serviceProviderAccountId", "amount");
        assertThat((List<String>) read(json, "$.components.schemas.CreateBookingRequest.required")).contains("amount", "serviceProviderAccountId");
        assertThat((Number) read(json, "$.components.schemas.TransferRequest.properties.amount.multipleOf")).isEqualTo(0.01);
        assertThat((Boolean) read(json, "$.components.schemas.TransferRequest.properties.amount.exclusiveMinimum")).isTrue();
        assertThat((Boolean) read(json, "$.components.schemas.RegisterRequest.properties.password.writeOnly")).isTrue();
        Map<String, Object> schemas = read(json, "$.components.schemas");
        assertThat(schemas).doesNotContainKeys("User", "Account", "Booking", "TransferRecord", "Principal", "JwtSettings", "AdminSettings");
        Map<String, Object> publicUser = read(json, "$.components.schemas.UserResponse.properties");
        assertThat(publicUser).doesNotContainKeys("passwordHash", "password", "token");
        assertThat((String) read(json, "$.components.schemas.ErrorResponse.properties.message.type")).isEqualTo("string");
    }

    /** Preconditions: annotated response contracts. Postconditions: typed success envelopes, empty 204 and relevant safe errors.
     * @throws Exception on HTTP failure */
    @Test
    void responseSchemasMatchEnvelopesAndStatuses() throws Exception {
        String json = document();
        String createdRef = read(json, "$.paths['/api/v1/auth/register'].post.responses['201'].content['application/json'].schema['$ref']");
        Map<String, Object> registrationCodes = read(json, "$.paths['/api/v1/auth/register'].post.responses");
        assertThat(registrationCodes).doesNotContainKey("200");
        String schema = createdRef.substring(createdRef.lastIndexOf('/') + 1);
        assertThat((String) read(json, "$.components.schemas['" + schema + "'].properties.data['$ref']")).endsWith("/UserResponse");
        Map<String, Object> noContent = read(json, "$.paths['/api/v1/users/me'].delete.responses['204']");
        assertThat(noContent.get("content") == null || ((Map<?, ?>) noContent.get("content")).isEmpty()).isTrue();
        assertThat((String) read(json, "$.paths['/api/v1/bookings/{bookingId}/payment'].post.responses['409']['$ref']")).endsWith("/Conflict");
        Map<String, Map<String, Map<String, Object>>> paths = read(json, "$.paths");
        paths.values().forEach(methods -> methods.values().forEach(operation -> assertThat(((Map<?, ?>) operation.get("responses")).containsKey("501")).isFalse()));
    }

    /** Preconditions: active configured ID and historical rows. Postconditions: four global collections, no credentials or writes.
     * @throws Exception on HTTP failure */
    @Test
    void configuredAdminReadsAllTablesWithoutCredentialsOrWrites() throws Exception {
        long usersBefore = users.count(); long accountsBefore = accounts.count(); long bookingsBefore = bookings.count(); long transfersBefore = transfers.count();
        String json = mvc.perform(auth(get("/api/v1/admin/overview"), admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<Boolean>) read(json, "$.data.users[?(@.id == " + historical.getId() + ")].active")).containsExactly(false);
        List<Number> balance = read(json, "$.data.accounts[?(@.id == " + walletId + ")].balance");
        assertThat(new BigDecimal(balance.getFirst().toString())).isEqualByComparingTo("900");
        List<Number> links = read(json, "$.data.bookings[?(@.id == " + bookingId + ")].paymentTransferId");
        assertThat(links.getFirst().longValue()).isEqualTo(transferId);
        List<Number> amounts = read(json, "$.data.transfers[?(@.id == " + transferId + ")].amount");
        assertThat(new BigDecimal(amounts.getFirst().toString())).isEqualByComparingTo("100");
        assertThat(json).doesNotContain("\"passwordHash\"", "\"password\"", "\"token\"", "private-test-hash");
        assertThat(users.count()).isEqualTo(usersBefore); assertThat(accounts.count()).isEqualTo(accountsBefore);
        assertThat(bookings.count()).isEqualTo(bookingsBefore); assertThat(transfers.count()).isEqualTo(transfersBefore);
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, walletId)).isEqualByComparingTo("900");
    }

    /** Preconditions: unlisted user spoofs query identity. Postconditions: 403, no global data.
     * @throws Exception on HTTP failure */
    @Test
    void unlistedUserCannotSpoofAdminIdentity() throws Exception {
        mvc.perform(auth(get("/api/v1/admin/overview?userId=" + admin.getId() + "&isAdmin=true"), reader))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.data").isEmpty());
    }

    /** Preconditions: empty trusted configuration. Postconditions: even formerly configured user is denied.
     * @throws Exception on HTTP failure */
    @Test
    void emptyAllowlistDeniesEveryone() throws Exception {
        when(settings.userIds()).thenReturn(Set.of());
        mvc.perform(auth(get("/api/v1/admin/overview"), admin)).andExpect(status().isForbidden());
    }

    /** Preconditions: token issued before configured identity disabled. Postconditions: bearer rejected despite allowlist.
     * @throws Exception on HTTP failure */
    @Test
    void inactiveAdminTokenIsRejected() throws Exception {
        var request = auth(get("/api/v1/admin/overview"), admin);
        admin.deactivate(); users.saveAndFlush(admin);
        mvc.perform(request).andExpect(status().isUnauthorized());
    }

    /** Preconditions: no bearer. Postconditions: no anonymous overview.
     * @throws Exception on HTTP failure */
    @Test
    void missingBearerCannotReadAdminData() throws Exception {
        mvc.perform(get("/api/v1/admin/overview")).andExpect(status().isUnauthorized());
    }

    /** Preconditions: configured admin attempts mutation. Postconditions: overview has no mutation handler.
     * @param verb unsupported HTTP verb
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void adminOverviewHasNoMutationRoute(String verb) throws Exception {
        mvc.perform(auth(request(HttpMethod.valueOf(verb), "/api/v1/admin/overview"), admin)).andExpect(status().isMethodNotAllowed());
    }

    /** Preconditions: issued token predates admin rename. Postconditions: stable configured ID still authorizes.
     * @throws Exception on HTTP failure */
    @Test
    void renamedAdminRetainsStableIdAccess() throws Exception {
        var request = auth(get("/api/v1/admin/overview"), admin);
        profiles.updateMe(new UpdateUserRequest("renamed_" + UUID.randomUUID().toString().replace("-", ""), "Admin", "0", "a@example.test", false), () -> admin.getUsername());
        mvc.perform(request).andExpect(status().isOk());
    }

    /** Preconditions: generated endpoint enabled. Postconditions: public JSON returned without credentials.
     * @return OpenAPI JSON
     * @throws Exception on HTTP failure */
    private String document() throws Exception { return mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); }

    /** Preconditions: test-controlled values. Postconditions: unsaved synthetic identity.
     * @param name unique username
     * @param hero eligibility
     * @return identity fixture */
    private User identity(String name, boolean hero) { return new User(name, "private-test-hash", "Documentation Test", "0", "test@example.test", hero); }

    /** Preconditions: active persisted actor. Postconditions: actual signed token added without printing it.
     * @param request HTTP operation
     * @param actor identity
     * @return authenticated request */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User actor) { return request.header("Authorization", "Bearer " + tokens.issue(actor).token()); }
}

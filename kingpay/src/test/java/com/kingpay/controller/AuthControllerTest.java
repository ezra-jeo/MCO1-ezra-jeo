package com.kingpay.controller;

import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.dto.request.LoginRequest;
import com.kingpay.dto.response.AuthResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.exception.SkeletonExceptionHandler;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import java.time.Instant;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP registration contract with the real exception advice and a mocked service. */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private static final String REGISTER_PATH = "/api/v1/auth/register";
    private static final String VALID_JSON = """
            {"username":"new-user","password":"test-password-123","fullName":"Test User",
             "phone":"00000000000","email":"user@example.test","isSuperhero":false}
            """;

    @Mock
    private AuthService service;

    private MockMvc mvc;

    /**
     * Configures the controller and advice without an application context or database.
     * <p>Preconditions: Mockito has initialized the service mock.
     * <p>Postconditions: request validation, response mapping, and exception advice can be exercised.
     */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service))
                .setControllerAdvice(new SkeletonExceptionHandler())
                .build();
    }

    /**
     * Verifies REG-C01: successful registration returns the planned public response.
     * <p>Preconditions: valid JSON and a successful service result.
     * <p>Postconditions: HTTP 201 exposes the user ID without a password/hash field.
     * @throws Exception if request dispatch or an assertion fails
     */
    @Test
    @DisplayName("REG-C01: return HTTP 201 without credentials")
    void registrationReturnsCreatedWithoutCredentials() throws Exception {
        when(service.register(any(RegisterRequest.class))).thenReturn(new UserResponse(1L,
                "new-user", "Test User", "00000000000", "user@example.test", false, true));

        mvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    /**
     * Verifies REG-C02: a precheck conflict is reported as a client-visible conflict.
     * <p>Preconditions: valid JSON and a duplicate-username service exception.
     * <p>Postconditions: HTTP 409 contains the public-safe message.
     * @throws Exception if request dispatch or an assertion fails
     */
    @Test
    @DisplayName("REG-C02: return HTTP 409 for an existing username")
    void existingUsernameReturnsConflict() throws Exception {
        when(service.register(any(RegisterRequest.class))).thenThrow(new UsernameAlreadyExistsException());

        mvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username is already in use."));
    }

    /**
     * Verifies REG-C03: database-race diagnostics never enter the conflict response.
     * <p>Preconditions: the conflict exception retains a private diagnostic cause.
     * <p>Postconditions: HTTP 409 contains no diagnostic sentinel or constraint name.
     * @throws Exception if request dispatch or an assertion fails
     */
    @Test
    @DisplayName("REG-C03: return HTTP 409 without internal database diagnostics")
    void databaseConflictHidesItsCause() throws Exception {
        when(service.register(any(RegisterRequest.class))).thenThrow(new UsernameAlreadyExistsException(
                new IllegalStateException("private-diagnostic-sentinel uk_users_username")));

        String response = mvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("private-diagnostic-sentinel", "uk_users_username");
    }

    /**
     * Verifies REG-C04: DTO constraints reject invalid registration fields before the service.
     * <p>Preconditions: username is blank and password is too short.
     * <p>Postconditions: HTTP 400 contains no rejected values and registration is not invoked.
     * @throws Exception if request dispatch or an assertion fails
     */
    @Test
    @DisplayName("REG-C04: reject invalid DTO fields with HTTP 400")
    void invalidRegistrationDoesNotReachService() throws Exception {
        String invalid = VALID_JSON.replace("new-user", "").replace("test-password-123", "short");

        String response = mvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed."))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("short", "user@example.test");
        verifyNoInteractions(service);
    }

    /**
     * Verifies REG-C05: malformed JSON is rejected before registration.
     * <p>Preconditions: the request body is not valid JSON.
     * <p>Postconditions: HTTP 400 is returned without invoking the service.
     * @throws Exception if request dispatch or an assertion fails
     */
    @Test
    @DisplayName("REG-C05: reject malformed registration JSON with HTTP 400")
    void malformedRegistrationDoesNotReachService() throws Exception {
        mvc.perform(post(REGISTER_PATH).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    /** P2-C01. Preconditions: service accepts valid login. Postconditions: 200 bearer metadata; no password fields.
     * @throws Exception on HTTP failure */
    @Test
    @DisplayName("P2-C01: return HTTP 200 and bearer token metadata")
    void loginReturnsToken() throws Exception {
        when(service.login(any(LoginRequest.class)))
                .thenReturn(new AuthResponse("test-token", "Bearer", Instant.now().plusSeconds(3600)));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    /** P2-C02. Preconditions: service rejects credentials. Postconditions: generic safe HTTP 401.
     * @throws Exception on HTTP failure */
    @Test
    @DisplayName("P2-C02: return HTTP 401 for invalid credentials")
    void loginRejectsBadCredentials() throws Exception {
        when(service.login(any(LoginRequest.class))).thenThrow(new BadCredentialsException("private-reason"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid credentials."));
    }

    /** P2-C03. Preconditions: service rejects inactive user. Postconditions: same generic safe HTTP 401.
     * @throws Exception on HTTP failure */
    @Test
    @DisplayName("P2-C03: hide inactive-user details behind HTTP 401")
    void loginRejectsInactiveIdentity() throws Exception {
        when(service.login(any(LoginRequest.class))).thenThrow(new DisabledException("private-disabled-reason"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginJson()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("Invalid credentials."));
    }

    /** P2-C04. Preconditions: missing login fields. Postconditions: safe field errors before service execution.
     * @throws Exception on HTTP failure */
    @Test
    @DisplayName("P2-C04: reject missing login fields with HTTP 400")
    void loginRejectsInvalidBody() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.username").exists())
                .andExpect(jsonPath("$.data.password").exists());
        verifyNoInteractions(service);
    }

    /** P2-C05. Preconditions: malformed JSON. Postconditions: HTTP 400 without parser diagnostics.
     * @throws Exception on HTTP failure */
    @Test
    @DisplayName("P2-C05: reject malformed login JSON with HTTP 400")
    void loginRejectsMalformedBody() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed request."));
        verifyNoInteractions(service);
    }

    /** Preconditions: none. Postconditions: only synthetic credentials are used.
     * @return valid login JSON */
    private String loginJson() {
        return "{\"username\":\"new-user\",\"password\":\"test-password-123\"}";
    }
}

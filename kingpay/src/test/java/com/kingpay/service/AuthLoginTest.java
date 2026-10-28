package com.kingpay.service;

import com.kingpay.dto.request.LoginRequest;
import com.kingpay.dto.response.AuthResponse;
import com.kingpay.entity.User;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Isolated login service cases documented in test-cases/phase-2.md. */
@ExtendWith(MockitoExtension.class)
class AuthLoginTest {
    private static final LoginRequest REQUEST = new LoginRequest("login-user", "test-password-123");
    @Mock private UserRepository users;
    @Mock private AccountRepository accounts;
    @Mock private PasswordEncoder passwords;
    @Mock private AuthenticationManager authentication;
    @Mock private TokenService tokens;
    private AuthService service;

    /** Preconditions: Mockito initialized dependencies. Postconditions: fresh service; no collaborator calls. */
    @BeforeEach
    void setUp() {
        service = new AuthService(users, accounts, passwords, authentication, tokens);
    }

    /** P2-L01. Preconditions: authenticated active user exists. Postconditions: issuer response returned without writes. */
    @Test
    @DisplayName("P2-L01: authenticated active user receives a token")
    void activeLoginIssuesToken() {
        User user = user();
        authenticateSuccessfully();
        when(users.findByUsername(REQUEST.username())).thenReturn(Optional.of(user));
        AuthResponse expected = new AuthResponse("test-token", "Bearer", Instant.now().plusSeconds(3600));
        when(tokens.issue(user)).thenReturn(expected);

        assertThat(service.login(REQUEST)).isSameAs(expected);
        verifyNoInteractions(accounts, passwords);
    }

    /** P2-L02. Preconditions: manager rejects credentials. Postconditions: no user lookup, token or wallet write. */
    @Test
    @DisplayName("P2-L02: invalid credentials do not issue a token")
    void rejectedCredentialsStopLogin() {
        when(authentication.authenticate(any())).thenThrow(new BadCredentialsException("Invalid credentials."));

        assertThrows(BadCredentialsException.class, () -> service.login(REQUEST));
        verifyNoInteractions(users, accounts, passwords, tokens);
    }

    /** P2-L03. Preconditions: authenticated identity disappears. Postconditions: login fails without issuing a token. */
    @Test
    @DisplayName("P2-L03: missing current identity cannot obtain a token")
    void missingCurrentIdentityStopsLogin() {
        authenticateSuccessfully();
        when(users.findByUsername(REQUEST.username())).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class, () -> service.login(REQUEST));
        verifyNoInteractions(tokens);
    }

    /** P2-L04. Preconditions: authenticated identity is now inactive. Postconditions: login fails without a token. */
    @Test
    @DisplayName("P2-L04: inactive current identity cannot obtain a token")
    void inactiveCurrentIdentityStopsLogin() {
        User user = user();
        user.deactivate();
        authenticateSuccessfully();
        when(users.findByUsername(REQUEST.username())).thenReturn(Optional.of(user));

        assertThrows(BadCredentialsException.class, () -> service.login(REQUEST));
        verifyNoInteractions(tokens);
    }

    /** P2-L05. Preconditions: null request. Postconditions: rejected before authentication or persistence. */
    @Test
    @DisplayName("P2-L05: null login request fails safely")
    void nullRequestStopsLogin() {
        assertThrows(InvalidRequestException.class, () -> service.login(null));
        verifyNoInteractions(authentication, users, accounts, passwords, tokens);
    }

    /** Preconditions: none. Postconditions: synthetic ID assigned without database writes.
     * @return active test identity */
    private User user() {
        User user = new User(REQUEST.username(), "encoded", "Login User", "000", "login@example.test", false);
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    /** Preconditions: manager mock exists. Postconditions: authenticating returns the fixture username without hashing. */
    private void authenticateSuccessfully() {
        when(authentication.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(REQUEST.username(), null, List.of()));
    }
}

package com.kingpay.service;

import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.User;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Registration unit cases; database rollback and simultaneous requests have separate integration tests. */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long SAVED_USER_ID = 1_001L;
    private static final RegisterRequest REQUEST = new RegisterRequest("new-user",
            "test-password-123", "Test User", "00000000000", "user@example.test", true);

    @Mock
    private UserRepository users;

    @Mock
    private AccountRepository accounts;

    @Mock
    private PasswordEncoder encoder;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private TokenService tokens;

    private AuthService service;

    /**
     * Creates a fresh service with isolated persistence collaborators.
     * <p>Preconditions: Mockito has initialized both repositories.
     * <p>Postconditions: no repository methods have been invoked.
     */
    @BeforeEach
    void setUp() {
        service = new AuthService(users, accounts, encoder, authenticationManager, tokens);
    }

    /**
     * Verifies REG-U01: a new username produces a hashed user and zero-balance wallet.
     * <p>Preconditions: username is available and user persistence assigns an ID.
     * <p>Postconditions: check precedes writes, encoder output is stored, and response fields match.
     */
    @Test
    @DisplayName("REG-U01: save a hashed user and zero-balance wallet after the uniqueness check")
    void registersUserAndZeroBalanceWallet() {
        stubSavedUser();

        UserResponse response = service.register(REQUEST);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        InOrder order = inOrder(users, accounts);
        order.verify(users).existsByUsername(REQUEST.username());
        order.verify(users).saveAndFlush(userCaptor.capture());
        order.verify(accounts).saveAndFlush(accountCaptor.capture());
        User stored = userCaptor.getValue();
        assertThat(stored.getPasswordHash()).isNotEqualTo(REQUEST.password());
        assertThat(stored.getPasswordHash()).isEqualTo("test-encoded-hash");
        verify(encoder).encode(REQUEST.password());
        assertThat(accountCaptor.getValue().getUser()).isSameAs(stored);
        assertThat(accountCaptor.getValue().getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response).isEqualTo(new UserResponse(SAVED_USER_ID, REQUEST.username(),
                REQUEST.fullName(), REQUEST.phone(), REQUEST.email(), true, true));
    }

    /**
     * Verifies REG-U02: an existing username is rejected before either write.
     * <p>Preconditions: the existence query returns true.
     * <p>Postconditions: UsernameAlreadyExistsException is thrown; hashing and both saves are skipped.
     */
    @Test
    @DisplayName("REG-U02: reject an existing username before persistence")
    void existingUsernameStopsRegistration() {
        when(users.existsByUsername(REQUEST.username())).thenReturn(true);

        assertThrows(UsernameAlreadyExistsException.class, () -> service.register(REQUEST));
        verifyNoInteractions(encoder);

        verify(users).existsByUsername(REQUEST.username());
        verify(users, org.mockito.Mockito.never()).saveAndFlush(any(User.class));
        verifyNoInteractions(accounts);
    }

    /**
     * Verifies REG-U03: the named unique constraint protects a missed precheck.
     * <p>Preconditions: existence is false but user insert violates the username index.
     * <p>Postconditions: a username conflict retains the cause and no account write occurs.
     */
    @Test
    @DisplayName("REG-U03: translate the database username race into a domain conflict")
    void namedUsernameViolationBecomesConflict() {
        DataIntegrityViolationException failure = constraintFailure(User.USERNAME_UNIQUE_CONSTRAINT);
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        UsernameAlreadyExistsException conflict = assertThrows(UsernameAlreadyExistsException.class,
                () -> service.register(REQUEST));

        assertThat(conflict.getCause()).isSameAs(failure);
        verifyNoInteractions(accounts);
    }

    /**
     * Verifies REG-U04: another named constraint is not mislabeled as a username conflict.
     * <p>Preconditions: user persistence reports an unrelated constraint name.
     * <p>Postconditions: the original integrity failure propagates and no account is saved.
     */
    @Test
    @DisplayName("REG-U04: preserve unrelated integrity failures")
    void unrelatedConstraintIsNotUsernameConflict() {
        DataIntegrityViolationException failure = constraintFailure("uk_other_property");
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThat(assertThrows(DataIntegrityViolationException.class,
                () -> service.register(REQUEST))).isSameAs(failure);
        verifyNoInteractions(accounts);
    }

    /**
     * Verifies REG-U05: account failure is allowed to escape the transaction boundary.
     * <p>Preconditions: user insert succeeds but account insert throws a runtime failure.
     * <p>Postconditions: the same error propagates; actual rollback is checked separately in MySQL.
     */
    @Test
    @DisplayName("REG-U05: propagate an account write failure for transactional rollback")
    void accountFailurePropagates() {
        stubSavedUser();
        DataIntegrityViolationException failure = new DataIntegrityViolationException("test account failure");
        when(accounts.saveAndFlush(any(Account.class))).thenThrow(failure);

        assertThat(assertThrows(DataIntegrityViolationException.class,
                () -> service.register(REQUEST))).isSameAs(failure);
    }

    /**
     * Verifies REG-U06: null requests fail before accessing persistence.
     * <p>Preconditions: request is null.
     * <p>Postconditions: IllegalArgumentException is thrown and neither repository is called.
     */
    @Test
    @DisplayName("REG-U06: reject a null registration request")
    void nullRequestDoesNotAccessRepositories() {
        assertThrows(IllegalArgumentException.class, () -> service.register(null));

        verifyNoInteractions(users, accounts);
    }

    /**
     * Verifies REG-U07: a qualified MySQL constraint name is still recognized.
     * <p>Preconditions: the username constraint is qualified and backtick-quoted.
     * <p>Postconditions: registration throws a username conflict without saving an account.
     */
    @Test
    @DisplayName("REG-U07: recognize a qualified username constraint name")
    void qualifiedUsernameConstraintBecomesConflict() {
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(constraintFailure("`kingpay`.`uk_users_username`"));

        assertThrows(UsernameAlreadyExistsException.class, () -> service.register(REQUEST));

        verifyNoInteractions(accounts);
    }

    /**
     * Verifies REG-U08: an unnamed constraint is not guessed to be a username conflict.
     * <p>Preconditions: Hibernate cannot identify the failed constraint.
     * <p>Postconditions: the original integrity error propagates unchanged.
     */
    @Test
    @DisplayName("REG-U08: preserve an unnamed integrity failure")
    void unnamedConstraintIsNotUsernameConflict() {
        DataIntegrityViolationException failure = constraintFailure(null);
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThat(assertThrows(DataIntegrityViolationException.class,
                () -> service.register(REQUEST))).isSameAs(failure);
        verifyNoInteractions(accounts);
    }

    /**
     * Verifies REG-U09: an integrity failure without a Hibernate constraint cause is preserved.
     * <p>Preconditions: persistence reports a generic integrity error.
     * <p>Postconditions: the original error propagates unchanged.
     */
    @Test
    @DisplayName("REG-U09: preserve a generic database integrity error")
    void genericIntegrityFailureIsNotUsernameConflict() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("test generic failure");
        when(users.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThat(assertThrows(DataIntegrityViolationException.class,
                () -> service.register(REQUEST))).isSameAs(failure);
        verifyNoInteractions(accounts);
    }

    /**
     * Simulates the database assigning an identifier to a newly saved user.
     * <p>Preconditions: the test expects the user insert to succeed.
     * <p>Postconditions: user save returns its argument with SAVED_USER_ID assigned.
     */
    private void stubSavedUser() {
        when(encoder.encode(REQUEST.password())).thenReturn("test-encoded-hash");
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", SAVED_USER_ID);
            return user;
        });
    }

    /**
     * Builds a synthetic Hibernate integrity failure without real credentials or user values.
     * <p>Preconditions: name identifies the simulated constraint, or is null.
     * <p>Postconditions: a wrapped database exception can exercise conflict classification.
     * @param name simulated constraint name
     * @return wrapped integrity failure
     */
    private DataIntegrityViolationException constraintFailure(String name) {
        SQLException sql = new SQLException("test duplicate", "23000", 1062);
        return new DataIntegrityViolationException("test persistence failure",
                new ConstraintViolationException("test constraint failure", sql, name));
    }
}

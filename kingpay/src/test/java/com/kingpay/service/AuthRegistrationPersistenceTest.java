package com.kingpay.service;

import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.entity.Account;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * Real MySQL registration transactions. No outer test transaction hides commit/rollback behavior.
 * Only this test's uniquely named rows are removed after each scenario.
 */
@SpringBootTest
class AuthRegistrationPersistenceTest {

    private static final int CONCURRENT_REQUESTS = 2;
    private static final int WAIT_SECONDS = 20;

    @Autowired
    private AuthService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private UserRepository users;

    @MockitoSpyBean
    private AccountRepository accounts;

    private final String username = "reg_" + UUID.randomUUID().toString().replace("-", "");

    /**
     * Verifies REG-P01: MySQL rejects a duplicate even when the precheck misses it.
     * <p>Preconditions: one committed registration exists; the existence check is forced false.
     * <p>Postconditions: the second request throws a username conflict; exactly one user/wallet remains.
     */
    @Test
    @DisplayName("REG-P01: translate the real MySQL username constraint after a missed precheck")
    void databaseConstraintProtectsMissedPrecheck() {
        service.register(request());
        doReturn(false).when(users).existsByUsername(username);

        assertThrows(UsernameAlreadyExistsException.class, () -> service.register(request()));

        assertExactlyOneRegistration();
    }

    /**
     * Verifies REG-P02: a wallet insert failure rolls back the preceding real user insert.
     * <p>Preconditions: user persistence works and the account write is forced to fail.
     * <p>Postconditions: neither row exists after the service transaction exits exceptionally.
     */
    @Test
    @DisplayName("REG-P02: roll back the user when wallet persistence fails")
    void walletFailureRollsBackUserInsert() {
        doThrow(new DataIntegrityViolationException("test wallet failure"))
                .when(accounts).saveAndFlush(any(Account.class));

        assertThrows(DataIntegrityViolationException.class, () -> service.register(request()));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts a JOIN users u ON u.id = a.user_id WHERE u.username = ?",
                Long.class, username)).isZero();
    }

    /**
     * Verifies REG-P03: two live requests competing for one username produce one registration.
     * <p>Preconditions: both prechecks are forced false; separate threads invoke the proxied service.
     * <p>Postconditions: one request succeeds, one reports conflict, and one user/zero-balance wallet exists.
     * @throws Exception if a worker fails unexpectedly or cannot finish within the test deadline
     */
    @Test
    @DisplayName("REG-P03: allow exactly one of two simultaneous registrations")
    void simultaneousRegistrationsCreateOnlyOneUserAndWallet() throws Exception {
        doReturn(false).when(users).existsByUsername(username);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        Callable<Boolean> attempt = () -> {
            ready.countDown();
            if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Registration worker did not receive its start signal.");
            }
            try {
                service.register(request());
                return true;
            } catch (UsernameAlreadyExistsException expected) {
                return false;
            }
        };

        try {
            var first = workers.submit(attempt);
            var second = workers.submit(attempt);
            assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> results = List.of(first.get(WAIT_SECONDS, TimeUnit.SECONDS),
                    second.get(WAIT_SECONDS, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertExactlyOneRegistration();
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Removes only records created under this scenario's unique username.
     * <p>Preconditions: workers have finished; no bookings or transfers reference these fixtures.
     * <p>Postconditions: fixture wallet/user rows are removed in one cleanup transaction.
     */
    @AfterEach
    void removeOwnFixtures() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM accounts WHERE user_id IN (SELECT id FROM users WHERE username = ?)", username);
            jdbc.update("DELETE FROM users WHERE username = ?", username);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username))
                .isZero();
    }

    /**
     * Asserts committed registration state through independent JDBC queries.
     * <p>Preconditions: service transactions have completed and one registration should have won.
     * <p>Postconditions: exactly one user and its zero-balance wallet are verified.
     */
    private void assertExactlyOneRegistration() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, username))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts a JOIN users u ON u.id = a.user_id WHERE u.username = ?",
                Long.class, username)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT a.balance FROM accounts a JOIN users u ON u.id = a.user_id WHERE u.username = ?",
                java.math.BigDecimal.class, username)).isEqualByComparingTo("0.00");
    }

    /**
     * Supplies valid synthetic input whose username is unique to this test invocation.
     * <p>Preconditions: none.
     * <p>Postconditions: no entity or database state changes.
     * @return the registration fixture
     */
    private RegisterRequest request() {
        return new RegisterRequest(username, "test-password-123", "Registration Test",
                "00000000000", username + "@example.test", false);
    }
}

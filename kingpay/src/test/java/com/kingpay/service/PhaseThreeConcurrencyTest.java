package com.kingpay.service;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** P3-P: independent committed MySQL transactions; cleanup touches only captured fixture IDs. */
@SpringBootTest
class PhaseThreeConcurrencyTest {
    private static final int WAIT_SECONDS = 25;
    @Autowired private UserService profiles;
    @Autowired private AccountService wallets;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    private String firstName;
    private String secondName;
    private Long firstId;
    private Long secondId;

    /** Preconditions: schema validates. Postconditions: two test identities and zero wallets are committed. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        firstName = "p3r1_" + suffix;
        secondName = "p3r2_" + suffix;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User first = users.saveAndFlush(new User(firstName, "test-hash", "Race One", "0", "test@example.test", false));
            firstId = first.getId();
            accounts.saveAndFlush(new Account(first, new BigDecimal("0.00")));
            User second = users.saveAndFlush(new User(secondName, "test-hash", "Race Two", "0", "test@example.test", false));
            secondId = second.getId();
            accounts.saveAndFlush(new Account(second, new BigDecimal("0.00")));
        });
    }

    /** Preconditions: two simultaneous loads on one wallet. Postconditions: both commits retained, no lost update.
     * @throws Exception on worker failure */
    @Test
    void concurrentLoadsRetainBothAmounts() throws Exception {
        race(() -> { wallets.load(new LoadAccountRequest(new BigDecimal("10.00")), () -> firstName); return "loaded"; },
                () -> { wallets.load(new LoadAccountRequest(new BigDecimal("20.00")), () -> firstName); return "loaded"; });
        assertThat(balance()).isEqualByComparingTo("30.00");
    }

    /** Preconditions: zero-wallet deactivation competes with load. Postconditions: inactive implies zero, funded implies active.
     * @throws Exception on worker failure */
    @Test
    void deactivationAndLoadCannotLeaveInactiveFundedWallet() throws Exception {
        List<String> results = race(() -> {
            try { profiles.deactivateMe(() -> firstName); return "deactivated"; }
            catch (BusinessConflictException exception) { return "balance-conflict"; }
        }, () -> {
            try { wallets.load(new LoadAccountRequest(BigDecimal.ONE), () -> firstName); return "loaded"; }
            catch (AccessDeniedException exception) { return "inactive"; }
        });
        boolean active = jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, firstId);
        if (active) {
            assertThat(results).containsExactlyInAnyOrder("balance-conflict", "loaded");
            assertThat(balance()).isEqualByComparingTo("1.00");
        } else {
            assertThat(results).containsExactlyInAnyOrder("deactivated", "inactive");
            assertThat(balance()).isEqualByComparingTo("0.00");
        }
    }

    /** Preconditions: two users rename to one free name. Postconditions: one winner/conflict and two retained wallets.
     * @throws Exception on worker failure */
    @Test
    void competingRenamesKeepUsernameUnique() throws Exception {
        String target = "p3target_" + UUID.randomUUID().toString().replace("-", "");
        List<String> results = race(() -> rename(firstName, target), () -> rename(secondName, target));
        assertThat(results).containsExactlyInAnyOrder("renamed", "conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, target)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_id IN (?, ?)", Long.class, firstId, secondId)).isEqualTo(2L);
    }

    /** Preconditions: first user has no account. Postconditions: concurrent create yields one wallet and one conflict.
     * @throws Exception on worker failure */
    @Test
    void competingAccountCreationsKeepOneZeroWallet() throws Exception {
        jdbc.update("DELETE FROM accounts WHERE user_id = ?", firstId);
        Callable<String> create = () -> {
            try { wallets.create(() -> firstName); return "created"; }
            catch (BusinessConflictException exception) { return "conflict"; }
        };
        assertThat(race(create, create)).containsExactlyInAnyOrder("created", "conflict");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE user_id = ?", Long.class, firstId)).isEqualTo(1L);
        assertThat(balance()).isEqualByComparingTo("0.00");
    }

    /** Preconditions: fixtures may have committed. Postconditions: only captured test IDs removed, including renamed users. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM accounts WHERE user_id IN (?, ?)", firstId, secondId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", firstId, secondId);
    }

    /** Preconditions: fixture wallet committed. Postconditions: no writes.
     * @return observed committed balance */
    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE user_id = ?", BigDecimal.class, firstId);
    }

    /** Preconditions: source identity and target supplied. Postconditions: returns explicit outcome, unexpected failures propagate.
     * @param source original username
     * @param target shared desired username
     * @return rename or conflict outcome */
    private String rename(String source, String target) {
        try {
            profiles.updateMe(new UpdateUserRequest(target, "Renamed", "0", "test@example.test", false), () -> source);
            return "renamed";
        } catch (UsernameAlreadyExistsException exception) { return "conflict"; }
    }

    /** Preconditions: tasks use separate service transactions. Postconditions: workers end before cleanup; failures propagate.
     * @param first first operation
     * @param second competing operation
     * @return both outcomes
     * @throws Exception on worker failure or timeout */
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) { throw new IllegalStateException("Start timed out."); }
                return task.call();
            })).toList();
            if (!ready.await(WAIT_SECONDS, TimeUnit.SECONDS)) { throw new IllegalStateException("Workers timed out."); }
            start.countDown();
            return List.of(futures.get(0).get(WAIT_SECONDS, TimeUnit.SECONDS), futures.get(1).get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }
}

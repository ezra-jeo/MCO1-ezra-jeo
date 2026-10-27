package com.kingpay.service;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.request.TransferRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InsufficientBalanceException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jayway.jsonpath.JsonPath.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** P4-H/P/C: real HTTP/security/MySQL; no outer transaction hides commits or rollbacks. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class TransferPersistenceTest {
    private static final int WAIT_SECONDS = 30;
    @Autowired private MockMvc mvc;
    @Autowired private TransferService service;
    @Autowired private AccountService wallets;
    @Autowired private UserService profiles;
    @Autowired private TokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private TransferRecordRepository ledger;
    private User sender;
    private User recipient;
    private User outsider;
    private Long sourceId;
    private Long destinationId;
    private Long outsiderAccountId;

    /** Preconditions: schema validates. Postconditions: independent committed fixtures have balances 1000/500/0. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            sender = users.saveAndFlush(identity("p4s_" + suffix));
            recipient = users.saveAndFlush(identity("p4r_" + suffix));
            outsider = users.saveAndFlush(identity("p4o_" + suffix));
            sourceId = accounts.saveAndFlush(new Account(sender, new BigDecimal("1000.00"))).getId();
            destinationId = accounts.saveAndFlush(new Account(recipient, new BigDecimal("500.00"))).getId();
            outsiderAccountId = accounts.saveAndFlush(new Account(outsider, new BigDecimal("0.00"))).getId();
        });
    }

    /** Preconditions: owner transfers 100 from 1000 to 500. Postconditions: 900/600 and one correct committed ledger row.
     * @throws Exception on HTTP failure */
    @Test
    void validHttpTransferCommitsBothBalancesAndOneRecord() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        var result = mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, destinationId, "100.00")), sender))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Transfer successful"))
                .andExpect(jsonPath("$.data.sourceAccountId").value(sourceId))
                .andExpect(jsonPath("$.data.destinationAccountId").value(destinationId))
                .andExpect(jsonPath("$.data.amount").value(100.00)).andReturn();
        Number id = read(result.getResponse().getContentAsString(), "$.data.id");
        TransferRecord saved = ledger.findById(id.longValue()).orElseThrow();
        assertThat(saved.getAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getTransferDate()).isBetween(before, Instant.now().plusSeconds(1));
        assertBalances("900.00", "600.00", 1);
        assertThat(jdbc.queryForObject("SELECT source_account_id FROM transfer_records WHERE id = ?", Long.class, id.longValue())).isEqualTo(sourceId);
        assertThat(jdbc.queryForObject("SELECT destination_account_id FROM transfer_records WHERE id = ?", Long.class, id.longValue())).isEqualTo(destinationId);
    }

    /** Preconditions: valid owner bearer with malformed transfer fields. Postconditions: safe 400 and no committed effects.
     * @param shape missing or invalid request shape
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"missing-source", "missing-destination", "missing-amount", "null-amount", "zero", "negative", "bad-source", "bad-destination", "fraction", "overflow", "malformed"})
    void invalidHttpInputsLeaveMoneyAndLedgerUnchanged(String shape) throws Exception {
        String json = switch (shape) {
            case "missing-source" -> "{\"toId\":" + destinationId + ",\"amount\":1}";
            case "missing-destination" -> "{\"fromId\":" + sourceId + ",\"amount\":1}";
            case "missing-amount" -> "{\"fromId\":" + sourceId + ",\"toId\":" + destinationId + "}";
            case "null-amount" -> body(sourceId, destinationId, "null");
            case "zero" -> body(sourceId, destinationId, "0");
            case "negative" -> body(sourceId, destinationId, "-1");
            case "bad-source" -> body(0L, destinationId, "1");
            case "bad-destination" -> body(sourceId, -1L, "1");
            case "fraction" -> body(sourceId, destinationId, "1.001");
            case "overflow" -> body(sourceId, destinationId, "100000000000000000");
            default -> "{broken";
        };
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json), sender))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: authenticated owner supplies absent source/destination. Postconditions: 404 and no partial effects.
     * @param missingSource whether source rather than destination is absent
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingAccountDoesNotChangeBalances(boolean missingSource) throws Exception {
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(missingSource ? Long.MAX_VALUE : sourceId, missingSource ? destinationId : Long.MAX_VALUE, "100")), sender))
                .andExpect(status().isNotFound());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: debit exceeds balance. Postconditions: documented 409 and no committed changes.
     * @throws Exception on HTTP failure */
    @Test
    void insufficientFundsReturnSafeConflict() throws Exception {
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, destinationId, "1000.01")), sender))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Insufficient funds."));
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: caller spoofs the other wallet owner in body. Postconditions: 403, no spending or disclosure.
     * @throws Exception on HTTP failure */
    @Test
    void spoofedBodyIdentityCannotDebitAnotherOwner() throws Exception {
        String json = body(destinationId, sourceId, "100").replace("}", ",\"authenticatedUsername\":\"" + recipient.getUsername() + "\"}");
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json), sender))
                .andExpect(status().isForbidden());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: same wallet appears twice. Postconditions: 400, no artificial ledger or changed funds.
     * @throws Exception on HTTP failure */
    @Test
    void selfTransferIsRejected() throws Exception {
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, sourceId, "100")), sender)).andExpect(status().isBadRequest());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: recipient disabled. Postconditions: 409 and unchanged money.
     * @throws Exception on HTTP failure */
    @Test
    void inactiveDestinationCannotReceive() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> users.findById(recipient.getId()).orElseThrow().deactivate());
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, destinationId, "100")), sender)).andExpect(status().isConflict());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: sender token exists before deactivation. Postconditions: transfer and history both reject 401.
     * @throws Exception on HTTP failure */
    @Test
    void inactiveSourceBearerCannotTransferOrReadHistory() throws Exception {
        String token = tokens.issue(sender).token();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> users.findById(sender.getId()).orElseThrow().deactivate());
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, destinationId, "100"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/transfers/history/" + sourceId).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: recipient wallet at capacity. Postconditions: conflict before any committed debit.
     * @throws Exception on HTTP failure */
    @Test
    void destinationOverflowRollsBackWithoutDebit() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                accounts.findById(destinationId).orElseThrow().credit(new BigDecimal("99999999999999499.99")));
        mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, destinationId, "0.01")), sender)).andExpect(status().isConflict());
        assertBalances("1000.00", "99999999999999999.99", 0);
    }

    /** Preconditions: ledger insertion fails after real flushed updates. Postconditions: ended transaction restores both balances.
     * A JDBC read inside the failing transaction proves 900/600 had reached persistence before rollback. */
    @Test
    void ledgerFailureAfterFlushedBalancesRollsBackCommittedState() {
        AtomicBoolean sawFlushedBalances = failLedgerAfterObservingWrites();
        assertThrows(DataIntegrityViolationException.class,
                () -> service.transferMoney(new TransferRequest(sourceId, destinationId, new BigDecimal("100.00")), () -> sender.getUsername()));
        assertThat(sawFlushedBalances).isTrue();
        assertBalances("1000.00", "500.00", 0);
        assertThat(service.getTransferHistory(sourceId, () -> sender.getUsername())).isEmpty();
    }

    /** Preconditions: same ledger failure reached through real HTTP. Postconditions: sanitized 500 and committed state restored.
     * @throws Exception on HTTP failure */
    @Test
    void httpPersistenceFailureIsSafeAndAtomic() throws Exception {
        AtomicBoolean sawFlushedBalances = failLedgerAfterObservingWrites();
        var result = mvc.perform(auth(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, destinationId, "100")), sender))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("Unable to complete request."))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic", "SQL", "Exception");
        assertThat(sawFlushedBalances).isTrue();
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: outgoing, incoming and unrelated successful transfers exist. Postconditions: private ordered DTO history.
     * @throws Exception on HTTP failure */
    @Test
    void historyIncludesBothDirectionsAndExcludesOtherAccounts() throws Exception {
        var first = service.transferMoney(new TransferRequest(sourceId, destinationId, new BigDecimal("100")), () -> sender.getUsername());
        var second = service.transferMoney(new TransferRequest(destinationId, sourceId, new BigDecimal("50")), () -> recipient.getUsername());
        var unrelated = service.transferMoney(new TransferRequest(destinationId, outsiderAccountId, new BigDecimal("10")), () -> recipient.getUsername());
        mvc.perform(auth(get("/api/v1/transfers/history/" + sourceId), sender)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].id").value(second.id()))
                .andExpect(jsonPath("$.data[1].id").value(first.id())).andExpect(jsonPath("$.data[0].amount").value(50))
                .andExpect(jsonPath("$.data[0].balance").doesNotExist()).andExpect(jsonPath("$.data[0].passwordHash").doesNotExist());
        mvc.perform(auth(get("/api/v1/transfers/history/" + outsiderAccountId), outsider)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(unrelated.id()));
    }

    /** Preconditions: owner has no transfers. Postconditions: 200 empty array. */
    @Test
    void emptyHistoryIsAnEmptyArray() throws Exception {
        mvc.perform(auth(get("/api/v1/transfers/history/" + sourceId), sender))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty());
    }

    /** Preconditions: history ID is foreign or absent. Postconditions: 403/404, no state changes.
     * @throws Exception on HTTP failure */
    @Test
    void forbiddenAndMissingHistoryHaveSafeErrors() throws Exception {
        mvc.perform(auth(get("/api/v1/transfers/history/" + destinationId), sender)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/transfers/history/" + Long.MAX_VALUE), sender)).andExpect(status().isNotFound());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: no bearer. Postconditions: both endpoints require authentication.
     * @throws Exception on HTTP failure */
    @Test
    void unauthenticatedTransferAndHistoryFail() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(body(sourceId, destinationId, "1")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/transfers/history/" + sourceId)).andExpect(status().isUnauthorized());
        assertBalances("1000.00", "500.00", 0);
    }

    /** Preconditions: malformed history ID. Postconditions: safe 400 without ledger disclosure.
     * @param id malformed/nonpositive path value
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"not-an-id", "0", "-1"})
    void invalidHistoryIdsAreRejected(String id) throws Exception {
        mvc.perform(auth(get("/api/v1/transfers/history/" + id), sender)).andExpect(status().isBadRequest());
    }

    /** Preconditions: a successful ledger row exists. Postconditions: PUT/PATCH/DELETE have no API mutation route.
     * @param method unsupported mutation verb
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void ledgerCannotBeEditedOrDeleted(String method) throws Exception {
        var saved = service.transferMoney(new TransferRequest(sourceId, destinationId, BigDecimal.ONE), () -> sender.getUsername());
        mvc.perform(auth(request(HttpMethod.valueOf(method), "/api/v1/transfers/" + saved.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":999}"), sender)).andExpect(status().is4xxClientError());
        assertBalances("999.00", "501.00", 1);
        assertThat(ledger.findById(saved.id()).orElseThrow().getAmount()).isEqualByComparingTo("1.00");
    }

    /** Preconditions: two 600 transfers compete for 1000. Postconditions: one wins, one insufficient, conservation.
     * @throws Exception on worker failure */
    @Test
    void concurrentSpendingCannotOverdraw() throws Exception {
        Callable<String> spend = () -> spend(new BigDecimal("600"));
        assertThat(race(List.of(spend, spend))).containsExactlyInAnyOrder("transferred", "insufficient");
        assertBalances("400.00", "1100.00", 1);
    }

    /** Preconditions: eight 200 attempts compete for 1000. Postconditions: five wins, three insufficient, exact zero.
     * @throws Exception on worker failure */
    @Test
    void repeatedConcurrentSpendingPreservesTotal() throws Exception {
        List<Callable<String>> attempts = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (Callable<String>) () -> spend(new BigDecimal("200"))).toList();
        List<String> results = race(attempts);
        assertThat(results.stream().filter("transferred"::equals).count()).isEqualTo(5);
        assertThat(results.stream().filter("insufficient"::equals).count()).isEqualTo(3);
        assertBalances("0.00", "1500.00", 5);
    }

    /** Preconditions: opposite-direction transfers compete. Postconditions: no deadlock, original totals and two records.
     * @throws Exception on worker failure */
    @Test
    void opposingTransfersUseConsistentLockOrder() throws Exception {
        Callable<String> forward = () -> spend(new BigDecimal("100"));
        Callable<String> reverse = () -> {
            service.transferMoney(new TransferRequest(destinationId, sourceId, new BigDecimal("100")), () -> recipient.getUsername());
            return "transferred";
        };
        assertThat(race(List.of(forward, reverse))).containsOnly("transferred");
        assertBalances("1000.00", "500.00", 2);
    }

    /** Preconditions: receiving wallet is loaded concurrently. Postconditions: transfer credit and load both retained.
     * @throws Exception on worker failure */
    @Test
    void concurrentLoadingAndTransferRetainBothCredits() throws Exception {
        Callable<String> transfer = () -> spend(new BigDecimal("100"));
        Callable<String> load = () -> {
            wallets.load(new LoadAccountRequest(new BigDecimal("25")), () -> recipient.getUsername());
            return "loaded";
        };
        assertThat(race(List.of(transfer, load))).containsExactlyInAnyOrder("transferred", "loaded");
        assertBalances("900.00", "625.00", 1);
    }

    /** Preconditions: zero recipient deactivation races with incoming transfer. Postconditions: inactive implies no receipt.
     * @throws Exception on worker failure */
    @Test
    void concurrentRecipientDeactivationCannotFreezeReceivedFunds() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> accounts.findById(destinationId).orElseThrow().debit(new BigDecimal("500")));
        Callable<String> incoming = () -> {
            try { return spend(new BigDecimal("100")); }
            catch (BusinessConflictException exception) { return "inactive"; }
        };
        Callable<String> deactivate = () -> {
            try { profiles.deactivateMe(() -> recipient.getUsername()); return "deactivated"; }
            catch (BusinessConflictException exception) { return "funded"; }
        };
        List<String> results = race(List.of(incoming, deactivate));
        boolean active = jdbc.queryForObject("SELECT active FROM users WHERE id = ?", Boolean.class, recipient.getId());
        if (active) {
            assertThat(results).containsExactlyInAnyOrder("transferred", "funded");
            assertBalances("900.00", "100.00", 1);
        } else {
            assertThat(results).containsExactlyInAnyOrder("inactive", "deactivated");
            assertBalances("1000.00", "0.00", 0);
        }
    }

    /** Preconditions: fixtures may have committed. Postconditions: only captured test rows removed; demo rows untouched. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM transfer_records WHERE source_account_id IN (?, ?, ?) OR destination_account_id IN (?, ?, ?)",
                sourceId, destinationId, outsiderAccountId, sourceId, destinationId, outsiderAccountId);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", sourceId, destinationId, outsiderAccountId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", sender == null ? null : sender.getId(),
                recipient == null ? null : recipient.getId(), outsider == null ? null : outsider.getId());
    }

    /** Preconditions: no ledger failure injected. Postconditions: confirms flushed updates before forcing rollback.
     * @return flag set only after observing both actual balance writes */
    private AtomicBoolean failLedgerAfterObservingWrites() {
        AtomicBoolean observed = new AtomicBoolean();
        doAnswer(call -> {
            assertThat(balance(sourceId)).isEqualByComparingTo("900.00");
            assertThat(balance(destinationId)).isEqualByComparingTo("600.00");
            observed.set(true);
            // Force an actual MySQL foreign-key failure after the real wallet updates were flushed.
            jdbc.update("INSERT INTO transfer_records (source_account_id, destination_account_id, amount, transfer_date) VALUES (?, ?, ?, ?)",
                    sourceId, Long.MAX_VALUE, new BigDecimal("100.00"), java.sql.Timestamp.from(Instant.now()));
            throw new AssertionError("Invalid ledger destination was unexpectedly accepted.");
        }).when(ledger).saveAndFlush(any(TransferRecord.class));
        return observed;
    }

    /** Preconditions: fixture source active. Postconditions: returns expected outcome; unexpected failures propagate.
     * @param amount attempted amount
     * @return transfer or insufficient outcome */
    private String spend(BigDecimal amount) {
        try {
            service.transferMoney(new TransferRequest(sourceId, destinationId, amount), () -> sender.getUsername());
            return "transferred";
        } catch (InsufficientBalanceException exception) { return "insufficient"; }
    }

    /** Preconditions: requests use independent service transactions. Postconditions: all workers finish before cleanup.
     * @param tasks simultaneous operations
     * @return outcomes in submission order
     * @throws Exception on timeout or unexpected failure */
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

    /** Preconditions: row exists. Postconditions: no writes; reads transaction-visible balance directly through JDBC.
     * @param id wallet ID
     * @return persisted balance */
    private BigDecimal balance(Long id) { return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id); }

    /** Preconditions: transaction under test has ended. Postconditions: verifies database balances and fixture ledger count.
     * @param source expected source balance
     * @param destination expected destination balance
     * @param count expected successful ledger rows */
    private void assertBalances(String source, String destination, long count) {
        assertThat(balance(sourceId)).isEqualByComparingTo(source);
        assertThat(balance(destinationId)).isEqualByComparingTo(destination);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_records WHERE source_account_id IN (?, ?, ?) OR destination_account_id IN (?, ?, ?)",
                Long.class, sourceId, destinationId, outsiderAccountId, sourceId, destinationId, outsiderAccountId)).isEqualTo(count);
    }

    /** Preconditions: safe synthetic name. Postconditions: unsaved test identity returned.
     * @param name unique username
     * @return fixture user */
    private User identity(String name) { return new User(name, "test-only-hash", "Transfer Test", "0", "test@example.test", false); }

    /** Preconditions: values are test-controlled. Postconditions: request JSON contains exactly the supplied transfer.
     * @param from source ID
     * @param to destination ID
     * @param amount JSON numeric/null literal
     * @return JSON body */
    private String body(Long from, Long to, String amount) { return "{\"fromId\":%d,\"toId\":%d,\"amount\":%s}".formatted(from, to, amount); }

    /** Preconditions: user is a persisted fixture. Postconditions: request has genuine signed bearer, never printed.
     * @param request HTTP operation
     * @param user caller identity
     * @return authenticated request */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header("Authorization", "Bearer " + tokens.issue(user).token());
    }
}

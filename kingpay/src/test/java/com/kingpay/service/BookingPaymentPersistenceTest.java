package com.kingpay.service;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.InsufficientBalanceException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP and committed MySQL payment/rollback/race tests; no outer test transaction. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BookingPaymentPersistenceTest {
    private static final int WAIT_SECONDS = 30;
    enum FailureStage { SOURCE, DESTINATION, LEDGER, BOOKING }
    @Autowired private MockMvc mvc;
    @Autowired private BookingService service;
    @Autowired private TransferService transfers;
    @Autowired private AccountService wallets;
    @Autowired private UserService profiles;
    @Autowired private TokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AccountRepository accounts;
    @MockitoSpyBean private BookingRepository bookings;
    @MockitoSpyBean private TransferRecordRepository ledger;
    private User customer;
    private User provider;
    private User outsider;
    private Long sourceId;
    private Long destinationId;
    private Long outsiderId;
    private long bookingId;

    /** Preconditions: valid schema. Postconditions: committed 1000/500/0 wallets and a confirmed unpaid quote of 100. */
    @BeforeEach
    void fixtures() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            customer = users.saveAndFlush(identity("p6c_" + suffix, false));
            provider = users.saveAndFlush(identity("p6p_" + suffix, true));
            outsider = users.saveAndFlush(identity("p6o_" + suffix, false));
            sourceId = accounts.saveAndFlush(new Account(customer, new BigDecimal("1000.00"))).getId();
            destinationId = accounts.saveAndFlush(new Account(provider, new BigDecimal("500.00"))).getId();
            outsiderId = accounts.saveAndFlush(new Account(outsider, BigDecimal.ZERO)).getId();
        });
        bookingId = confirmedQuote("100");
    }

    /** Preconditions: completed quote; caller forges replacement financial fields. Postconditions: fixed transfer/link commit.
     * @throws Exception on HTTP failure */
    @Test
    void customerPaysFixedPriceAndLinksCommittedTransfer() throws Exception {
        mvc.perform(auth(pay().contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"fromId\":" + outsiderId
                        + ",\"toId\":" + outsiderId + ",\"status\":\"ConfirmedPaid\",\"paymentTransferId\":999}"), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ConfirmedPaid"))
                .andExpect(jsonPath("$.data.amount").value(100.00)).andExpect(jsonPath("$.data.paymentTransferId").isNumber());
        assertMoney("900", "600", 1);
        assertPaid(bookingId, "100");
        Long link = paymentLink(bookingId);
        mvc.perform(auth(get("/api/v1/transfers/history/" + sourceId), customer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(link));
        mvc.perform(auth(get("/api/v1/transfers/history/" + destinationId), provider))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(link));
    }

    /** Preconditions: quote has neither/one completion. Postconditions: 409 and no financial effects.
     * @param state nonpayable state
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(value = BookingStatus.class, names = {"Unconfirmed", "CustomerConfirmed", "ServiceProviderConfirmed"})
    void incompletePaymentIsRejected(BookingStatus state) throws Exception {
        jdbc.update("UPDATE bookings SET status = ? WHERE id = ?", state.name(), bookingId);
        mvc.perform(auth(pay(), customer)).andExpect(status().isConflict());
        assertMoney("1000", "500", 0);
        assertThat(state(bookingId)).isEqualTo(state.name());
        assertThat(paymentLink(bookingId)).isNull();
    }

    /** Preconditions: provider/outsider attempts payment. Postconditions: 403 without charge.
     * @param providerSide which wrong actor
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void onlyCustomerCanAuthorizePayment(boolean providerSide) throws Exception {
        mvc.perform(auth(pay(), providerSide ? provider : outsider)).andExpect(status().isForbidden());
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: absent bearer. Postconditions: 401 without effects.
     * @throws Exception on HTTP failure */
    @Test
    void paymentNeedsAuthentication() throws Exception {
        mvc.perform(pay()).andExpect(status().isUnauthorized());
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: token predates forced customer disable. Postconditions: current activity rejects with 401.
     * @throws Exception on HTTP failure */
    @Test
    void disabledCustomerTokenCannotPay() throws Exception {
        var request = auth(pay(), customer);
        jdbc.update("UPDATE users SET active = false WHERE id = ?", customer.getId());
        mvc.perform(request).andExpect(status().isUnauthorized());
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: absent booking ID. Postconditions: 404 without financial effects.
     * @throws Exception on HTTP failure */
    @Test
    void missingBookingCannotPay() throws Exception {
        mvc.perform(auth(post("/api/v1/bookings/" + Long.MAX_VALUE + "/payment"), customer)).andExpect(status().isNotFound());
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: malformed/nonpositive path. Postconditions: safe 400.
     * @param id invalid identifier
     * @throws Exception on HTTP failure */
    @ParameterizedTest @ValueSource(strings = {"not-an-id", "0", "-1"})
    void invalidPaymentIdsFail(String id) throws Exception {
        mvc.perform(auth(post("/api/v1/bookings/" + id + "/payment"), customer)).andExpect(status().isBadRequest());
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: paid booking. Postconditions: repeated requests return original ID and no additional debit.
     * @throws Exception on HTTP failure */
    @Test
    void successfulRetriesReturnOriginalPayment() throws Exception {
        long original = service.pay(bookingId, () -> customer.getUsername()).paymentTransferId();
        for (int attempt = 0; attempt < 3; attempt++) {
            mvc.perform(auth(pay(), customer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentTransferId").value(original));
        }
        assertMoney("900", "600", 1);
        assertPaid(bookingId, "100");
    }

    /** Preconditions: price exceeds funds. Postconditions: unchanged on failure; load then retry commits once.
     * @throws Exception on HTTP failure */
    @Test
    void insufficientPaymentCanRetryAfterLoading() throws Exception {
        jdbc.update("UPDATE bookings SET amount = 1100 WHERE id = ?", bookingId);
        mvc.perform(auth(pay(), customer)).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Insufficient funds."));
        assertUnpaidAndUnchanged();
        mvc.perform(auth(post("/api/v1/accounts/me/load").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100}"), customer))
                .andExpect(status().isOk());
        mvc.perform(auth(pay(), customer)).andExpect(status().isOk());
        assertMoney("0", "1600", 1);
        assertPaid(bookingId, "1100");
    }

    /** Preconditions: recipient at storage capacity. Postconditions: conflict before debit.
     * @throws Exception on HTTP failure */
    @Test
    void destinationCapacityRejectsPaymentAtomically() throws Exception {
        jdbc.update("UPDATE accounts SET balance = 99999999999999999.99 WHERE id = ?", destinationId);
        mvc.perform(auth(pay(), customer)).andExpect(status().isConflict());
        assertMoney("1000", "99999999999999999.99", 0);
        assertThat(state(bookingId)).isEqualTo("ConfirmedUnpaid");
        assertThat(paymentLink(bookingId)).isNull();
    }

    /** Preconditions: recipient disabled directly as an exceptional fixture. Postconditions: conflict, no charge.
     * @throws Exception on HTTP failure */
    @Test
    void inactiveRecipientCannotReceivePayment() throws Exception {
        jdbc.update("UPDATE users SET active = false WHERE id = ?", provider.getId());
        mvc.perform(auth(pay(), customer)).andExpect(status().isConflict());
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: minimum/full-wallet quote. Postconditions: exact conserved funds and one linked payment.
     * @param price quote boundary */
    @ParameterizedTest @ValueSource(strings = {"0.01", "1000.00"})
    void paymentHandlesExactPriceBoundaries(String price) {
        jdbc.update("UPDATE bookings SET amount = ? WHERE id = ?", new BigDecimal(price), bookingId);
        service.pay(bookingId, () -> customer.getUsername());
        assertMoney(new BigDecimal("1000").subtract(new BigDecimal(price)).toPlainString(),
                new BigDecimal("500").add(new BigDecimal(price)).toPlainString(), 1);
        assertPaid(bookingId, price);
    }

    /** Preconditions: customer independently transfers the same price. Postconditions: no implicit booking payment. */
    @Test
    void standaloneTransferCannotMarkBookingPaid() {
        transfers.transferMoney(new TransferRequest(sourceId, destinationId, new BigDecimal("100")), () -> customer.getUsername());
        assertMoney("900", "600", 1);
        assertThat(state(bookingId)).isEqualTo("ConfirmedUnpaid");
        assertThat(paymentLink(bookingId)).isNull();
        service.pay(bookingId, () -> customer.getUsername());
        assertMoney("800", "700", 2);
        assertPaid(bookingId, "100");
    }

    /** Preconditions: existing token predates rename. Postconditions: stable-ID authorization still permits payment.
     * @throws Exception on HTTP failure */
    @Test
    void renamedCustomerCanPayWithExistingToken() throws Exception {
        var request = auth(pay(), customer);
        profiles.updateMe(new UpdateUserRequest("p6new_" + UUID.randomUUID().toString().replace("-", ""), "Renamed", "0", "test@example.test", false),
                () -> customer.getUsername());
        mvc.perform(request).andExpect(status().isOk());
        assertMoney("900", "600", 1);
    }

    /** Preconditions: selected component fails after actual writes are flushed to MySQL.
     * Postconditions: wallet, ledger and booking writes all roll back; HTTP does not disclose SQL.
     * @param stage wallet/ledger/booking failure point
     * @throws Exception on HTTP failure */
    @ParameterizedTest @EnumSource(FailureStage.class)
    void mysqlFailureRollsBackEveryPaymentComponent(FailureStage stage) throws Exception {
        AtomicBoolean observed = new AtomicBoolean();
        if (stage == FailureStage.SOURCE || stage == FailureStage.DESTINATION) {
            doAnswer(call -> {
                Account wallet = call.getArgument(0);
                entities.flush();
                if (wallet.getId().equals(stage == FailureStage.SOURCE ? sourceId : destinationId)) {
                    assertThat(balance(sourceId)).isEqualByComparingTo("900");
                    assertThat(balance(destinationId)).isEqualByComparingTo(stage == FailureStage.SOURCE ? "500" : "600");
                    failInMysql(observed);
                }
                return wallet;
            }).when(accounts).saveAndFlush(any(Account.class));
        } else if (stage == FailureStage.LEDGER) {
            doAnswer(call -> {
                assertMoney("900", "600", 0);
                failInMysql(observed);
                return null;
            }).when(ledger).saveAndFlush(any(TransferRecord.class));
        } else {
            doAnswer(call -> {
                entities.flush();
                assertMoney("900", "600", 1);
                assertPaid(bookingId, "100");
                failInMysql(observed);
                return null;
            }).when(bookings).saveAndFlush(any(Booking.class));
        }
        mvc.perform(auth(pay(), customer)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to complete request."));
        assertThat(observed).isTrue();
        assertUnpaidAndUnchanged();
    }

    /** Preconditions: six customer requests race one job. Postconditions: all return same link; one charge.
     * @throws Exception on worker failure */
    @Test
    void simultaneousRetriesChargeAtMostOnce() throws Exception {
        Callable<String> pay = () -> service.pay(bookingId, () -> customer.getUsername()).paymentTransferId().toString();
        List<String> results = race(List.of(pay, pay, pay, pay, pay, pay));
        assertThat(results.stream().distinct().count()).isEqualTo(1);
        assertMoney("900", "600", 1);
        assertPaid(bookingId, "100");
    }

    /** Preconditions: two quotes of 600 compete for 1000. Postconditions: exactly one paid; no overdraft.
     * @throws Exception on worker failure */
    @Test
    void competingBookingsCannotOverspend() throws Exception {
        jdbc.update("UPDATE bookings SET amount = 600 WHERE id = ?", bookingId);
        long second = confirmedQuote("600");
        assertThat(race(List.of(() -> attemptPayment(bookingId), () -> attemptPayment(second))))
                .containsExactlyInAnyOrder("paid", "insufficient");
        assertMoney("400", "1100", 1);
        assertThat(List.of(state(bookingId), state(second))).containsExactlyInAnyOrder("ConfirmedPaid", "ConfirmedUnpaid");
    }

    /** Preconditions: booking and standalone transfer both spend 600 from 1000. Postconditions: one succeeds.
     * @throws Exception on worker failure */
    @Test
    void bookingAndStandaloneTransferShareSpendingLocks() throws Exception {
        jdbc.update("UPDATE bookings SET amount = 600 WHERE id = ?", bookingId);
        Callable<String> transfer = () -> {
            try { transfers.transferMoney(new TransferRequest(sourceId, destinationId, new BigDecimal("600")), () -> customer.getUsername()); return "paid"; }
            catch (InsufficientBalanceException exception) { return "insufficient"; }
        };
        assertThat(race(List.of(() -> attemptPayment(bookingId), transfer))).containsExactlyInAnyOrder("paid", "insufficient");
        assertMoney("400", "1100", 1);
        assertThat(paymentLink(bookingId) == null).isEqualTo(state(bookingId).equals("ConfirmedUnpaid"));
    }

    /** Preconditions: payment races a recipient load. Postconditions: both credits retained.
     * @throws Exception on worker failure */
    @Test
    void loadingAndPaymentRetainBothCredits() throws Exception {
        race(List.of(() -> attemptPayment(bookingId), () -> {
            wallets.load(new LoadAccountRequest(new BigDecimal("25")), () -> provider.getUsername()); return "loaded";
        }));
        assertMoney("900", "625", 1);
        assertPaid(bookingId, "100");
    }

    /** Preconditions: payment races repeated confirmations. Postconditions: paid state/link never reversed.
     * @throws Exception on worker failure */
    @Test
    void paymentAndConfirmationRepeatsPreservePaidState() throws Exception {
        race(List.of(() -> attemptPayment(bookingId), () -> { service.confirmCustomer(bookingId, () -> customer.getUsername()); return "confirmed"; },
                () -> { service.confirmServiceProvider(bookingId, () -> provider.getUsername()); return "confirmed"; }));
        assertMoney("900", "600", 1);
        assertPaid(bookingId, "100");
    }

    /** Preconditions: payment and reverse standalone transfer compete. Postconditions: no lock inversion, both commit.
     * @throws Exception on worker failure */
    @Test
    void reverseTransferAndPaymentUseConsistentOwnerOrder() throws Exception {
        race(List.of(() -> attemptPayment(bookingId), () -> {
            transfers.transferMoney(new TransferRequest(destinationId, sourceId, new BigDecimal("100")), () -> provider.getUsername()); return "transferred";
        }));
        assertMoney("1000", "500", 2);
        assertPaid(bookingId, "100");
    }

    /** Preconditions: fixtures may have committed. Postconditions: only captured test rows removed. */
    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM bookings WHERE customer_account_id IN (?, ?, ?) OR provider_account_id IN (?, ?, ?)", sourceId, destinationId, outsiderId, sourceId, destinationId, outsiderId);
        jdbc.update("DELETE FROM transfer_records WHERE source_account_id IN (?, ?, ?) OR destination_account_id IN (?, ?, ?)", sourceId, destinationId, outsiderId, sourceId, destinationId, outsiderId);
        jdbc.update("DELETE FROM accounts WHERE id IN (?, ?, ?)", sourceId, destinationId, outsiderId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", customer == null ? null : customer.getId(), provider == null ? null : provider.getId(), outsider == null ? null : outsider.getId());
    }

    /** Preconditions: active fixture parties. Postconditions: committed unpaid quote, no funds moved.
     * @param price exact quote
     * @return booking ID */
    private long confirmedQuote(String price) {
        long id = new TransactionTemplate(transactionManager).execute(status -> bookings.saveAndFlush(new Booking(
                accounts.findById(sourceId).orElseThrow(), accounts.findById(destinationId).orElseThrow(), new BigDecimal(price))).getId());
        service.confirmCustomer(id, () -> customer.getUsername());
        service.confirmServiceProvider(id, () -> provider.getUsername());
        return id;
    }

    /** Preconditions: fixture exists. Postconditions: no writes.
     * @return payment request without authentication */
    private MockHttpServletRequestBuilder pay() { return post("/api/v1/bookings/" + bookingId + "/payment"); }

    /** Preconditions: active persisted identity. Postconditions: request receives real JWT; token is never logged.
     * @param request route
     * @param actor identity
     * @return authenticated request */
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User actor) { return request.header("Authorization", "Bearer " + tokens.issue(actor).token()); }

    /** Preconditions: fixture ID. Postconditions: no writes.
     * @param id wallet ID
     * @return current committed/transaction-local balance */
    private BigDecimal balance(Long id) { return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id); }

    /** Preconditions: booking exists. Postconditions: no writes.
     * @param id booking ID
     * @return persisted status */
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, id); }

    /** Preconditions: booking exists. Postconditions: no writes.
     * @param id booking ID
     * @return nullable transfer ID */
    private Long paymentLink(long id) { return jdbc.queryForObject("SELECT payment_transfer_id FROM bookings WHERE id = ?", Long.class, id); }

    /** Preconditions: operation ended or deliberately flushed. Postconditions: exact funds and scoped ledger count checked.
     * @param source expected customer funds
     * @param destination expected provider funds
     * @param count expected fixture records */
    private void assertMoney(String source, String destination, long count) {
        assertThat(balance(sourceId)).isEqualByComparingTo(source);
        assertThat(balance(destinationId)).isEqualByComparingTo(destination);
        assertThat(balance(outsiderId)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfer_records WHERE source_account_id IN (?, ?, ?) OR destination_account_id IN (?, ?, ?)",
                Long.class, sourceId, destinationId, outsiderId, sourceId, destinationId, outsiderId)).isEqualTo(count);
    }

    /** Preconditions: successful payment expected. Postconditions: correct stored link, parties and amount checked.
     * @param id paid booking
     * @param price expected exact price */
    private void assertPaid(long id, String price) {
        assertThat(state(id)).isEqualTo("ConfirmedPaid");
        Long link = paymentLink(id);
        assertThat(link).isNotNull();
        assertThat(jdbc.queryForObject("SELECT source_account_id FROM transfer_records WHERE id = ?", Long.class, link)).isEqualTo(sourceId);
        assertThat(jdbc.queryForObject("SELECT destination_account_id FROM transfer_records WHERE id = ?", Long.class, link)).isEqualTo(destinationId);
        assertThat(jdbc.queryForObject("SELECT amount FROM transfer_records WHERE id = ?", BigDecimal.class, link)).isEqualByComparingTo(price);
    }

    /** Preconditions: failed default payment. Postconditions: all four components unchanged. */
    private void assertUnpaidAndUnchanged() {
        assertMoney("1000", "500", 0);
        assertThat(state(bookingId)).isEqualTo("ConfirmedUnpaid");
        assertThat(paymentLink(bookingId)).isNull();
    }

    /** Preconditions: previous real writes observed inside transaction. Postconditions: MySQL FK failure forces rollback.
     * @param observed failure-stage witness */
    private void failInMysql(AtomicBoolean observed) {
        observed.set(true);
        jdbc.update("INSERT INTO transfer_records (source_account_id, destination_account_id, amount, transfer_date) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))",
                sourceId, Long.MAX_VALUE, BigDecimal.ONE);
    }

    /** Preconditions: synthetic fixture values. Postconditions: unsaved identity returned.
     * @param name unique username
     * @param hero eligibility
     * @return test identity */
    private User identity(String name, boolean hero) { return new User(name, "test-only-hash", "Payment Test", "0", "test@example.test", hero); }

    /** Preconditions: caller owns quote. Postconditions: explicit outcome; unexpected failures propagate.
     * @param id booking ID
     * @return paid or insufficient */
    private String attemptPayment(long id) {
        try { service.pay(id, () -> customer.getUsername()); return "paid"; }
        catch (InsufficientBalanceException exception) { return "insufficient"; }
    }

    /** Preconditions: independent service transactions. Postconditions: every worker completes before fixture cleanup.
     * @param tasks concurrent actions
     * @return worker outcomes
     * @throws Exception on timeout or unexpected worker failure */
    private List<String> race(List<Callable<String>> tasks) throws Exception {
        var pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            var futures = tasks.stream().map(task -> pool.submit(() -> {
                ready.countDown();
                if (!start.await(WAIT_SECONDS, TimeUnit.SECONDS)) { throw new IllegalStateException("Race start timed out"); }
                return task.call();
            })).toList();
            assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new java.util.ArrayList<String>();
            for (var future : futures) { results.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS)); }
            return results;
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue(); }
    }
}

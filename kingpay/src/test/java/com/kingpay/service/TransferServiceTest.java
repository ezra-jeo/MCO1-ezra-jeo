package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InsufficientBalanceException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** P4-U: transfer/history rules and write ordering; real rollback is tested separately against MySQL. */
class TransferServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final TransferRecordRepository ledger = mock(TransferRecordRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final TransferService service = new TransferService(accounts, ledger, new CurrentUser(users, entities), entities);
    private final Principal caller = () -> "sender";
    private User sender;
    private User recipient;
    private Account source;
    private Account destination;

    /** Preconditions: mocks exist. Postconditions: parties have IDs deliberately opposite to transfer direction. */
    @BeforeEach
    void fixtures() {
        sender = user("sender", 2L);
        recipient = user("recipient", 1L);
        source = new Account(sender, new BigDecimal("1000.00"));
        destination = new Account(recipient, new BigDecimal("500.00"));
        ReflectionTestUtils.setField(source, "id", 20L);
        ReflectionTestUtils.setField(destination, "id", 10L);
        when(users.findByUsername("sender")).thenReturn(Optional.of(sender));
        when(accounts.findById(20L)).thenReturn(Optional.of(source));
        when(accounts.findById(10L)).thenReturn(Optional.of(destination));
        when(ledger.saveAndFlush(any(TransferRecord.class))).thenAnswer(call -> {
            TransferRecord record = call.getArgument(0);
            ReflectionTestUtils.setField(record, "id", 30L);
            return record;
        });
    }

    /** Preconditions: valid exact amount and sufficient funds. Postconditions: conservation, one record and sorted locks.
     * @param amount boundary or ordinary positive amount */
    @ParameterizedTest @ValueSource(strings = {"100.00", "1000.00", "0.01"})
    void successConservesFundsAndUsesOrderedLocks(String amount) {
        Instant before = Instant.now();
        var result = service.transferMoney(request(new BigDecimal(amount)), caller);
        assertThat(source.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00").subtract(new BigDecimal(amount)));
        assertThat(destination.getBalance()).isEqualByComparingTo(new BigDecimal("500.00").add(new BigDecimal(amount)));
        assertThat(source.getBalance().add(destination.getBalance())).isEqualByComparingTo("1500.00");
        assertThat(result.id()).isEqualTo(30L);
        assertThat(result.sourceAccountId()).isEqualTo(20L);
        assertThat(result.destinationAccountId()).isEqualTo(10L);
        assertThat(result.amount()).isEqualByComparingTo(amount);
        assertThat(result.transferDate()).isBetween(before, Instant.now());
        var locks = inOrder(entities);
        locks.verify(entities).refresh(recipient, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entities).refresh(sender, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entities).refresh(destination, LockModeType.PESSIMISTIC_WRITE);
        locks.verify(entities).refresh(source, LockModeType.PESSIMISTIC_WRITE);
        var writes = inOrder(accounts, ledger);
        writes.verify(accounts).saveAndFlush(source);
        writes.verify(accounts).saveAndFlush(destination);
        writes.verify(ledger).saveAndFlush(any(TransferRecord.class));
    }

    /** Preconditions: malformed transfer. Postconditions: invalid request before authentication or repository use.
     * @param id documented case ID
     * @param request missing/invalid fields or self-transfer */
    @ParameterizedTest(name = "{0}") @MethodSource("invalidRequests")
    void invalidInputDoesNotReachPersistence(String id, TransferRequest request) {
        assertThrows(InvalidRequestException.class, () -> service.transferMoney(request, caller));
        verifyNoInteractions(accounts, ledger, users, entities);
    }

    /** Preconditions: source absent. Postconditions: 404 domain failure without writes. */
    @Test
    void missingSourceFails() {
        when(accounts.findById(20L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        verify(ledger, never()).saveAndFlush(any());
    }

    /** Preconditions: destination absent. Postconditions: no debit/credit or ledger attempt. */
    @Test
    void missingDestinationLeavesBalancesUntouched() {
        when(accounts.findById(10L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        unchanged();
    }

    /** Preconditions: caller is not source owner. Postconditions: denied before destination lookup or writes. */
    @Test
    void otherOwnerCannotSpend() {
        when(users.findByUsername("outsider")).thenReturn(Optional.of(user("outsider", 99L)));
        assertThrows(AccessDeniedException.class, () -> service.transferMoney(request(BigDecimal.ONE), () -> "outsider"));
        verify(accounts, never()).findById(10L);
        unchanged();
    }

    /** Preconditions: requested amount exceeds funds. Postconditions: domain guard fails without balance or ledger effects. */
    @Test
    void insufficientFundsLeaveBalancesUntouched() {
        assertThrows(InsufficientBalanceException.class,
                () -> service.transferMoney(request(new BigDecimal("1000.01")), caller));
        unchanged();
    }

    /** Preconditions: recipient inactive. Postconditions: conflict with balances retained. */
    @Test
    void inactiveRecipientCannotReceive() {
        recipient.deactivate();
        assertThrows(BusinessConflictException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        unchanged();
    }

    /** Preconditions: owner becomes inactive while waiting for locks. Postconditions: refreshed state rejects spending. */
    @Test
    void sourceActivityIsRecheckedAfterLock() {
        doAnswer(call -> { sender.deactivate(); return null; }).when(entities).refresh(sender, LockModeType.PESSIMISTIC_WRITE);
        assertThrows(AccessDeniedException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        unchanged();
    }

    /** Preconditions: recipient has maximum balance. Postconditions: capacity conflict before debit. */
    @Test
    void destinationCapacityPreventsDebit() {
        destination.credit(new BigDecimal("99999999999999499.99"));
        assertThrows(BusinessConflictException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        assertThat(source.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("99999999999999999.99");
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: wallet write fails. Postconditions: failure propagates to transaction; no ledger insert. */
    @Test
    void balancePersistenceFailurePropagates() {
        when(accounts.saveAndFlush(source)).thenThrow(new DataIntegrityViolationException("synthetic wallet failure"));
        assertThrows(DataIntegrityViolationException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        verify(ledger, never()).saveAndFlush(any());
    }

    /** Preconditions: ledger write fails after wallet flushes. Postconditions: failure propagates for Spring rollback. */
    @Test
    void ledgerFailurePropagatesAfterBothBalanceWrites() {
        when(ledger.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("synthetic ledger failure"));
        assertThrows(DataIntegrityViolationException.class, () -> service.transferMoney(request(BigDecimal.ONE), caller));
        verify(accounts).saveAndFlush(source);
        verify(accounts).saveAndFlush(destination);
    }

    /** Preconditions: owned account has incoming/outgoing rows. Postconditions: DTOs retain repository order and IDs. */
    @Test
    void historyMapsBothDirectionsInRepositoryOrder() {
        TransferRecord outgoing = new TransferRecord(source, destination, BigDecimal.TEN);
        TransferRecord incoming = new TransferRecord(destination, source, BigDecimal.ONE);
        ReflectionTestUtils.setField(outgoing, "id", 1L);
        ReflectionTestUtils.setField(incoming, "id", 2L);
        when(ledger.findBySourceAccount_IdOrDestinationAccount_IdOrderByTransferDateDescIdDesc(20L, 20L))
                .thenReturn(List.of(incoming, outgoing));
        var result = service.getTransferHistory(20L, caller);
        assertThat(result).extracting("id").containsExactly(2L, 1L);
        assertThat(result.getFirst().destinationAccountId()).isEqualTo(20L);
        assertThat(result.getLast().sourceAccountId()).isEqualTo(20L);
    }

    /** Preconditions: owner has no ledger rows. Postconditions: empty successful history, no writes. */
    @Test
    void emptyHistoryIsSuccessful() {
        assertThat(service.getTransferHistory(20L, caller)).isEmpty();
        verify(ledger, never()).saveAndFlush(any());
    }

    /** Preconditions: account belongs to another user. Postconditions: no history query or disclosure. */
    @Test
    void anotherOwnersHistoryIsForbidden() {
        assertThrows(AccessDeniedException.class, () -> service.getTransferHistory(10L, caller));
        verifyNoInteractions(ledger);
    }

    /** Preconditions: account absent. Postconditions: history returns missing-resource failure. */
    @Test
    void missingHistoryAccountFails() {
        assertThrows(ResourceNotFoundException.class, () -> service.getTransferHistory(99L, caller));
    }

    /** Preconditions: malformed history ID. Postconditions: rejects without account or ledger lookup.
     * @param id null or nonpositive ID */
    @ParameterizedTest @NullSource @ValueSource(longs = {0, -1})
    void invalidHistoryIdFails(Long id) {
        assertThrows(InvalidRequestException.class, () -> service.getTransferHistory(id, caller));
        verifyNoInteractions(accounts, ledger);
    }

    /** Preconditions: principal absent. Postconditions: both methods require authentication before writes. */
    @Test
    void missingCallerFailsBothMethods() {
        assertThrows(InsufficientAuthenticationException.class, () -> service.transferMoney(request(BigDecimal.ONE), null));
        assertThrows(InsufficientAuthenticationException.class, () -> service.getTransferHistory(20L, null));
        verifyNoInteractions(accounts, ledger);
    }

    /** Preconditions: none. Postconditions: synthetic persisted-ID identity created without a database.
     * @param name username
     * @param id user ID
     * @return fixture identity */
    private User user(String name, Long id) {
        User user = new User(name, "hash", "Test", "0", "test@example.test", false);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** Preconditions: parties initialized. Postconditions: valid party IDs with supplied amount returned.
     * @param amount transfer amount
     * @return request fixture */
    private TransferRequest request(BigDecimal amount) { return new TransferRequest(20L, 10L, amount); }

    /** Preconditions: failure occurred before writes. Postconditions: asserts unchanged fixtures and no saves. */
    private void unchanged() {
        assertThat(source.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("500.00");
        verify(accounts, never()).saveAndFlush(any());
        verify(ledger, never()).saveAndFlush(any());
    }

    /** Preconditions: none. Postconditions: complete invalid-input matrix supplied without secrets.
     * @return thirteen invalid transfer shapes */
    private static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("P4-U02a", null),
                Arguments.of("P4-U02b", new TransferRequest(null, 10L, BigDecimal.ONE)),
                Arguments.of("P4-U02c", new TransferRequest(20L, null, BigDecimal.ONE)),
                Arguments.of("P4-U02d", new TransferRequest(0L, 10L, BigDecimal.ONE)),
                Arguments.of("P4-U02e", new TransferRequest(20L, 0L, BigDecimal.ONE)),
                Arguments.of("P4-U02f", new TransferRequest(-1L, 10L, BigDecimal.ONE)),
                Arguments.of("P4-U02g", new TransferRequest(20L, -1L, BigDecimal.ONE)),
                Arguments.of("P4-U02h", new TransferRequest(20L, 20L, BigDecimal.ONE)),
                Arguments.of("P4-U02i", new TransferRequest(20L, 10L, null)),
                Arguments.of("P4-U02j", new TransferRequest(20L, 10L, BigDecimal.ZERO)),
                Arguments.of("P4-U02k", new TransferRequest(20L, 10L, BigDecimal.ONE.negate())),
                Arguments.of("P4-U02l", new TransferRequest(20L, 10L, new BigDecimal("1.001"))),
                Arguments.of("P4-U02m", new TransferRequest(20L, 10L, new BigDecimal("1E+17"))));
    }
}

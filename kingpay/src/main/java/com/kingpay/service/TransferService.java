package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

/** Atomic standalone transfers and private successful-transfer history. */
@Service
@Validated
@Transactional(readOnly = true)
public class TransferService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private static final int MONEY_SCALE = 2;
    private final AccountRepository accounts;
    private final TransferRecordRepository transfers;
    private final CurrentUser identities;
    private final EntityManager entities;

    /** Preconditions: Spring supplies persistence/security dependencies. Postconditions: no queries or writes.
     * @param accounts wallet repository
     * @param transfers permanent ledger repository
     * @param identities current active caller resolver
     * @param entities managed context for ordered locking */
    public TransferService(AccountRepository accounts, TransferRecordRepository transfers,
            CurrentUser identities, EntityManager entities) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.identities = identities;
        this.entities = entities;
    }

    /** Preconditions: active source owner, distinct existing active wallets and valid exact amount.
     * Postconditions: debit, credit and exactly one ledger insert commit together; every failure rolls back all writes.
     * Repeated successful calls are independent transfers; no booking is marked paid.
     * @param request source/destination IDs and positive DECIMAL(19,2) amount
     * @param caller framework principal, never body identity
     * @return persisted transfer ID, parties, exact amount and timestamp
     * @throws com.kingpay.exception.InsufficientBalanceException if source funds do not cover the debit
     * @throws BusinessConflictException if destination is inactive or cannot hold the resulting balance */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse transferMoney(@Valid TransferRequest request, Principal caller) {
        BigDecimal amount = validatedAmount(request);
        User actor = identities.active(caller);
        Account source = accounts.findById(request.fromId())
                .orElseThrow(() -> new ResourceNotFoundException("Source account not found."));
        requireOwner(source, actor);
        Account destination = accounts.findById(request.toId())
                .orElseThrow(() -> new ResourceNotFoundException("Destination account not found."));
        lockParticipants(source, destination);
        requireOwner(source, actor);
        if (!source.getUser().isActive()) { throw new AccessDeniedException("Access denied."); }
        if (!destination.getUser().isActive()) {
            throw new BusinessConflictException("Destination owner is inactive.");
        }
        if (destination.getBalance().add(amount).compareTo(MAX_BALANCE) > 0) {
            throw new BusinessConflictException("Destination balance exceeds the wallet limit.");
        }
        source.debit(amount);
        accounts.saveAndFlush(source);
        destination.credit(amount);
        accounts.saveAndFlush(destination);
        TransferRecord transfer = transfers.saveAndFlush(new TransferRecord(source, destination, amount));
        return response(transfer);
    }

    /** Preconditions: active caller owns requested account. Postconditions: no writes, only committed incoming/outgoing DTOs.
     * @param accountId protected wallet ID
     * @param caller framework principal
     * @return newest-first history, with descending ID tie-breaker; empty when no transfers exist */
    public List<TransferResponse> getTransferHistory(Long accountId, Principal caller) {
        requireId(accountId);
        User actor = identities.active(caller);
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
        requireOwner(account, actor);
        return transfers.findBySourceAccount_IdOrDestinationAccount_IdOrderByTransferDateDescIdDesc(accountId, accountId)
                .stream().map(this::response).toList();
    }

    /** Preconditions: service write transaction active; parties persisted and distinct. Postconditions: latest states locked.
     * All owner rows are locked by ascending ID before all wallet rows by ascending ID, matching Phase 3 owner-first writes.
     * @param source source wallet
     * @param destination destination wallet */
    private void lockParticipants(Account source, Account destination) {
        List<User> owners = Stream.of(source.getUser(), destination.getUser())
                .sorted(Comparator.comparing(User::getId)).toList();
        for (User owner : owners) { entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE); }
        List<Account> wallets = Stream.of(source, destination).sorted(Comparator.comparing(Account::getId)).toList();
        for (Account wallet : wallets) { entities.refresh(wallet, LockModeType.PESSIMISTIC_WRITE); }
    }

    /** Preconditions: none. Postconditions: invalid IDs, self-transfers and unrepresentable amounts fail before persistence.
     * @param request submitted transfer
     * @return exact scale-two amount */
    private BigDecimal validatedAmount(TransferRequest request) {
        if (request == null) { throw new InvalidRequestException("Transfer request is required."); }
        requireId(request.fromId());
        requireId(request.toId());
        if (request.fromId().equals(request.toId())) {
            throw new InvalidRequestException("Source and destination accounts must differ.");
        }
        if (request.amount() == null || request.amount().signum() <= 0 || request.amount().scale() > MONEY_SCALE
                || request.amount().compareTo(MAX_BALANCE) > 0) {
            throw new InvalidRequestException("Transfer amount must be positive and fit 17 integer and 2 fractional digits.");
        }
        return request.amount().setScale(MONEY_SCALE);
    }

    /** Preconditions: none. Postconditions: malformed identifier rejected without lookup.
     * @param id wallet ID */
    private void requireId(Long id) {
        if (id == null || id <= 0) { throw new InvalidRequestException("A positive account ID is required."); }
    }

    /** Preconditions: account and identity persisted. Postconditions: only ID-equal owner passes.
     * @param account protected wallet
     * @param actor authenticated identity */
    private void requireOwner(Account account, User actor) {
        if (!account.getUser().getId().equals(actor.getId())) { throw new AccessDeniedException("Access denied."); }
    }

    /** Preconditions: ledger row saved or read. Postconditions: no entity/credentials/balance exposed.
     * @param transfer permanent ledger row
     * @return transfer DTO */
    private TransferResponse response(TransferRecord transfer) {
        return new TransferResponse(transfer.getId(), transfer.getSourceAccount().getId(),
                transfer.getDestinationAccount().getId(), transfer.getAmount(), transfer.getTransferDate());
    }
}
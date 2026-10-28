package com.kingpay.service;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.response.AccountResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.security.Principal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.validation.annotation.Validated;

/** Owner-only zero-wallet creation and simulated funding; no ledger transfers or external payments. */
@Service
@Validated
@Transactional(readOnly = true)
public class AccountService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private static final int MONEY_SCALE = 2;
    private final AccountRepository accounts;
    private final CurrentUser identities;
    private final EntityManager entities;

    /** Preconditions: Spring supplies dependencies. Postconditions: owner-scoped wallet operations are available.
     * @param accounts wallet persistence
     * @param identities authenticated caller resolver
     * @param entities locking persistence context */
    public AccountService(AccountRepository accounts, CurrentUser identities, EntityManager entities) {
        this.accounts = accounts;
        this.identities = identities;
        this.entities = entities;
    }

    /** Preconditions: active user has no wallet. Postconditions: exactly one zero-balance wallet is created for caller.
     * @param caller framework principal
     * @return persisted zero-balance wallet
     * @throws BusinessConflictException if registration or another request already created a wallet */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountResponse create(Principal caller) {
        User user = identities.lockActive(caller);
        if (accounts.existsByUser_Id(user.getId())) {
            throw new BusinessConflictException("User already has an account.");
        }
        return response(accounts.saveAndFlush(new Account(user, BigDecimal.ZERO.setScale(MONEY_SCALE))));
    }

    /** Preconditions: active owner, existing wallet, positive amount fitting DECIMAL(19,2).
     * Postconditions: exact amount is added once per successful call; failures roll back; no transfer record created.
     * @param request simulated funding amount; repeated calls are separate loads
     * @param caller framework principal
     * @return updated owner wallet
     * @throws InvalidRequestException if amount or resulting balance cannot fit the storage policy */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountResponse load(@Valid LoadAccountRequest request, Principal caller) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0
                || request.amount().scale() > MONEY_SCALE || request.amount().compareTo(MAX_BALANCE) > 0) {
            throw new InvalidRequestException("Load amount must be positive and fit 17 integer and 2 fractional digits.");
        }
        User user = identities.lockActive(caller);
        Account account = ownedAccount(user);
        entities.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        if (account.getBalance().add(request.amount()).compareTo(MAX_BALANCE) > 0) {
            throw new InvalidRequestException("Resulting balance exceeds the wallet limit.");
        }
        account.credit(request.amount().setScale(MONEY_SCALE));
        return response(accounts.saveAndFlush(account));
    }

    /** Preconditions: active caller has a wallet. Postconditions: no writes and only that owner's wallet returned.
     * @param caller framework principal
     * @return caller's current wallet */
    public AccountResponse getMe(Principal caller) {
        return response(ownedAccount(identities.active(caller)));
    }

    /** Preconditions: current user is persisted. Postconditions: no other owner's account can be selected.
     * @param user authenticated identity
     * @return owned account
     * @throws ResourceNotFoundException if no wallet exists */
    private Account ownedAccount(User user) {
        return accounts.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
    }

    /** Preconditions: account and owner are persisted. Postconditions: no entity or credential is exposed.
     * @param account persisted wallet
     * @return owner and exact balance response */
    private AccountResponse response(Account account) {
        return new AccountResponse(account.getId(), account.getUser().getId(), account.getBalance());
    }
}

package com.kingpay.service;

import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.dto.response.SuperheroResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.entity.Account;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.exception.UsernameConflicts;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.repository.HeroRateRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.validation.annotation.Validated;

/** Owner-scoped profiles, private contact details, public hero summaries and retained deactivation. */
@Service
@Validated
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final BookingRepository bookings;
    private final CurrentUser identities;
    private final EntityManager entities;
    private final HeroRateRepository rates;

    /** Preconditions: dependencies supplied by Spring. Postconditions: profile operations can enforce persisted rules.
     * @param users identity persistence
     * @param accounts wallet persistence
     * @param bookings outstanding-work queries
     * @param identities authenticated caller resolver
     * @param entities locking persistence context
     * @param rates published provider prices */
    public UserService(UserRepository users, AccountRepository accounts, BookingRepository bookings,
            CurrentUser identities, EntityManager entities, HeroRateRepository rates) {
        this.users = users;
        this.accounts = accounts;
        this.bookings = bookings;
        this.identities = identities;
        this.entities = entities;
        this.rates = rates;
    }

    /** Preconditions: caller is authenticated. Postconditions: returns only caller's profile; no credentials or writes.
     * @param caller framework principal
     * @return current public profile fields, including owner's contact details */
    public UserResponse getMe(Principal caller) {
        return response(identities.active(caller));
    }

    /** Preconditions: validated replacement profile and active owner. Postconditions: only editable fields persist atomically.
     * @param request full editable profile replacement
     * @param caller framework principal
     * @return updated profile
     * @throws UsernameAlreadyExistsException if another identity owns the username, including a constraint race
     * @throws BusinessConflictException if provider eligibility is removed during outstanding provider work */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserResponse updateMe(@Valid UpdateUserRequest request, Principal caller) {
        if (request == null) { throw new InvalidRequestException("Profile request is required."); }
        User user = identities.lockActive(caller);
        if (users.existsByUsernameAndIdNot(request.username(), user.getId())) {
            throw new UsernameAlreadyExistsException();
        }
        if (user.isSuperhero() && !request.isSuperhero()) {
            accounts.findByUser_Username(user.getUsername()).ifPresent(account -> {
                if (bookings.existsByServiceProvider_IdAndStatusNot(account.getId(), BookingStatus.ConfirmedPaid)) {
                    throw new BusinessConflictException("Outstanding provider bookings prevent this eligibility change.");
                }
            });
        }
        user.setUsername(request.username());
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        user.setEmail(request.email());
        user.setSuperhero(request.isSuperhero());
        try {
            return response(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            if (UsernameConflicts.matches(exception)) { throw new UsernameAlreadyExistsException(exception); }
            throw exception;
        }
    }

    /** Preconditions: active owner, zero wallet balance and no outstanding booking in either role.
     * Postconditions: only user.active becomes false; wallet, bookings and transfers remain present.
     * @param caller framework principal
     * @throws BusinessConflictException if funds or outstanding work prevent deactivation */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deactivateMe(Principal caller) {
        User user = identities.lockActive(caller);
        Account account = accounts.findByUser_Username(user.getUsername()).orElse(null);
        if (account != null) {
            entities.refresh(account, LockModeType.PESSIMISTIC_WRITE);
            if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
                throw new BusinessConflictException("Wallet balance must be zero before deactivation.");
            }
            if (bookings.existsByCustomer_IdAndStatusNotOrServiceProvider_IdAndStatusNot(
                    account.getId(), BookingStatus.ConfirmedPaid, account.getId(), BookingStatus.ConfirmedPaid)) {
                throw new BusinessConflictException("Outstanding bookings prevent deactivation.");
            }
        }
        user.deactivate();
        users.saveAndFlush(user);
    }

    /** Preconditions: active authenticated caller. Postconditions: no writes; only active eligible profiles with published rates are listed.
     * @param caller framework principal
     * @return username-sorted summaries without contact or credential data */
    public List<SuperheroResponse> getSuperheroes(Principal caller) {
        identities.active(caller);
        return rates.findByServiceProvider_User_IsSuperheroTrueAndServiceProvider_User_ActiveTrueOrderByServiceProvider_User_UsernameAsc().stream()
                .map(rate -> new SuperheroResponse(rate.getServiceProvider().getUser().getId(), rate.getServiceProvider().getUser().getUsername(),
                        rate.getServiceProvider().getUser().getFullName(), rate.getServiceProvider().getId(), rate.getId(), rate.getAmount())).toList();
    }

    /** Preconditions: user has initialized fields. Postconditions: no credentials enter the response.
     * @param user persisted identity
     * @return profile response */
    private UserResponse response(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getFullName(), user.getPhone(),
                user.getEmail(), user.isSuperhero(), user.isActive());
    }
}

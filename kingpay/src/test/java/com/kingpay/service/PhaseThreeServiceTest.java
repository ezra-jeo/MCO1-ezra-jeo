package com.kingpay.service;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.entity.Account;
import com.kingpay.entity.BookingStatus;
import com.kingpay.entity.User;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.exception.UsernameAlreadyExistsException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.CurrentUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** P3-S: isolated service rules with real caller resolution, no database or signing key. */
class PhaseThreeServiceTest {
    enum Operation { PROFILE_GET, PROFILE_UPDATE, DEACTIVATE, HEROES, ACCOUNT_CREATE, ACCOUNT_GET, LOAD }
    private final UserRepository users = mock(UserRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final CurrentUser identities = new CurrentUser(users, entities);
    private final UserService profiles = new UserService(users, accounts, bookings, identities, entities);
    private final AccountService wallets = new AccountService(accounts, identities, entities);
    private final Principal caller = () -> "owner";
    private User user;
    private Account account;

    /** Preconditions: mocks are initialized. Postconditions: active owner and zero wallet fixtures are available. */
    @BeforeEach
    void fixtures() {
        user = new User("owner", "unchanged-hash", "Owner", "000", "owner@example.test", false);
        ReflectionTestUtils.setField(user, "id", 1L);
        account = new Account(user, new BigDecimal("0.00"));
        ReflectionTestUtils.setField(account, "id", 10L);
        when(users.findByUsername("owner")).thenReturn(Optional.of(user));
        when(accounts.findByUser_Username("owner")).thenReturn(Optional.of(account));
        when(accounts.findByUser_Id(1L)).thenReturn(Optional.of(account));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(accounts.saveAndFlush(any(Account.class))).thenAnswer(call -> {
            Account saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 10L);
            return saved;
        });
    }

    /** Preconditions: active owner and valid input. Postconditions: operation succeeds through actual service rules.
     * @param operation documented operation suffix */
    @ParameterizedTest @EnumSource(Operation.class)
    void validOperationSucceeds(Operation operation) {
        invoke(operation, caller);
        switch (operation) {
            case PROFILE_GET -> verify(users).findByUsername("owner");
            case PROFILE_UPDATE -> { assertThat(user.getFullName()).isEqualTo("Updated Owner"); verify(users).saveAndFlush(user); }
            case DEACTIVATE -> { assertThat(user.isActive()).isFalse(); verify(users).saveAndFlush(user); }
            case HEROES -> verify(accounts).findByUser_IsSuperheroTrueAndUser_ActiveTrueOrderByUser_UsernameAsc();
            case ACCOUNT_CREATE -> verify(accounts).saveAndFlush(argThat(value -> value.getUser() == user && value.getBalance().signum() == 0));
            case ACCOUNT_GET -> verify(accounts).findByUser_Id(1L);
            case LOAD -> { assertThat(account.getBalance()).isEqualByComparingTo("25.00"); verify(entities).refresh(account, LockModeType.PESSIMISTIC_WRITE); }
        }
    }

    /** Preconditions: trusted caller names absent identity. Postconditions: 404 domain failure before writes.
     * @param operation documented operation suffix */
    @ParameterizedTest @EnumSource(Operation.class)
    void missingIdentityFails(Operation operation) {
        when(users.findByUsername("owner")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> invoke(operation, caller));
        verify(users, never()).saveAndFlush(any());
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: identity inactive. Postconditions: direct service caller denied without mutation.
     * @param operation documented operation suffix */
    @ParameterizedTest @EnumSource(Operation.class)
    void inactiveIdentityFails(Operation operation) {
        user.deactivate();
        assertThrows(AccessDeniedException.class, () -> invoke(operation, caller));
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: absent principal. Postconditions: authentication failure before persistence changes.
     * @param operation documented operation suffix */
    @ParameterizedTest @EnumSource(Operation.class)
    void missingPrincipalFails(Operation operation) {
        assertThrows(InsufficientAuthenticationException.class, () -> invoke(operation, null));
        verify(users, never()).saveAndFlush(any());
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: JWT name is stale or forged in trusted test. Postconditions: stable subject selects correct identity. */
    @Test
    void jwtSubjectWinsOverPrincipalName() {
        when(users.findById(1L)).thenReturn(Optional.of(user));
        Jwt jwt = Jwt.withTokenValue("synthetic").header("alg", "HS256").subject("1").build();
        var principal = new JwtAuthenticationToken(jwt, List.of(), "different-name");
        assertThat(profiles.getMe(principal).id()).isEqualTo(1L);
        assertThat(wallets.getMe(principal).userId()).isEqualTo(1L);
        verify(users, never()).findByUsername("different-name");
    }

    /** Preconditions: another identity owns replacement name. Postconditions: no fields or password changed. */
    @Test
    void duplicateRenameStopsBeforeMutation() {
        when(users.existsByUsernameAndIdNot("owner", 1L)).thenReturn(true);
        assertThrows(UsernameAlreadyExistsException.class, () -> profiles.updateMe(update(), caller));
        assertThat(user.getFullName()).isEqualTo("Owner");
        assertThat(user.getPasswordHash()).isEqualTo("unchanged-hash");
        verify(users, never()).saveAndFlush(any());
    }

    /** Preconditions: valid replacement. Postconditions: editable fields change, credentials/ID/balance remain. */
    @Test
    void profileUpdatePreservesServerOwnedState() {
        var result = profiles.updateMe(update(), caller);
        assertThat(result.fullName()).isEqualTo("Updated Owner");
        assertThat(result.id()).isEqualTo(1L);
        assertThat(user.getPasswordHash()).isEqualTo("unchanged-hash");
        assertThat(user.isActive()).isTrue();
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    /** Preconditions: eligibility removed during assigned work. Postconditions: conflict, eligibility retained. */
    @Test
    void outstandingProviderWorkPreventsEligibilityRemoval() {
        user.setSuperhero(true);
        when(bookings.existsByServiceProvider_IdAndStatusNot(10L, BookingStatus.ConfirmedPaid)).thenReturn(true);
        assertThrows(BusinessConflictException.class, () -> profiles.updateMe(update(), caller));
        assertThat(user.isSuperhero()).isTrue();
    }

    /** Preconditions: provider has no outstanding assignments. Postconditions: eligibility removal persists. */
    @Test
    void eligibilityRemovalWithoutOutstandingWorkSucceeds() {
        user.setSuperhero(true);
        assertThat(profiles.updateMe(update(), caller).isSuperhero()).isFalse();
        verify(users).saveAndFlush(user);
    }

    /** Preconditions: write reports named constraint race. Postconditions: only that failure becomes username conflict. */
    @Test
    void renameConstraintRaceBecomesConflict() {
        var cause = new org.hibernate.exception.ConstraintViolationException("private SQL detail",
                new java.sql.SQLException("synthetic"), User.USERNAME_UNIQUE_CONSTRAINT);
        when(users.saveAndFlush(user)).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private", cause));
        assertThrows(UsernameAlreadyExistsException.class, () -> profiles.updateMe(update(), caller));
    }

    /** Preconditions: write reports unrelated constraint. Postconditions: failure is not mislabeled as duplicate username. */
    @Test
    void unrelatedRenameIntegrityFailurePropagates() {
        var failure = new org.springframework.dao.DataIntegrityViolationException("synthetic unrelated integrity failure");
        when(users.saveAndFlush(user)).thenThrow(failure);
        assertThat(assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> profiles.updateMe(update(), caller))).isSameAs(failure);
    }

    /** Preconditions: positive funds remain. Postconditions: user stays active and no save occurs. */
    @Test
    void fundedWalletPreventsDeactivation() {
        account.credit(BigDecimal.ONE);
        assertThrows(BusinessConflictException.class, () -> profiles.deactivateMe(caller));
        assertThat(user.isActive()).isTrue();
        verify(users, never()).saveAndFlush(any());
    }

    /** Preconditions: outstanding work in either role. Postconditions: user stays active. */
    @Test
    void outstandingWorkPreventsDeactivation() {
        when(bookings.existsByCustomer_IdAndStatusNotOrServiceProvider_IdAndStatusNot(
                10L, BookingStatus.ConfirmedPaid, 10L, BookingStatus.ConfirmedPaid)).thenReturn(true);
        assertThrows(BusinessConflictException.class, () -> profiles.deactivateMe(caller));
        assertThat(user.isActive()).isTrue();
    }

    /** Preconditions: accountless active user. Postconditions: deactivation does not require creating an account. */
    @Test
    void accountlessUserCanDeactivate() {
        when(accounts.findByUser_Username("owner")).thenReturn(Optional.empty());
        when(accounts.findByUser_Id(1L)).thenReturn(Optional.empty());
        profiles.deactivateMe(caller);
        assertThat(user.isActive()).isFalse();
    }

    /** Preconditions: one wallet already exists. Postconditions: second creation rejected without insert. */
    @Test
    void duplicateAccountFails() {
        when(accounts.existsByUser_Id(1L)).thenReturn(true);
        assertThrows(BusinessConflictException.class, () -> wallets.create(caller));
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: wallet absent. Postconditions: load/read fail with missing-resource error. */
    @Test
    void missingWalletFailsReadAndLoad() {
        when(accounts.findByUser_Username("owner")).thenReturn(Optional.empty());
        when(accounts.findByUser_Id(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> wallets.getMe(caller));
        assertThrows(ResourceNotFoundException.class, () -> wallets.load(new LoadAccountRequest(BigDecimal.ONE), caller));
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: unsupported amount. Postconditions: no balance change or insert.
     * @param value null, nonpositive, excessive scale or overflow */
    @ParameterizedTest @NullSource @ValueSource(strings = {"0", "-1", "1.001", "1E+17"})
    void invalidLoadDoesNotMutate(String value) {
        BigDecimal amount = value == null ? null : new BigDecimal(value);
        assertThrows(InvalidRequestException.class, () -> wallets.load(new LoadAccountRequest(amount), caller));
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        verify(accounts, never()).saveAndFlush(any());
    }

    /** Preconditions: result would exceed storage range. Postconditions: rejected before credit. */
    @Test
    void resultingOverflowDoesNotMutate() {
        account.credit(new BigDecimal("99999999999999999.99"));
        assertThrows(InvalidRequestException.class, () -> wallets.load(new LoadAccountRequest(new BigDecimal("0.01")), caller));
        assertThat(account.getBalance()).isEqualByComparingTo("99999999999999999.99");
    }

    /** Preconditions: null bodies passed directly. Postconditions: explicit safe request failures. */
    @Test
    void nullRequestsFailSafely() {
        assertThrows(InvalidRequestException.class, () -> profiles.updateMe(null, caller));
        assertThrows(InvalidRequestException.class, () -> wallets.load(null, caller));
    }

    /** Preconditions: name-based wallet lookup could select a reused username. Postconditions: stable owner ID selects wallet. */
    @Test
    void walletReadUsesStableOwnerId() {
        when(accounts.findByUser_Username("owner")).thenThrow(new AssertionError("Mutable username lookup"));
        assertThat(wallets.getMe(caller).id()).isEqualTo(10L);
        verify(accounts).findByUser_Id(1L);
    }
    /** Preconditions: no supplied state required. Postconditions: complete valid replacement fixture returned.
     * @return editable profile fields */
    private UpdateUserRequest update() {
        return new UpdateUserRequest("owner", "Updated Owner", "123", "updated@example.test", false);
    }

    /** Preconditions: fixtures initialized. Postconditions: invokes one documented public method.
     * @param operation target operation
     * @param principal trusted caller */
    private void invoke(Operation operation, Principal principal) {
        switch (operation) {
            case PROFILE_GET -> profiles.getMe(principal);
            case PROFILE_UPDATE -> profiles.updateMe(update(), principal);
            case DEACTIVATE -> profiles.deactivateMe(principal);
            case HEROES -> profiles.getSuperheroes(principal);
            case ACCOUNT_CREATE -> wallets.create(principal);
            case ACCOUNT_GET -> wallets.getMe(principal);
            case LOAD -> wallets.load(new LoadAccountRequest(new BigDecimal("25.00")), principal);
        }
    }
}

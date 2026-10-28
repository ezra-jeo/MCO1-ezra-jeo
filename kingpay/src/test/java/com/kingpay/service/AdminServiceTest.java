package com.kingpay.service;

import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.entity.TransferRecord;
import com.kingpay.entity.User;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.AdminSettings;
import com.kingpay.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** Admin overview authorization and credential-free DTO mapping. */
class AdminServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final TransferRecordRepository transfers = mock(TransferRecordRepository.class);
    private final CurrentUser identities = mock(CurrentUser.class);
    private User admin;

    /** Preconditions: isolated mocks. Postconditions: active caller ID 1 is available. */
    @BeforeEach
    void fixtures() {
        admin = new User("admin", "private-hash", "Admin", "0", "a@example.test", false);
        ReflectionTestUtils.setField(admin, "id", 1L);
        when(identities.active(any())).thenReturn(admin);
    }

    /** Preconditions: allowed admin and historical rows. Postconditions: all four DTO collections map IDs/state without writes. */
    @Test
    void overviewMapsAllRowsAndHistoricalLinks() {
        User provider = new User("old-provider", "hidden-hash", "Provider", "0", "p@example.test", true);
        ReflectionTestUtils.setField(provider, "id", 2L);
        provider.deactivate();
        Account source = new Account(admin, new BigDecimal("900"));
        Account destination = new Account(provider, new BigDecimal("600"));
        ReflectionTestUtils.setField(source, "id", 10L);
        ReflectionTestUtils.setField(destination, "id", 20L);
        TransferRecord transfer = new TransferRecord(source, destination, new BigDecimal("100"));
        ReflectionTestUtils.setField(transfer, "id", 50L);
        Booking booking = new Booking(source, destination, new BigDecimal("100"));
        ReflectionTestUtils.setField(booking, "id", 30L);
        booking.confirmCustomer(); booking.confirmServiceProvider(); booking.markPaid(transfer);
        Sort order = Sort.by("id").ascending();
        when(users.findAll(order)).thenReturn(List.of(admin, provider));
        when(accounts.findAll(order)).thenReturn(List.of(source, destination));
        when(bookings.findAll(order)).thenReturn(List.of(booking));
        when(transfers.findAll(order)).thenReturn(List.of(transfer));
        var result = service(Set.of(1L)).overview(() -> "admin");
        assertThat(result.users()).hasSize(2);
        assertThat(result.users().get(1).active()).isFalse();
        assertThat(result.accounts().get(0).balance()).isEqualByComparingTo("900");
        assertThat(result.bookings().get(0).paymentTransferId()).isEqualTo(50L);
        assertThat(result.transfers().get(0).sourceAccountId()).isEqualTo(10L);
        verify(users).findAll(order); verify(accounts).findAll(order); verify(bookings).findAll(order); verify(transfers).findAll(order);
        verifyNoMoreInteractions(users, accounts, bookings, transfers);
    }

    /** Preconditions: allowed identity; no rows returned. Postconditions: four empty collections, not null. */
    @Test
    void emptyOverviewReturnsEmptyCollections() {
        var result = service(Set.of(1L)).overview(() -> "admin");
        assertThat(result.users()).isEmpty(); assertThat(result.accounts()).isEmpty();
        assertThat(result.bookings()).isEmpty(); assertThat(result.transfers()).isEmpty();
    }

    /** Preconditions: allowlist empty or contains another ID. Postconditions: forbidden before global queries.
     * @param empty whether admin configuration is empty */
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unlistedCallerCannotRead(boolean empty) {
        assertThrows(AccessDeniedException.class, () -> service(empty ? Set.of() : Set.of(2L)).overview(() -> "admin"));
        verifyNoInteractions(users, accounts, bookings, transfers);
    }

    /** Preconditions: active-identity resolver rejects disabled identity. Postconditions: configured ID cannot bypass activity. */
    @Test
    void inactiveAdminCannotRead() {
        when(identities.active(any())).thenThrow(new AccessDeniedException("Access denied."));
        assertThrows(AccessDeniedException.class, () -> service(Set.of(1L)).overview(() -> "admin"));
        verifyNoInteractions(users, accounts, bookings, transfers);
    }

    /** Preconditions: no principal. Postconditions: authentication failure before global queries. */
    @Test
    void missingPrincipalCannotRead() {
        when(identities.active(null)).thenThrow(new InsufficientAuthenticationException("Authentication required."));
        assertThrows(InsufficientAuthenticationException.class, () -> service(Set.of(1L)).overview(null));
        verifyNoInteractions(users, accounts, bookings, transfers);
    }

    /** Preconditions: test-controlled IDs. Postconditions: service uses exactly that allowlist.
     * @param ids allowed IDs
     * @return admin service fixture */
    private AdminService service(Set<Long> ids) { return new AdminService(users, accounts, bookings, transfers, identities, new AdminSettings(ids)); }
}

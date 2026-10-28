package com.kingpay.service;

import com.kingpay.dto.response.AccountResponse;
import com.kingpay.dto.response.AdminOverviewResponse;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import com.kingpay.repository.TransferRecordRepository;
import com.kingpay.repository.UserRepository;
import com.kingpay.security.AdminSettings;
import com.kingpay.security.CurrentUser;
import java.security.Principal;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only global overview authorized by external stable user IDs. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final BookingRepository bookings;
    private final TransferRecordRepository transfers;
    private final CurrentUser identities;
    private final AdminSettings settings;

    /** Preconditions: Spring supplies persistence and trusted configuration. Postconditions: no reads/writes.
     * @param users identity repository
     * @param accounts wallet repository
     * @param bookings booking repository
     * @param transfers successful ledger repository
     * @param identities current active caller resolver
     * @param settings external admin allowlist */
    public AdminService(UserRepository users, AccountRepository accounts, BookingRepository bookings,
            TransferRecordRepository transfers, CurrentUser identities, AdminSettings settings) {
        this.users = users;
        this.accounts = accounts;
        this.bookings = bookings;
        this.transfers = transfers;
        this.identities = identities;
        this.settings = settings;
    }

    /** Preconditions: active caller's stable ID belongs to the configured allowlist.
     * Postconditions: returns all four tables as credential-free DTOs in ascending ID order from one read snapshot; no writes.
     * @param caller authenticated framework principal; body/query fields never grant access
     * @return global users, wallets, bookings and successful transfers, including historical/inactive rows
     * @throws AccessDeniedException if caller is inactive or not configured as an admin */
    public AdminOverviewResponse overview(Principal caller) {
        var actor = identities.active(caller);
        if (!settings.userIds().contains(actor.getId())) { throw new AccessDeniedException("Access denied."); }
        Sort order = Sort.by("id").ascending();
        var userRows = users.findAll(order).stream().map(user -> new UserResponse(user.getId(), user.getUsername(),
                user.getFullName(), user.getPhone(), user.getEmail(), user.isSuperhero(), user.isActive())).toList();
        var accountRows = accounts.findAll(order).stream().map(account -> new AccountResponse(account.getId(),
                account.getUser().getId(), account.getBalance())).toList();
        var bookingRows = bookings.findAll(order).stream().map(booking -> new BookingResponse(booking.getId(), booking.getCreatedAt(),
                booking.getCustomer().getId(), booking.getServiceProvider().getId(), booking.getAmount(), booking.getStatus(),
                booking.getPaymentTransfer() == null ? null : booking.getPaymentTransfer().getId())).toList();
        var transferRows = transfers.findAll(order).stream().map(transfer -> new TransferResponse(transfer.getId(),
                transfer.getSourceAccount().getId(), transfer.getDestinationAccount().getId(), transfer.getAmount(), transfer.getTransferDate())).toList();
        return new AdminOverviewResponse(userRows, accountRows, bookingRows, transferRows);
    }
}

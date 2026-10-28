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
import com.kingpay.repository.HeroRateRepository;
import com.kingpay.repository.ProtectionRequestRepository;
import com.kingpay.dto.response.HeroRateResponse;
import com.kingpay.dto.response.ProtectionRequestResponse;
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
    private final HeroRateRepository rates;
    private final ProtectionRequestRepository requests;

    /** Preconditions: Spring supplies persistence and trusted configuration. Postconditions: no reads/writes.
     * @param users identity repository
     * @param accounts wallet repository
     * @param bookings booking repository
     * @param transfers successful ledger repository
     * @param identities current active caller resolver
     * @param settings external admin allowlist
     * @param rates all publications
     * @param requests all proposals */
    public AdminService(UserRepository users, AccountRepository accounts, BookingRepository bookings,
            TransferRecordRepository transfers, CurrentUser identities, AdminSettings settings, HeroRateRepository rates,
            ProtectionRequestRepository requests) {
        this.users = users;
        this.accounts = accounts;
        this.bookings = bookings;
        this.transfers = transfers;
        this.identities = identities;
        this.settings = settings;
        this.rates = rates;
        this.requests = requests;
    }

    /** Preconditions: active caller's stable ID belongs to the configured allowlist.
     * Postconditions: returns all six tables as credential-free DTOs in ascending ID order from one read snapshot; no writes.
     * @param caller authenticated framework principal; body/query fields never grant access
     * @return global users, wallets, bookings, transfers, rates and requests, including historical/inactive rows
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
                booking.getPaymentTransfer() == null ? null : booking.getPaymentTransfer().getId(),
                booking.getProtectionRequest() == null ? null : booking.getProtectionRequest().getId())).toList();
        var transferRows = transfers.findAll(order).stream().map(transfer -> new TransferResponse(transfer.getId(),
                transfer.getSourceAccount().getId(), transfer.getDestinationAccount().getId(), transfer.getAmount(), transfer.getTransferDate())).toList();
        var rateRows = rates.findAll(order).stream().map(rate -> new HeroRateResponse(rate.getId(), rate.getServiceProvider().getUser().getId(),
                rate.getServiceProvider().getId(), rate.getAmount(), rate.getUpdatedAt())).toList();
        var origins = bookingRows.stream().filter(booking -> booking.protectionRequestId() != null)
                .collect(java.util.stream.Collectors.toMap(BookingResponse::protectionRequestId, BookingResponse::id));
        var requestRows = requests.findAll(order).stream().map(request -> new ProtectionRequestResponse(request.getId(), request.getCustomer().getId(),
                request.getServiceProvider().getId(), request.getRate().getId(), request.getAmount(), request.getStatus(), request.getCreatedAt(),
                request.getRespondedAt(), origins.get(request.getId()))).toList();
        return new AdminOverviewResponse(userRows, accountRows, bookingRows, transferRows, rateRows, requestRows);
    }
}

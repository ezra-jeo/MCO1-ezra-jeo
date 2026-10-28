package com.kingpay.service;

import com.kingpay.dto.request.CreateProtectionRequest;
import com.kingpay.dto.response.BookingResponse;
import com.kingpay.dto.response.ProtectionRequestResponse;
import com.kingpay.entity.*;
import com.kingpay.exception.BusinessConflictException;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.*;
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

/** Initial customer agreement and provider response; acceptance alone creates new bookings. */
@Service @Validated @Transactional(readOnly = true)
public class ProtectionRequestService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");
    private static final int MONEY_SCALE = 2;
    private final ProtectionRequestRepository requests;
    private final HeroRateRepository rates;
    private final AccountRepository accounts;
    private final BookingRepository bookings;
    private final CurrentUser identities;
    private final EntityManager entities;

    /** Preconditions: Spring supplies dependencies. Postconditions: no reads or writes.
     * @param requests proposal persistence
     * @param rates current publications
     * @param accounts participant wallet lookup
     * @param bookings unique accepted engagements
     * @param identities active identity resolver
     * @param entities ordered locking context */
    public ProtectionRequestService(ProtectionRequestRepository requests, HeroRateRepository rates, AccountRepository accounts,
            BookingRepository bookings, CurrentUser identities, EntityManager entities) {
        this.requests = requests; this.rates = rates; this.accounts = accounts; this.bookings = bookings;
        this.identities = identities; this.entities = entities;
    }

    /** Preconditions: active distinct wallets, available published hero and unchanged displayed price.
     * Postconditions: Pending proposal preserves server price; no booking/funds/ledger effects.
     * @param input hero user ID and displayed-price check, never authoritative financial terms
     * @param caller authenticated customer
     * @return new private proposal */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProtectionRequestResponse create(@Valid CreateProtectionRequest input, Principal caller) {
        BigDecimal expected = validatedInput(input);
        var actor = identities.active(caller);
        Account customer = accounts.findByUser_Id(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer account not found."));
        HeroRate rate = rates.findByServiceProvider_User_Id(input.serviceProviderUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Published rate not found."));
        Account provider = rate.getServiceProvider();
        if (actor.getId().equals(provider.getUser().getId())) { throw new InvalidRequestException("Customer and provider must differ."); }
        lockOwners(customer, provider);
        requireAvailable(customer, provider);
        entities.refresh(rate, LockModeType.PESSIMISTIC_WRITE);
        if (rate.getAmount().compareTo(expected) != 0) { throw new BusinessConflictException("Published price changed; review the current rate."); }
        return response(requests.saveAndFlush(new ProtectionRequest(customer, rate)));
    }

    /** Preconditions: active caller participates. Postconditions: private DTO, no writes.
     * @param requestId proposal ID
     * @param caller framework principal
     * @return participant's proposal */
    public ProtectionRequestResponse getById(Long requestId, Principal caller) {
        var actor = identities.active(caller);
        ProtectionRequest request = request(requestId);
        if (!request.getCustomer().getUser().getId().equals(actor.getId())
                && !request.getServiceProvider().getUser().getId().equals(actor.getId())) { throw new AccessDeniedException("Access denied."); }
        return response(request);
    }

    /** Preconditions: active caller. Postconditions: only own customer/provider proposals, newest first.
     * @param caller framework principal
     * @return private list, possibly empty */
    public List<ProtectionRequestResponse> getMine(Principal caller) {
        var actor = identities.active(caller);
        return requests.findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(actor.getId(), actor.getId()).stream().map(this::response).toList();
    }

    /** Preconditions: active assigned provider; new acceptance additionally requires both active and provider eligible.
     * Postconditions: Accepted and one matching booking commit together; retries return that booking; failure rolls back both.
     * @param requestId proposal ID
     * @param caller assigned provider
     * @return new or original accepted booking */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse accept(Long requestId, Principal caller) {
        ProtectionRequest request = lockedProviderRequest(requestId, caller);
        if (request.getStatus() == ProtectionRequestStatus.Accepted) {
            return bookingResponse(bookings.findByProtectionRequest_Id(request.getId())
                    .orElseThrow(() -> new IllegalStateException("Accepted request has no booking.")));
        }
        if (request.getStatus() == ProtectionRequestStatus.Declined) { throw new BusinessConflictException("Request was declined."); }
        requireAvailable(request.getCustomer(), request.getServiceProvider());
        // Flush agreement before insertion so any later booking failure must roll this state back as well.
        request.accept(); requests.saveAndFlush(request);
        return bookingResponse(bookings.saveAndFlush(new Booking(request)));
    }

    /** Preconditions: active assigned provider, even if customer is now inactive or provider removed eligibility.
     * Postconditions: Declined/no booking; repeats harmless; accepted proposals cannot be reversed.
     * @param requestId proposal ID
     * @param caller assigned provider
     * @return declined request */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProtectionRequestResponse decline(Long requestId, Principal caller) {
        ProtectionRequest request = lockedProviderRequest(requestId, caller);
        if (request.getStatus() == ProtectionRequestStatus.Accepted) { throw new BusinessConflictException("Accepted requests cannot be declined."); }
        if (request.getStatus() == ProtectionRequestStatus.Pending) { request.decline(); requests.saveAndFlush(request); }
        return response(request);
    }

    /** Preconditions: actor authenticated by resolver. Postconditions: provider authorization rechecked against latest locked rows.
     * @param id proposal ID
     * @param caller framework principal
     * @return locked proposal */
    private ProtectionRequest lockedProviderRequest(Long id, Principal caller) {
        var actor = identities.active(caller);
        ProtectionRequest request = request(id);
        if (!request.getServiceProvider().getUser().getId().equals(actor.getId())) { throw new AccessDeniedException("Access denied."); }
        lockOwners(request.getCustomer(), request.getServiceProvider());
        entities.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!request.getServiceProvider().getUser().isActive()) { throw new AccessDeniedException("Access denied."); }
        return request;
    }

    /** Preconditions: distinct immutable wallets, write transaction active. Postconditions: current owners locked by ascending ID.
     * @param customer customer wallet
     * @param provider provider wallet */
    private void lockOwners(Account customer, Account provider) {
        Stream.of(customer.getUser(), provider.getUser()).sorted(Comparator.comparing(User::getId))
                .forEach(owner -> entities.refresh(owner, LockModeType.PESSIMISTIC_WRITE));
    }

    /** Preconditions: owners just refreshed under lock. Postconditions: unavailable new engagements rejected without writes.
     * @param customer customer wallet
     * @param provider provider wallet */
    private void requireAvailable(Account customer, Account provider) {
        if (!customer.getUser().isActive() || !provider.getUser().isActive() || !provider.getUser().isSuperhero()) {
            throw new BusinessConflictException("Both participants must be active and the provider eligible.");
        }
    }

    /** Preconditions: none. Postconditions: invalid/absent proposal rejected before mutation.
     * @param id proposal ID
     * @return persisted proposal */
    private ProtectionRequest request(Long id) {
        if (id == null || id <= 0) { throw new InvalidRequestException("A positive request ID is required."); }
        return requests.findById(id).orElseThrow(() -> new ResourceNotFoundException("Protection request not found."));
    }

    /** Preconditions: none. Postconditions: malformed input rejected before persistence; price is only a comparison value.
     * @param input submitted values
     * @return normalized expected price */
    private BigDecimal validatedInput(CreateProtectionRequest input) {
        if (input == null || input.serviceProviderUserId() == null || input.serviceProviderUserId() <= 0 || input.expectedAmount() == null
                || input.expectedAmount().signum() <= 0 || input.expectedAmount().scale() > MONEY_SCALE || input.expectedAmount().compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidRequestException("Hero user ID and positive displayed price fitting 17 integer/2 fractional digits are required.");
        }
        return input.expectedAmount().setScale(MONEY_SCALE);
    }

    /** Preconditions: authorized proposal. Postconditions: immutable terms and nullable accepted booking ID, no entities.
     * @param request proposal
     * @return private DTO */
    private ProtectionRequestResponse response(ProtectionRequest request) {
        Long bookingId = request.getStatus() == ProtectionRequestStatus.Accepted
                ? bookings.findByProtectionRequest_Id(request.getId()).map(Booking::getId).orElse(null) : null;
        return new ProtectionRequestResponse(request.getId(), request.getCustomer().getId(), request.getServiceProvider().getId(),
                request.getRate().getId(), request.getAmount(), request.getStatus(), request.getCreatedAt(), request.getRespondedAt(), bookingId);
    }

    /** Preconditions: persisted accepted booking. Postconditions: values only; no money movement.
     * @param booking accepted engagement
     * @return booking DTO */
    private BookingResponse bookingResponse(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getCreatedAt(), booking.getCustomer().getId(), booking.getServiceProvider().getId(),
                booking.getAmount(), booking.getStatus(), booking.getPaymentTransfer() == null ? null : booking.getPaymentTransfer().getId(),
                booking.getProtectionRequest() == null ? null : booking.getProtectionRequest().getId());
    }
}

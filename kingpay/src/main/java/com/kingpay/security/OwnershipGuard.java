package com.kingpay.security;

import com.kingpay.entity.Account;
import com.kingpay.entity.Booking;
import com.kingpay.exception.InvalidRequestException;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.AccountRepository;
import com.kingpay.repository.BookingRepository;
import java.security.Principal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resource ownership checks shared by current guards and future business implementations. */
@Component
@Transactional(readOnly = true)
public class OwnershipGuard {

    private final AccountRepository accounts;
    private final BookingRepository bookings;

    /**
     * Receives protected-resource persistence.
     * <p>Preconditions: repositories are non-null.
     * <p>Postconditions: authorization can compare persisted ownership against the principal.
     * @param accounts wallet repository
     * @param bookings booking repository
     */
    public OwnershipGuard(AccountRepository accounts, BookingRepository bookings) {
        this.accounts = accounts;
        this.bookings = bookings;
    }

    /**
     * Requires wallet ownership for source spending or private history.
     * <p>Preconditions: caller identity comes from Spring Security.
     * <p>Postconditions: no writes; only the account's owner receives the account.
     * @param accountId protected wallet ID
     * @param caller authenticated principal
     * @return owned wallet
     */
    public Account accountOwner(Long accountId, Principal caller) {
        String name = username(caller);
        requireId(accountId);
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account not found."));
        if (!account.getUser().getUsername().equals(name)) {
            throw new AccessDeniedException("Access denied.");
        }
        return account;
    }

    /**
     * Requires either participant for private booking reads.
     * <p>Preconditions: caller identity comes from Spring Security.
     * <p>Postconditions: no writes; unrelated callers are denied.
     * @param bookingId protected booking ID
     * @param caller authenticated principal
     * @return participant's booking
     */
    public Booking bookingParticipant(Long bookingId, Principal caller) {
        String name = username(caller);
        Booking booking = booking(bookingId);
        if (!booking.getCustomer().getUser().getUsername().equals(name)
                && !booking.getServiceProvider().getUser().getUsername().equals(name)) {
            throw new AccessDeniedException("Access denied.");
        }
        return booking;
    }

    /**
     * Requires the customer for customer confirmation and payment authorization.
     * <p>Preconditions: caller identity comes from Spring Security.
     * <p>Postconditions: no writes; provider/third-party callers are denied.
     * @param bookingId protected booking ID
     * @param caller authenticated principal
     * @return customer's booking
     */
    public Booking bookingCustomer(Long bookingId, Principal caller) {
        String name = username(caller);
        Booking booking = booking(bookingId);
        if (!booking.getCustomer().getUser().getUsername().equals(name)) {
            throw new AccessDeniedException("Access denied.");
        }
        return booking;
    }

    /**
     * Requires the assigned provider for provider completion confirmation.
     * <p>Preconditions: caller identity comes from Spring Security.
     * <p>Postconditions: no writes; customer/third-party callers are denied.
     * @param bookingId protected booking ID
     * @param caller authenticated principal
     * @return provider's booking
     */
    public Booking bookingProvider(Long bookingId, Principal caller) {
        String name = username(caller);
        Booking booking = booking(bookingId);
        if (!booking.getServiceProvider().getUser().getUsername().equals(name)) {
            throw new AccessDeniedException("Access denied.");
        }
        return booking;
    }

    /**
     * Loads an existing booking after validating its identifier.
     * <p>Preconditions: an authenticated outer guard is active.
     * <p>Postconditions: missing resources produce a fixed 404 description.
     * @param id booking ID
     * @return existing booking
     */
    private Booking booking(Long id) {
        requireId(id);
        return bookings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Booking not found."));
    }

    /**
     * Extracts the trusted identity without accepting request-body ownership.
     * <p>Preconditions: none.
     * <p>Postconditions: absent identity fails with an authentication error.
     * @param caller framework principal
     * @return authenticated username
     */
    private String username(Principal caller) {
        if (caller == null || caller.getName() == null || caller.getName().isBlank()) {
            throw new InsufficientAuthenticationException("Authentication required.");
        }
        return caller.getName();
    }

    /**
     * Rejects malformed identifiers before persistence lookup.
     * <p>Preconditions: none.
     * <p>Postconditions: null/nonpositive IDs produce a safe request error.
     * @param id resource ID
     */
    private void requireId(Long id) {
        if (id == null || id <= 0) {
            throw new InvalidRequestException("A positive resource ID is required.");
        }
    }
}

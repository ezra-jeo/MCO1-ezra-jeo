package com.kingpay.repository;

import com.kingpay.entity.Booking;
import com.kingpay.entity.BookingStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Booking persistence; service authorization is required for identifier-based lookups. */
@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Preconditions: account ID and excluded status are persisted values. Postconditions: no writes.
     * @param serviceProviderId provider account ID
     * @param status completed state excluded from outstanding work
     * @return whether the provider has unfinished or unpaid work */
    boolean existsByServiceProvider_IdAndStatusNot(Long serviceProviderId, BookingStatus status);

    /**
     * Lists bookings where a user participates as customer or service provider.
     * <p>Preconditions: pass the authenticated username as both parameters.
     * <p>Postconditions: no data changes; newest creation time comes first, with descending ID ties.
     * @param customerUsername username to match through the customer account's user
     * @param serviceProviderUsername same username to match through the provider account's user
     * @return matching bookings in deterministic newest-first order, or an empty list
     */
    List<Booking> findByCustomer_User_UsernameOrServiceProvider_User_UsernameOrderByCreatedAtDescIdDesc(
            String customerUsername, String serviceProviderUsername);

    /**
     * Checks outstanding work for account deactivation in either participant role.
     * <p>Preconditions: pass the same account ID for both roles and ConfirmedPaid for
     * both excluded-status parameters under the current five-state lifecycle.
     * <p>Postconditions: no data changes; grouping is (customer AND status-not)
     * OR (provider AND status-not). This is not a concurrency lock.
     * @param customerId account ID to match as customer
     * @param customerExcludedStatus completed state to exclude for the customer role
     * @param serviceProviderId same account ID to match as provider
     * @param serviceProviderExcludedStatus completed state to exclude for the provider role
     * @return whether a matching unfinished or unpaid booking exists
     */
    boolean existsByCustomer_IdAndStatusNotOrServiceProvider_IdAndStatusNot(
            Long customerId, BookingStatus customerExcludedStatus,
            Long serviceProviderId, BookingStatus serviceProviderExcludedStatus);
}

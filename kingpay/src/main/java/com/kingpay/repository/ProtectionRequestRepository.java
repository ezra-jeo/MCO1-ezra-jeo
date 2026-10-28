package com.kingpay.repository;

import com.kingpay.entity.ProtectionRequest;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Private proposal persistence, independent of accepted bookings. */
public interface ProtectionRequestRepository extends JpaRepository<ProtectionRequest, Long> {
    /** Preconditions: same authenticated stable ID supplied for both roles. Postconditions: only participant requests.
     * @param customerId customer user ID
     * @param providerId provider user ID
     * @return newest-first requests, descending ID tie-break */
    @EntityGraph(attributePaths = {"customer", "serviceProvider", "rate"})
    List<ProtectionRequest> findByCustomer_User_IdOrServiceProvider_User_IdOrderByCreatedAtDescIdDesc(Long customerId, Long providerId);
}

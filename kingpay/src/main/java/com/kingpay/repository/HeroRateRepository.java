package com.kingpay.repository;

import com.kingpay.entity.HeroRate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/** Current flat-rate persistence; unique provider wallet is authoritative. */
public interface HeroRateRepository extends JpaRepository<HeroRate, Long> {
    /** Preconditions: stable user ID supplied by service. Postconditions: no writes/activity authorization.
     * @param userId provider user ID
     * @return rate, if published */
    @EntityGraph(attributePaths = "serviceProvider.user")
    Optional<HeroRate> findByServiceProvider_User_Id(Long userId);
    /** Preconditions: caller authenticated by service. Postconditions: only available published providers selected.
     * @return rates with provider summaries in username order */
    @EntityGraph(attributePaths = "serviceProvider.user")
    List<HeroRate> findByServiceProvider_User_IsSuperheroTrueAndServiceProvider_User_ActiveTrueOrderByServiceProvider_User_UsernameAsc();
}

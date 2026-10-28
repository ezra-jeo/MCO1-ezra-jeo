package com.kingpay.repository;

import com.kingpay.entity.Account;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Account persistence; identifier lookup and CRUD are inherited from JpaRepository. */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    /** Preconditions: userId is the authenticated persisted identity. Postconditions: no mutable username dependency or writes.
     * @param userId stable owner ID
     * @return owner's wallet, or empty when absent */
    Optional<Account> findByUser_Id(Long userId);

    /** Preconditions: caller authorization belongs to service. Postconditions: one joined query, no writes.
     * @return active eligible provider wallets ordered by username, with initialized user details */
    @EntityGraph(attributePaths = "user")
    List<Account> findByUser_IsSuperheroTrueAndUser_ActiveTrueOrderByUser_UsernameAsc();

    /**
     * Resolves an account through its owner's login name.
     * <p>Preconditions: username is a non-null authenticated identity supplied by the service.
     * <p>Postconditions: no data changes; inactive status is not filtered or authorized here.
     * @param username account owner's login name
     * @return the owner's account, or empty when it does not exist
     */
    Optional<Account> findByUser_Username(String username);

    /**
     * Checks whether a persisted user already owns an account.
     * <p>Preconditions: userId is a non-null persisted user ID.
     * <p>Postconditions: no data changes; the unique owner constraint remains authoritative.
     * @param userId account owner's user ID
     * @return whether that user has an account
     */
    boolean existsByUser_Id(Long userId);
}

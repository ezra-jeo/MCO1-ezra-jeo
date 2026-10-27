package com.kingpay.repository;

import com.kingpay.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** User persistence; identifier lookup and CRUD are inherited from JpaRepository. */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a login identity, including inactive users for explicit service-level checks.
     * <p>Preconditions: username is non-null and validated by the caller.
     * <p>Postconditions: no data changes; matching follows the database collation.
     * @param username login name to find
     * @return the matching user, or empty when absent
     */
    Optional<User> findByUsername(String username);

    /**
     * Checks username availability before registration.
     * <p>Preconditions: username is non-null and validated by the caller.
     * <p>Postconditions: no data changes; the database unique constraint remains authoritative.
     * @param username proposed login name
     * @return whether a matching username already exists
     */
    boolean existsByUsername(String username);

    /**
     * Checks whether a profile update conflicts with another user's username.
     * <p>Preconditions: username and the persisted current-user ID are non-null.
     * <p>Postconditions: no data changes; the current user's row is excluded.
     * @param username proposed login name
     * @param id current user's ID to exclude
     * @return whether another user already has that username
     */
    boolean existsByUsernameAndIdNot(String username, Long id);

    /**
     * Lists active users who are eligible to provide superhero protection.
     * <p>Preconditions: none; access and response mapping belong to the service.
     * <p>Postconditions: no data changes; results are ordered by username ascending.
     * @return active superhero users, or an empty list
     */
    List<User> findByIsSuperheroTrueAndActiveTrueOrderByUsernameAsc();
}

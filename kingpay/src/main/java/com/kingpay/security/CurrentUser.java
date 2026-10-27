package com.kingpay.security;

import com.kingpay.entity.User;
import com.kingpay.exception.ResourceNotFoundException;
import com.kingpay.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.security.Principal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the caller and serializes current profile/wallet writes on the owner's row. */
@Component
public class CurrentUser {
    private final UserRepository users;
    private final EntityManager entities;

    /** Preconditions: Spring supplies persistence. Postconditions: caller resolution is available.
     * @param users identity repository
     * @param entities managed persistence context */
    public CurrentUser(UserRepository users, EntityManager entities) {
        this.users = users;
        this.entities = entities;
    }

    /** Preconditions: caller came from authentication. Postconditions: no writes; missing/inactive identities fail.
     * @param caller framework principal
     * @return current active user */
    public User active(Principal caller) {
        return requireActive(resolve(caller));
    }

    /** Preconditions: a service write transaction is active. Postconditions: latest active state checked under row lock.
     * @param caller framework principal
     * @return locked active user */
    public User lockActive(Principal caller) {
        User user = resolve(caller);
        // Refresh also replaces stale first-level cache state after waiting for another writer.
        entities.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return requireActive(user);
    }

    /** Preconditions: caller is supplied by trusted application code. Postconditions: JWT ID wins over mutable name.
     * @param caller authenticated principal
     * @return persisted identity before activity check */
    private User resolve(Principal caller) {
        if (caller == null || caller.getName() == null || caller.getName().isBlank()) {
            throw new InsufficientAuthenticationException("Authentication required.");
        }
        if (caller instanceof JwtAuthenticationToken token) {
            try {
                return users.findById(Long.valueOf(token.getToken().getSubject()))
                        .orElseThrow(() -> new ResourceNotFoundException("User not found."));
            } catch (NumberFormatException exception) {
                throw new InsufficientAuthenticationException("Invalid authentication.");
            }
        }
        return users.findByUsername(caller.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User not found."));
    }

    /** Preconditions: user is persisted. Postconditions: inactive users cannot invoke profile/wallet operations.
     * @param user resolved identity
     * @return same active identity */
    private User requireActive(User user) {
        if (!user.isActive()) { throw new AccessDeniedException("Access denied."); }
        return user;
    }
}

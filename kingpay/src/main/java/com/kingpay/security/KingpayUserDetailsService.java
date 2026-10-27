package com.kingpay.security;

import com.kingpay.entity.User;
import com.kingpay.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adapts persisted identities to Spring Security's password-authentication contract. */
@Service
public class KingpayUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    /**
     * Receives identity persistence.
     * <p>Preconditions: users is non-null.
     * <p>Postconditions: lookup uses the supplied repository.
     * @param users identity repository
     */
    public KingpayUserDetailsService(UserRepository users) {
        this.users = users;
    }

    /**
     * Loads credentials and current enabled/role state without logging submitted identity data.
     * <p>Preconditions: username is supplied by the authentication provider.
     * <p>Postconditions: no writes; inactive users are represented as disabled.
     * @param username login identity
     * @return Spring Security user details
     * @throws UsernameNotFoundException if the username is absent
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username).map(KingpayUserDetailsService::details)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
    }

    /**
     * Maps current database state to framework details.
     * <p>Preconditions: user has initialized identity and hash fields.
     * <p>Postconditions: password remains encoded; roles come from the stored eligibility flag.
     * @param user persisted identity
     * @return enabled/disabled security details
     */
    static UserDetails details(User user) {
        String[] authorities = user.isSuperhero()
                ? new String[] {"ROLE_USER", "ROLE_SUPERHERO"} : new String[] {"ROLE_USER"};
        return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(user.getPasswordHash()).disabled(!user.isActive()).authorities(authorities).build();
    }
}

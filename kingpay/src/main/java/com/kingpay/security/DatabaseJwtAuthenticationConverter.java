package com.kingpay.security;

import com.kingpay.entity.User;
import com.kingpay.repository.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Reloads current identity state so disabled/deleted users cannot keep using old tokens. */
@Component
public class DatabaseJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    /**
     * Receives identity persistence.
     * <p>Preconditions: users is non-null.
     * <p>Postconditions: token subjects can resolve to current database identities.
     * @param users identity repository
     */
    public DatabaseJwtAuthenticationConverter(UserRepository users) {
        this.users = users;
    }

    /**
     * Converts a signature/expiry-validated token into a current authenticated principal.
     * <p>Preconditions: the resource-server decoder has validated the JWT.
     * <p>Postconditions: principal name and authorities come from the database, not request fields.
     * @param jwt validated token with a persisted user ID as subject
     * @return authenticated token naming the current username
     * @throws OAuth2AuthenticationException if subject is invalid or user is missing/inactive
     */
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Long userId;
        try {
            userId = Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw invalidToken();
        }
        User user = users.findById(userId).filter(User::isActive).orElseThrow(this::invalidToken);
        return new JwtAuthenticationToken(jwt, KingpayUserDetailsService.details(user).getAuthorities(),
                user.getUsername());
    }

    /**
     * Creates an authentication failure with no token or identity values.
     * <p>Preconditions: current identity cannot be authenticated.
     * <p>Postconditions: the resource server can return a generic HTTP 401.
     * @return safe authentication failure
     */
    private OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "Invalid authentication.");
    }
}

package com.kingpay.service;

import com.kingpay.dto.response.AuthResponse;
import com.kingpay.entity.User;
import com.kingpay.security.JwtSettings;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access tokens with stable user IDs and no profile/password claims. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtSettings settings;
    private final Clock clock;

    /**
     * Receives signing infrastructure and time policy.
     * <p>Preconditions: dependencies are non-null and settings passed startup validation.
     * <p>Postconditions: issuance uses the configured signing key and UTC policy.
     * @param encoder JWT signer
     * @param settings issuer/lifetime configuration
     * @param clock issuing clock
     */
    public TokenService(JwtEncoder encoder, JwtSettings settings, Clock clock) {
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Issues a bearer token for a persisted active identity after password authentication.
     * <p>Preconditions: the service has authenticated the user's credentials.
     * <p>Postconditions: signed claims contain issuer, user ID, issue time, and expiration only.
     * @param user authenticated database identity
     * @return signed bearer token and expiration
     * @throws BadCredentialsException if identity is null, unsaved, or inactive
     */
    public AuthResponse issue(User user) {
        if (user == null || user.getId() == null || !user.isActive()) {
            throw new BadCredentialsException("Invalid credentials.");
        }
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(settings.ttlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(settings.issuer()).subject(user.getId().toString())
                .issuedAt(issuedAt).expiresAt(expiresAt).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AuthResponse(token, "Bearer", expiresAt);
    }
}

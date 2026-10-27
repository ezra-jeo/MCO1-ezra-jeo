package com.kingpay.security;

import com.kingpay.entity.User;
import com.kingpay.repository.UserRepository;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/** P2-D/K: identity adapters and password/key compatibility without a database. */
class SecurityComponentsTest {
    private final UserRepository users = mock(UserRepository.class);
    private final KingpayUserDetailsService details = new KingpayUserDetailsService(users);
    private final SecurityConfig config = new SecurityConfig();

    /** Preconditions: ordinary active identity exists. Postconditions: only ROLE_USER is granted. */
    @Test
    void ordinaryIdentityHasUserAuthority() {
        when(users.findByUsername("reader")).thenReturn(Optional.of(user(false)));
        var result = details.loadUserByUsername("reader");
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    /** Preconditions: eligible active identity exists. Postconditions: both current authorities are granted. */
    @Test
    void superheroIdentityHasBothAuthorities() {
        when(users.findByUsername("reader")).thenReturn(Optional.of(user(true)));
        assertThat(details.loadUserByUsername("reader").getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_SUPERHERO");
    }

    /** Preconditions: inactive identity exists. Postconditions: credentials adapter marks it disabled. */
    @Test
    void inactiveIdentityIsDisabled() {
        User user = user(false);
        user.deactivate();
        when(users.findByUsername("reader")).thenReturn(Optional.of(user));
        assertThat(details.loadUserByUsername("reader").isEnabled()).isFalse();
    }

    /** Preconditions: identity absent. Postconditions: no details disclose the requested identity. */
    @Test
    void missingIdentityFailsSafely() {
        assertThat(assertThrows(UsernameNotFoundException.class, () -> details.loadUserByUsername("reader")))
                .hasMessage("Invalid credentials.");
    }

    /** Preconditions: legacy jBCrypt hash exists. Postconditions: configured encoder verifies it and rejects wrong passwords. */
    @Test
    void existingJbcryptHashesRemainUsable() {
        String legacy = org.mindrot.jbcrypt.BCrypt.hashpw("test-password", org.mindrot.jbcrypt.BCrypt.gensalt(4));
        assertThat(config.passwordEncoder().matches("test-password", legacy)).isTrue();
        assertThat(config.passwordEncoder().matches("wrong-password", legacy)).isFalse();
        String encoded = config.passwordEncoder().encode("test-password");
        assertThat(encoded).isNotEqualTo("test-password");
        assertThat(config.passwordEncoder().matches("test-password", encoded)).isTrue();
    }

    /** Preconditions: malformed or undersized key supplied. Postconditions: startup fails without echoing it.
     * @param secret invalid configuration input */
    @ParameterizedTest
    @ValueSource(strings = {"bad!key", "c2hvcnQ="})
    void invalidSigningKeysFailWithoutDisclosure(String secret) {
        var exception = assertThrows(IllegalStateException.class,
                () -> config.jwtSigningKey(new JwtSettings(secret, "kingpay", 3600)));
        assertThat(exception.getMessage()).doesNotContain(secret);
    }

    /** Preconditions: valid 256-bit key supplied. Postconditions: exact key bytes are accepted. */
    @Test
    void validSigningKeyIsAccepted() {
        byte[] key = new byte[32];
        assertThat(config.jwtSigningKey(new JwtSettings(Base64.getEncoder().encodeToString(key), "kingpay", 3600))
                .getEncoded()).isEqualTo(key);
    }

    /** Preconditions: current identity differs from old claims. Postconditions: current username/roles win. */
    @Test
    void converterUsesCurrentDatabaseIdentity() {
        when(users.findById(1L)).thenReturn(Optional.of(user(false)));
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "HS256").subject("1")
                .claim("username", "old-name").claim("roles", "ROLE_SUPERHERO").build();
        var result = new DatabaseJwtAuthenticationConverter(users).convert(jwt);
        assertThat(result.getName()).isEqualTo("reader");
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    /** Preconditions: subject is not a valid persisted ID. Postconditions: authentication fails.
     * @param subject untrusted subject */
    @ParameterizedTest
    @ValueSource(strings = {"not-an-id", "0", "-1", "9223372036854775808"})
    void converterRejectsInvalidOrMissingIdentity(String subject) {
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "HS256").subject(subject).build();
        assertThrows(OAuth2AuthenticationException.class,
                () -> new DatabaseJwtAuthenticationConverter(users).convert(jwt));
    }

    /** Preconditions: identity became inactive after issuance. Postconditions: old token cannot authenticate. */
    @Test
    void converterRejectsInactiveIdentity() {
        User user = user(false);
        user.deactivate();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var jwt = Jwt.withTokenValue("synthetic").header("alg", "HS256").subject("1").build();
        assertThrows(OAuth2AuthenticationException.class,
                () -> new DatabaseJwtAuthenticationConverter(users).convert(jwt));
    }

    /** Preconditions: none. Postconditions: creates a synthetic unsaved identity.
     * @param hero eligibility flag
     * @return fixture identity */
    private User user(boolean hero) {
        return new User("reader", "encoded", "Test Reader", "000", "reader@example.test", hero);
    }
}

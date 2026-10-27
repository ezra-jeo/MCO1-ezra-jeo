package com.kingpay.service;

import com.kingpay.entity.User;
import com.kingpay.security.JwtSettings;
import com.kingpay.security.SecurityConfig;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real JWT signing/verification with a fresh test-only key and fixed issuance clock. */
class TokenServiceTest {
    private static final long TOKEN_LIFETIME = 3600;
    private TokenService service;
    private JwtDecoder decoder;
    private JwtSettings settings;
    private Instant now;

    /** Preconditions: none. Postconditions: ephemeral signer/decoder and fixed clock exist; environment key is not read. */
    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        settings = new JwtSettings(Base64.getEncoder().encodeToString(key), "kingpay-test", TOKEN_LIFETIME);
        SecurityConfig config = new SecurityConfig();
        var signingKey = config.jwtSigningKey(settings);
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        service = new TokenService(config.jwtEncoder(signingKey), settings, Clock.fixed(now, ZoneOffset.UTC));
        decoder = config.jwtDecoder(signingKey, settings);
    }

    /** P2-T01. Preconditions: active persisted identity. Postconditions: signature/lifetime verify; credentials omitted. */
    @Test
    @DisplayName("P2-T01: issue a verifiable token with stable ID and exact lifetime")
    void issueSignedTokenWithSafeClaims() {
        User user = user();
        ReflectionTestUtils.setField(user, "id", 100L);

        var response = service.issue(user);
        var jwt = decoder.decode(response.token());

        assertThat(jwt.getSubject()).isEqualTo("100");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(settings.issuer());
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(TOKEN_LIFETIME));
        assertThat(response.expiresAt()).isEqualTo(jwt.getExpiresAt());
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(jwt.getClaims()).doesNotContainKeys("password", "passwordHash", "email", "username");
        assertThat(response.toString()).doesNotContain(response.token());
        assertThat(settings.toString()).doesNotContain(settings.secret());
    }

    /** P2-T02. Preconditions: null identity. Postconditions: no token can be issued. */
    @Test
    @DisplayName("P2-T02: reject null token identity")
    void rejectNullIdentity() {
        assertThrows(BadCredentialsException.class, () -> service.issue(null));
    }

    /** P2-T03. Preconditions: identity is unsaved. Postconditions: it cannot become a usable token subject. */
    @Test
    @DisplayName("P2-T03: reject an unsaved token identity")
    void rejectUnsavedIdentity() {
        assertThrows(BadCredentialsException.class, () -> service.issue(user()));
    }

    /** P2-T04. Preconditions: inactive persisted identity. Postconditions: no fresh token is issued. */
    @Test
    @DisplayName("P2-T04: reject an inactive token identity")
    void rejectInactiveIdentity() {
        User user = user();
        ReflectionTestUtils.setField(user, "id", 100L);
        user.deactivate();
        assertThrows(BadCredentialsException.class, () -> service.issue(user));
    }

    /** Preconditions: none. Postconditions: synthetic active identity exists without an ID.
     * @return unsaved test identity */
    private User user() {
        return new User("test-user", "test-hash", "Test User", "000", "user@example.test", true);
    }
}

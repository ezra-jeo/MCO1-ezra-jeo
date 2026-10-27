package com.kingpay.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * External signing configuration; the secret is never included in string representations.
 * @param secret Base64 signing key containing at least 32 random bytes
 * @param issuer expected issuer of this application's tokens
 * @param ttlSeconds positive access-token lifetime
 */
@Validated
@ConfigurationProperties(prefix = "kingpay.jwt")
public record JwtSettings(@NotBlank String secret, @NotBlank String issuer, @Min(1) long ttlSeconds) {

    /**
     * Provides a safe configuration description.
     * <p>Preconditions: none.
     * <p>Postconditions: no signing material is exposed.
     * @return a redacted description
     */
    @Override
    public String toString() {
        return "JwtSettings[redacted]";
    }
}

package com.kingpay.dto.response;

import java.time.Instant;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the future service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param token returned token
 * @param tokenType returned tokenType
 * @param expiresAt returned expiresAt
 */
public record AuthResponse(
        String token,
        String tokenType,
        Instant expiresAt
) {

    /**
     * Returns a log-safe description.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: No token value is disclosed.
     * @return a description excluding the token
     */
    @Override
    public String toString() {
        return "AuthResponse[redacted]";
    }
}

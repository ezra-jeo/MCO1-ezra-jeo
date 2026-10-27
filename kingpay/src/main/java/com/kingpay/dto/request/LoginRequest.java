package com.kingpay.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request shape; validation checks input syntax only, not authorization/business rules.
 * <p>Preconditions: populated from a request body.
 * <p>Postconditions: immutable request data is available.
 * @param username supplied username
 * @param password supplied password
 */
public record LoginRequest(
        @NotBlank @Size(max = 50) String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 72) String password
) {

    /**
     * Returns a log-safe description.
     *
     * <p>Preconditions: None.
     * <p>Postconditions: No request field values are disclosed.
     * @return a description excluding credentials
     */
    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}

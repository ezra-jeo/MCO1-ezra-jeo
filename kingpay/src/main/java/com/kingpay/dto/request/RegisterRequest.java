package com.kingpay.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request shape; validation checks input syntax only, not authorization/business rules.
 * <p>Preconditions: populated from a request body.
 * <p>Postconditions: immutable request data is available.
 * @param username supplied username
 * @param password supplied password
 * @param fullName supplied fullName
 * @param phone supplied phone
 * @param email supplied email
 * @param isSuperhero supplied isSuperhero
 */
public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Email @Size(max = 254) String email,
        boolean isSuperhero
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
        return "RegisterRequest[redacted]";
    }
}

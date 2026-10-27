package com.kingpay.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request shape; validation checks input syntax only, not authorization/business rules.
 * <p>Preconditions: populated from a request body.
 * <p>Postconditions: immutable request data is available.
 * @param username supplied username
 * @param fullName supplied fullName
 * @param phone supplied phone
 * @param email supplied email
 * @param isSuperhero supplied isSuperhero
 */
public record UpdateUserRequest(
        @NotBlank @Size(min = 3, max = 50) String username,
        @NotBlank @Size(max = 120) String fullName,
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull Boolean isSuperhero
) {
}

package com.kingpay.dto.response;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param id returned id
 * @param username returned username
 * @param fullName returned fullName
 * @param phone returned phone
 * @param email returned email
 * @param isSuperhero returned isSuperhero
 * @param active returned active
 */
public record UserResponse(
        Long id,
        String username,
        String fullName,
        String phone,
        String email,
        boolean isSuperhero,
        boolean active
) {
}

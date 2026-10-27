package com.kingpay.dto.response;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the future service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param id returned id
 * @param username returned username
 * @param fullName returned fullName
 */
public record SuperheroResponse(
        Long id,
        String username,
        String fullName
) {
}

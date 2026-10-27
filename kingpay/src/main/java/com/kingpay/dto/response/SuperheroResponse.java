package com.kingpay.dto.response;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param id provider user ID, distinct from the account ID
 * @param username returned username
 * @param fullName returned fullName
 * @param accountId bookable provider wallet ID; no balance is disclosed
 */
public record SuperheroResponse(
        Long id,
        String username,
        String fullName,
        Long accountId
) {
}

package com.kingpay.dto.response;

import java.math.BigDecimal;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the future service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param id returned id
 * @param userId returned userId
 * @param balance returned balance
 */
public record AccountResponse(
        Long id,
        Long userId,
        BigDecimal balance
) {
}

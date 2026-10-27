package com.kingpay.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param id returned id
 * @param sourceAccountId returned sourceAccountId
 * @param destinationAccountId returned destinationAccountId
 * @param amount returned amount
 * @param transferDate returned transferDate
 */
public record TransferResponse(
        Long id,
        Long sourceAccountId,
        Long destinationAccountId,
        BigDecimal amount,
        Instant transferDate
) {
}

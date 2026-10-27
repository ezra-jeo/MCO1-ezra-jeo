package com.kingpay.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request shape; validation checks input syntax only, not authorization/business rules.
 * <p>Preconditions: populated from a request body.
 * <p>Postconditions: immutable request data is available.
 * @param fromId supplied fromId
 * @param toId supplied toId
 * @param amount supplied amount
 */
public record TransferRequest(
        @NotNull @Positive Long fromId,
        @NotNull @Positive Long toId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount
) {
}

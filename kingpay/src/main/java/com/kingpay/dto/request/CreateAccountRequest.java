package com.kingpay.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request shape; validation checks input syntax only, not authorization/business rules.
 * <p>Preconditions: populated from a request body.
 * <p>Postconditions: immutable request data is available.
 * @param openingBalance supplied openingBalance
 */
public record CreateAccountRequest(
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal openingBalance
) {
}

package com.kingpay.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Amount for simulated funding of the authenticated user's own wallet.
 * <p>Preconditions: populated from a request body; ownership comes from the principal.
 * <p>Postconditions: immutable request data is available; no funds have been added.
 * @param amount positive load amount with at most two fractional digits
 */
public record LoadAccountRequest(
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount
) {
}

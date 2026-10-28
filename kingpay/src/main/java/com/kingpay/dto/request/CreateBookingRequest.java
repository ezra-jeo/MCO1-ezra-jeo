package com.kingpay.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Request shape; validation checks input syntax only, not authorization/business rules.
 * <p>Preconditions: populated from a request body.
 * <p>Postconditions: immutable request data is available.
 * @param serviceProviderAccountId supplied serviceProviderAccountId
 * @param amount supplied amount
 */
public record CreateBookingRequest(
        @NotNull @Positive Long serviceProviderAccountId,
        @Schema(description = "Exact positive DECIMAL(19,2), at most 17 integer and 2 fractional digits; no rounding.", example = "100.00", minimum = "0", exclusiveMinimum = true, maximum = "99999999999999999.99", multipleOf = 0.01)
        @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount
) {
}

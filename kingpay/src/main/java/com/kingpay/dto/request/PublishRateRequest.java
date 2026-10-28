package com.kingpay.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Own-rate update; owner comes from authentication.
 * <p>Preconditions: HTTP binding. Postconditions: immutable syntactic input.
 * @param amount positive exact flat price */
public record PublishRateRequest(
        @Schema(description = "Positive flat price for one whole job; exact 17 integer/2 fraction digits.", example = "100.00", minimum = "0", exclusiveMinimum = true, maximum = "99999999999999999.99", multipleOf = 0.01)
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount) { }

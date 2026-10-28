package com.kingpay.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/** Initial customer agreement; expected amount detects a stale display, never sets the authoritative price.
 * <p>Preconditions: HTTP binding. Postconditions: immutable syntactic input.
 * @param serviceProviderUserId selected hero's stable user ID
 * @param expectedAmount price displayed to the customer */
public record CreateProtectionRequest(@NotNull @Positive Long serviceProviderUserId,
        @Schema(description = "Echo the displayed price. Mismatch returns 409; the server stores its current rate.", example = "100.00", minimum = "0", exclusiveMinimum = true, maximum = "99999999999999999.99", multipleOf = 0.01)
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal expectedAmount) { }

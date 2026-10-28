package com.kingpay.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/** Published price summary, without private contact/balance data.
 * <p>Preconditions: authorized service mapping. Postconditions: immutable values only.
 * @param id internal rate ID
 * @param serviceProviderUserId stable provider user ID used in rate lookup/requests
 * @param serviceProviderAccountId fixed provider wallet
 * @param amount current flat price
 * @param updatedAt latest publication time */
public record HeroRateResponse(Long id, Long serviceProviderUserId, Long serviceProviderAccountId, BigDecimal amount, Instant updatedAt) { }

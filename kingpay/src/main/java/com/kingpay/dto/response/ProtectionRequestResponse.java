package com.kingpay.dto.response;

import com.kingpay.entity.ProtectionRequestStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Private immutable proposal view.
 * <p>Preconditions: caller participant or configured admin. Postconditions: no persistence entities/credentials.
 * @param id proposal ID
 * @param customerAccountId fixed customer wallet
 * @param serviceProviderAccountId fixed provider wallet
 * @param rateId originating publication
 * @param amount immutable submitted price snapshot
 * @param status initial-agreement status
 * @param createdAt submission time
 * @param respondedAt nullable provider response time
 * @param bookingId nullable accepted booking ID */
public record ProtectionRequestResponse(Long id, Long customerAccountId, Long serviceProviderAccountId, Long rateId,
        BigDecimal amount, ProtectionRequestStatus status, Instant createdAt, Instant respondedAt, Long bookingId) { }

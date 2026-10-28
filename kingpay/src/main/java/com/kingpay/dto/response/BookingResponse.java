package com.kingpay.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import com.kingpay.entity.BookingStatus;

/**
 * Response data; persistence entities are not exposed.
 * <p>Preconditions: the service authorizes access and maps the entity.
 * <p>Postconditions: only the declared response fields are exposed.
 * @param id returned id
 * @param createdAt returned createdAt
 * @param customerAccountId returned customerAccountId
 * @param serviceProviderAccountId returned serviceProviderAccountId
 * @param amount returned amount
 * @param status returned status
 * @param paymentTransferId returned paymentTransferId; null until paid
 * @param protectionRequestId accepted origin, null for historical bookings
 */
public record BookingResponse(
        Long id,
        Instant createdAt,
        Long customerAccountId,
        Long serviceProviderAccountId,
        BigDecimal amount,
        BookingStatus status,
        Long paymentTransferId,
        Long protectionRequestId
) {
}

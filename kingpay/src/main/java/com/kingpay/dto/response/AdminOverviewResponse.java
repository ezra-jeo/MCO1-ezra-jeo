package com.kingpay.dto.response;

import java.util.List;

/** Read-only global data for a configured admin; no credentials or persistence entities.
 * <p>Preconditions: service verified the active caller's stable ID against the external allowlist.
 * <p>Postconditions: authorized DTO collections expose current rows, including inactive users and historical payments.
 * @param users identities and contact details, excluding hashes/tokens
 * @param accounts wallet owners and balances
 * @param bookings all booking states and nullable payment links
 * @param transfers immutable successful transfer records */
public record AdminOverviewResponse(List<UserResponse> users, List<AccountResponse> accounts,
        List<BookingResponse> bookings, List<TransferResponse> transfers) {
}

package com.kingpay.entity;

/** Booking lifecycle; either participant may be the first to confirm completion. */
public enum BookingStatus {
    Unconfirmed,
    CustomerConfirmed,
    ServiceProviderConfirmed,
    ConfirmedUnpaid,
    ConfirmedPaid
}

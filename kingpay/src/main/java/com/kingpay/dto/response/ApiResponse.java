package com.kingpay.dto.response;

/**
 * Minimal response envelope for the draft API.
 * <p>Preconditions: message describes the result; data is authorized for the caller.
 * <p>Postconditions: the message and payload are available as immutable values.
 * @param <T> payload type
 * @param message result description
 * @param data payload; null for responses without data
 */
public record ApiResponse<T>(String message, T data) {
}

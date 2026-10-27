package com.kingpay.exception;

/** Signals an explicitly validated request failure with a public-safe message. */
public class InvalidRequestException extends IllegalArgumentException {

    /**
     * Creates a request error without retaining rejected input values.
     * <p>Preconditions: message is a fixed public-safe description.
     * <p>Postconditions: the error can be translated to HTTP 400.
     * @param message description of the invalid request
     */
    public InvalidRequestException(String message) {
        super(message);
    }
}

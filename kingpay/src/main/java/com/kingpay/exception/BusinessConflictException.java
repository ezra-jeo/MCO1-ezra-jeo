package com.kingpay.exception;

/** A valid request conflicts with the current persisted domain state. */
public class BusinessConflictException extends RuntimeException {
    /** Preconditions: message is fixed and safe. Postconditions: advice can return 409.
     * @param message public conflict description */
    public BusinessConflictException(String message) {
        super(message);
    }
}

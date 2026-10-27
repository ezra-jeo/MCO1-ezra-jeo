package com.kingpay.exception;

/** Signals that a requested username conflicts with an existing registration. */
public class UsernameAlreadyExistsException extends RuntimeException {

    private static final String MESSAGE = "Username is already in use.";

    /**
     * Creates a conflict detected before registration writes begin.
     * <p>Preconditions: the username existence check found a matching row.
     * <p>Postconditions: a public-safe conflict message is available.
     */
    public UsernameAlreadyExistsException() {
        super(MESSAGE);
    }

    /**
     * Creates a conflict detected by the database's username unique constraint.
     * <p>Preconditions: cause identifies that specific constraint violation.
     * <p>Postconditions: the cause is retained for diagnostics but excluded from the public message.
     * @param cause database failure that rejected the duplicate username
     */
    public UsernameAlreadyExistsException(Throwable cause) {
        super(MESSAGE, cause);
    }
}

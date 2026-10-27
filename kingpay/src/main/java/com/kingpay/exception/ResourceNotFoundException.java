package com.kingpay.exception;

/** Signals that a resource required by an authorized operation does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Creates a missing-resource error without disclosing other records.
     * <p>Preconditions: message is a fixed resource-type description.
     * <p>Postconditions: the error can be translated to HTTP 404.
     * @param message description of the missing resource
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}

package com.kingpay.exception;

/** Signals an intentionally unfinished skeleton operation without claiming success. */
public class UnimplementedOperationException extends UnsupportedOperationException {

    /**
     * Describes the unfinished operation.
     * <p>Preconditions: operation identifies the requested action.
     * <p>Postconditions: the exception contains an explicit unfinished-operation message.
     * @param operation name of the unfinished action
     */
    public UnimplementedOperationException(String operation) {
        super(operation + " is not implemented.");
    }
}

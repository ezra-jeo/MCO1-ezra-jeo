package com.kingpay.exception;

/** Signals an operation causes an insufficient balance. */
public class InsufficientBalanceException extends RuntimeException {

    /**
     * Mentions the operation will cause an invalid balance
     * <p>Preconditions: operation identifies the requested action.
     * <p>Postconditions: the exception contains an explicit unfinished-operation message.
     * @param operation name of the unfinished action
     */
    public InsufficientBalanceException(String operation) {
        super(operation + " will result in an invalid balance.");
    }
}

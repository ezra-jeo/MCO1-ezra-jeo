package com.kingpay.exception;

/** Signals an operation causes an insufficient balance. */
public class InsufficientBalanceException extends RuntimeException {

    /**
     * Mentions the operation will cause an invalid balance
     * <p>Preconditions: operation identifies the requested action.
     * <p>Postconditions: the exception describes the rejected action; API advice returns a fixed safe conflict.
     * @param operation internal name of the attempted action
     */
    public InsufficientBalanceException(String operation) {
        super(operation + " will result in an invalid balance.");
    }
}

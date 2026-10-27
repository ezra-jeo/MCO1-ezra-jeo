package com.kingpay.exception;

import com.kingpay.entity.User;
import org.springframework.dao.DataIntegrityViolationException;

/** Shared classification for registration and profile-rename constraint races. */
public final class UsernameConflicts {
    /** Preconditions: none. Postconditions: utility cannot be instantiated. */
    private UsernameConflicts() { }

    /** Preconditions: exception is a persistence failure. Postconditions: no writes or diagnostic disclosure.
     * @param exception integrity failure
     * @return whether the named username constraint caused it */
    public static boolean matches(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint == null) { return false; }
                String unquoted = constraint.replace("`", "").replace("\"", "");
                String name = unquoted.substring(unquoted.lastIndexOf('.') + 1);
                return User.USERNAME_UNIQUE_CONSTRAINT.equalsIgnoreCase(name);
            }
        }
        return false;
    }
}

package com.kingpay.exception;

import com.kingpay.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.ErrorResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Handles implemented domain failures and intentional skeleton placeholders. */
@RestControllerAdvice
public class SkeletonExceptionHandler {

    /** Preconditions: a valid request conflicts with current state. Postconditions: safe HTTP 409.
     * @param exception public-safe state conflict
     * @return conflict envelope */
    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessConflict(BusinessConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(exception.getMessage(), null));
    }

    /**
     * Normalizes remaining failures without exposing framework, SQL, or credential diagnostics.
     * <p>Preconditions: no more specific handler matched the exception.
     * <p>Postconditions: framework HTTP statuses are preserved; other failures return a safe 500.
     * @param exception unhandled failure
     * @return generic error envelope
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        HttpStatusCode status = exception instanceof ErrorResponse error
                ? error.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        String message = status.is4xxClientError() ? "Request could not be processed." : "Unable to complete request.";
        return ResponseEntity.status(status).body(new ApiResponse<>(message, null));
    }

    /**
     * Rejects invalid request fields without echoing or logging rejected values.
     * <p>Preconditions: request-body Bean Validation failed before the controller ran.
     * <p>Postconditions: HTTP 400 exposes a generic message without credentials or field values.
     * @param exception validation failure; its rejected values are deliberately not inspected
     * @return HTTP 400 with no sensitive diagnostics
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleInvalidRequest(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>("Request validation failed.", errors));
    }

    /**
     * Reports failed credentials without disclosing identity or disabled-user state.
     * <p>Preconditions: password authentication failed.
     * <p>Postconditions: HTTP 401 has no internal cause or credential values.
     * @param exception authentication failure
     * @return safe authentication error
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse<>("Invalid credentials.", null));
    }

    /**
     * Reports forbidden resource ownership.
     * <p>Preconditions: a resource guard denied the authenticated caller.
     * <p>Postconditions: HTTP 403 discloses no other user's data.
     * @param exception denied access
     * @return safe authorization error
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiResponse<>("Access denied.", null));
    }

    /**
     * Reports missing protected-resource identifiers.
     * <p>Preconditions: a guard could not locate the resource.
     * <p>Postconditions: HTTP 404 contains only its resource-type description.
     * @param exception missing resource
     * @return safe missing-resource error
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiResponse<>(exception.getMessage(), null));
    }

    /**
     * Reports explicitly rejected inputs without retaining their values.
     * <p>Preconditions: a service or guard rejected the request.
     * <p>Postconditions: HTTP 400 contains a fixed safe reason.
     * @param exception safe request description
     * @return request error
     */
    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleExplicitRequest(InvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(exception.getMessage(), null));
    }

    /**
     * Reports proxy-level Bean Validation failures without rejected values.
     * <p>Preconditions: a validated service argument violated a declared constraint.
     * <p>Postconditions: HTTP 400 discloses no input or exception detail.
     * @param exception constraint failures
     * @return generic safe validation error
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintValidation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>("Request validation failed.", null));
    }

    /**
     * Rejects malformed JSON and parameter types without parser messages or raw values.
     * <p>Preconditions: request binding failed.
     * <p>Postconditions: HTTP 400 uses a generic envelope.
     * @param exception binding failure
     * @return safe malformed-request error
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleMalformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>("Malformed request.", null));
    }

    /**
     * Reports a username conflict without exposing persistence diagnostics.
     * <p>Preconditions: registration rejected a duplicate username.
     * <p>Postconditions: HTTP 409 contains only the public-safe message and no payload.
     * @param exception duplicate-username failure
     * @return HTTP 409 with the conflict message
     */
    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleUsernameConflict(UsernameAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(exception.getMessage(), null));
    }

    /**
     * Reports an unfinished operation without returning a successful placeholder result.
     * <p>Preconditions: an intentional skeleton operation threw the supplied exception.
     * <p>Postconditions: HTTP 501 is returned with an explicit message and no payload.
     * @param exception the unfinished operation
     * @return HTTP 501 and its message
     */
    @ExceptionHandler(UnimplementedOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnimplemented(UnimplementedOperationException exception) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new ApiResponse<>(exception.getMessage(), null));
    }

    /** Preconditions: a debit failed for insufficient funds. Postconditions: safe HTTP 409, no diagnostics.
     * @param exception rejected debit
     * @return conflict envelope */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiResponse<Void>> handleInsufficientBalance(InsufficientBalanceException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>("Insufficient funds.", null));
    }
}

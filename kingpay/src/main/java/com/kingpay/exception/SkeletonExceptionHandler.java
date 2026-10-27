package com.kingpay.exception;

import com.kingpay.dto.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps only intentional skeleton placeholders; final application errors belong to later phases. */
@RestControllerAdvice
public class SkeletonExceptionHandler {

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
}

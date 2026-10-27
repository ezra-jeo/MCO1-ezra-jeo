package com.kingpay.controller;

import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.dto.response.ApiResponse;
import com.kingpay.dto.response.SuperheroResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.service.UserService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Draft HTTP routes; service stubs deliberately report HTTP 501. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    /**
     * Receives the application service through constructor injection.
     *
     * <p>Preconditions: Spring supplies the service instance.
     * <p>Postconditions: The controller delegates operations to the supplied service.
     * @param service owning application service
     */
    public UserController(UserService service) {
        this.service = service;
    }

    /**
     * Routes own profile lookup to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(Principal caller) {
        UserResponse result = service.getMe(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Profile retrieved", result));
    }

    /**
     * Routes own profile update to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param request validated request body
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(@Valid @RequestBody UpdateUserRequest request, Principal caller) {
        UserResponse result = service.updateMe(request, caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Profile updated", result));
    }

    /**
     * Routes user deactivation to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deactivateMe(Principal caller) {
        service.deactivateMe(caller);
        return ResponseEntity.noContent().build();
    }

    /**
     * Routes superhero browsing to the Phase 3 service.
     *
     * <p>Preconditions: Request syntax is valid; security and business behavior remain unfinished.
     * <p>Postconditions: The skeleton service throws without changing state.
     * @param caller framework-supplied authenticated principal; may be absent until Phase 2
     * @return the planned response when implemented; currently HTTP 501 via the exception handler
     */
    @GetMapping("/superheroes")
    public ResponseEntity<ApiResponse<List<SuperheroResponse>>> getSuperheroes(Principal caller) {
        List<SuperheroResponse> result = service.getSuperheroes(caller);
        return ResponseEntity.status(HttpStatus.OK)
                .body(new ApiResponse<>("Superheroes retrieved", result));
    }
}

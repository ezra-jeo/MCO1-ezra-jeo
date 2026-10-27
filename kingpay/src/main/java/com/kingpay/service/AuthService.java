package com.kingpay.service;

import com.kingpay.dto.request.LoginRequest;
import com.kingpay.dto.request.RegisterRequest;
import com.kingpay.dto.response.AuthResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.exception.UnimplementedOperationException;
import org.springframework.stereotype.Service;

/** Draft auth boundary; no business operations are implemented. */
@Service
public class AuthService {

    /**
     * Reserves user registration for Phase 2.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public UserResponse register(RegisterRequest request) {
        throw new UnimplementedOperationException("User registration");
    }

    /**
     * Reserves user login for Phase 2.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public AuthResponse login(LoginRequest request) {
        throw new UnimplementedOperationException("User login");
    }
}

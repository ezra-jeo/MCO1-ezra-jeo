package com.kingpay.service;

import com.kingpay.dto.request.CreateAccountRequest;
import com.kingpay.dto.response.AccountResponse;
import com.kingpay.exception.UnimplementedOperationException;
import java.security.Principal;
import org.springframework.stereotype.Service;

/** Draft account boundary; no business operations are implemented. */
@Service
public class AccountService {

    /**
     * Reserves demo account creation for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public AccountResponse create(CreateAccountRequest request, Principal caller) {
        throw new UnimplementedOperationException("Demo account creation");
    }

    /**
     * Reserves own account lookup for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public AccountResponse getMe(Principal caller) {
        throw new UnimplementedOperationException("Own account lookup");
    }
}

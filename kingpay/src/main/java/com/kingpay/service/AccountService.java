package com.kingpay.service;

import com.kingpay.dto.request.LoadAccountRequest;
import com.kingpay.dto.response.AccountResponse;
import com.kingpay.exception.UnimplementedOperationException;
import java.security.Principal;
import org.springframework.stereotype.Service;

/** Draft account boundary; no business operations are implemented. */
@Service
public class AccountService {

    /**
     * Reserves zero-balance account creation for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public AccountResponse create(Principal caller) {
        throw new UnimplementedOperationException("Zero-balance account creation");
    }

    /**
     * Reserves simulated wallet loading for Phase 3.
     *
     * <p>Preconditions: the future service requires an active authenticated owner,
     * an existing account, and a valid positive amount.
     * <p>Postconditions: this placeholder changes no balance and creates no ledger row.
     * @param request the amount to load into the caller's own account
     * @param caller authenticated principal; never supplied by the request body
     * @return the future updated account; this skeleton always throws
     * @throws UnimplementedOperationException always, until loading is implemented
     */
    public AccountResponse load(LoadAccountRequest request, Principal caller) {
        throw new UnimplementedOperationException("Simulated wallet loading");
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

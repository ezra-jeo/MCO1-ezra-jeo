package com.kingpay.service;

import com.kingpay.dto.request.UpdateUserRequest;
import com.kingpay.dto.response.SuperheroResponse;
import com.kingpay.dto.response.UserResponse;
import com.kingpay.exception.UnimplementedOperationException;
import java.security.Principal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Draft user boundary; no business operations are implemented. */
@Service
public class UserService {

    /**
     * Reserves own profile lookup for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public UserResponse getMe(Principal caller) {
        throw new UnimplementedOperationException("Own profile lookup");
    }

    /**
     * Reserves own profile update for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public UserResponse updateMe(UpdateUserRequest request, Principal caller) {
        throw new UnimplementedOperationException("Own profile update");
    }

    /**
     * Reserves user deactivation for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param caller authenticated principal; never supplied by the request body
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public void deactivateMe(Principal caller) {
        throw new UnimplementedOperationException("User deactivation");
    }

    /**
     * Reserves superhero browsing for Phase 3.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public List<SuperheroResponse> getSuperheroes(Principal caller) {
        throw new UnimplementedOperationException("Superhero browsing");
    }
}

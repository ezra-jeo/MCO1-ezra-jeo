package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.exception.UnimplementedOperationException;
import com.kingpay.security.OwnershipGuard;
import java.security.Principal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Draft transfer boundary; no business operations are implemented. */
@Service
public class TransferService {

    private final OwnershipGuard ownership;

    /**
     * Receives resource authorization for the later transfer implementation.
     * <p>Preconditions: ownership is non-null.
     * <p>Postconditions: protected operations verify the source/history owner.
     * @param ownership authorization guard
     */
    public TransferService(OwnershipGuard ownership) {
        this.ownership = ownership;
    }

    /**
     * Reserves standalone money transfer for Phase 4.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public TransferResponse transferMoney(TransferRequest request, Principal caller) {
        ownership.accountOwner(request.fromId(), caller);
        throw new UnimplementedOperationException("Standalone money transfer");
    }

    /**
     * Reserves private transfer history for Phase 4.
     *
     * <p>Preconditions: HTTP callers are authenticated; resource-ID operations enforce ownership before the placeholder.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param accountId operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException after authorization succeeds, until business behavior is implemented
     */
    public List<TransferResponse> getTransferHistory(Long accountId, Principal caller) {
        ownership.accountOwner(accountId, caller);
        throw new UnimplementedOperationException("Private transfer history");
    }
}

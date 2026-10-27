package com.kingpay.service;

import com.kingpay.dto.request.TransferRequest;
import com.kingpay.dto.response.TransferResponse;
import com.kingpay.exception.UnimplementedOperationException;
import java.security.Principal;
import java.util.List;
import org.springframework.stereotype.Service;

/** Draft transfer boundary; no business operations are implemented. */
@Service
public class TransferService {

    /**
     * Reserves standalone money transfer for Phase 4.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param request operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public TransferResponse transferMoney(TransferRequest request, Principal caller) {
        throw new UnimplementedOperationException("Standalone money transfer");
    }

    /**
     * Reserves private transfer history for Phase 4.
     *
     * <p>Preconditions: Caller identity and domain rules will be checked in the owning phase.
     * <p>Postconditions: This operation remains unimplemented and changes no state.
     * @param accountId operation input
     * @param caller authenticated principal; never supplied by the request body
     * @return the future operation result; this skeleton always throws
     * @throws UnimplementedOperationException always, until the owning phase is implemented
     */
    public List<TransferResponse> getTransferHistory(Long accountId, Principal caller) {
        throw new UnimplementedOperationException("Private transfer history");
    }
}

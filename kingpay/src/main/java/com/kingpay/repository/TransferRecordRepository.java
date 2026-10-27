package com.kingpay.repository;

import com.kingpay.entity.TransferRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Ledger persistence; ordinary services/controllers must not expose ledger edit/delete operations. */
@Repository
public interface TransferRecordRepository extends JpaRepository<TransferRecord, Long> {

    /**
     * Lists an account's successful incoming and outgoing transfers.
     * <p>Preconditions: the service verifies ownership and passes that account's ID
     * as both parameters. Records are assumed to represent successful transfers.
     * <p>Postconditions: no data changes; newest transfer time comes first, with descending ID ties.
     * @param sourceAccountId account ID to match as sender
     * @param destinationAccountId same account ID to match as recipient
     * @return matching ledger rows in deterministic newest-first order, or an empty list
     */
    List<TransferRecord> findBySourceAccount_IdOrDestinationAccount_IdOrderByTransferDateDescIdDesc(
            Long sourceAccountId, Long destinationAccountId);
}

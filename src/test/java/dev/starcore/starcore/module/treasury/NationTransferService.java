package dev.starcore.starcore.module.treasury;

import dev.starcore.starcore.module.nation.model.NationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Test-only exact subset of the audited public national transfer contract, never packaged. */
public interface NationTransferService {
    enum TransferCategory { REPARATION, TRIBUTE, TREATY, LOAN, OTHER }
    enum TransferStatus { APPLIED, ALREADY_APPLIED, INSUFFICIENT_FUNDS, REJECTED }
    record TransferRequest(String transactionId,NationId payerId,NationId receiverId,BigDecimal amount,TransferCategory category,String description){}
    record TransferReceipt(String transactionId,NationId payerId,NationId receiverId,BigDecimal amount,TransferCategory category,String description,Instant appliedAt){}
    record TransferResult(TransferStatus status,String transactionId,BigDecimal payerBalanceAfter,BigDecimal receiverBalanceAfter){}
    TransferResult transfer(TransferRequest request);
    Optional<TransferReceipt> transferReceipt(String transactionId);
}

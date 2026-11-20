package dev.starcore.starcore.module.resource;

import dev.starcore.starcore.module.nation.model.NationId;
import java.time.Instant;
import java.util.*;
/** Test-only audited public transaction signature; never packaged with StarWorld. */
public interface NationResourceTransactionService {
    enum Operation { CONSUME,GRANT }
    enum Category { RESEARCH,COMPENSATION_REFUND,OTHER }
    enum Status { APPLIED,ALREADY_APPLIED,INSUFFICIENT_RESOURCES,REJECTED }
    record TransactionRequest(String transactionId,NationId nationId,Map<String,Long> resources,Category category,String description) {}
    record TransactionReceipt(String transactionId,NationId nationId,Map<String,Long> resources,Operation operation,Category category,String description,Instant appliedAt) {}
    record TransactionResult(Status status,String transactionId,Map<String,Long> stockpileAfter) {}
    TransactionResult grant(TransactionRequest request);
    Optional<TransactionReceipt> transactionReceipt(String transactionId);
}

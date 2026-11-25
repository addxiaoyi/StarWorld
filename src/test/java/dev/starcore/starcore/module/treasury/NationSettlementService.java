package dev.starcore.starcore.module.treasury;

import dev.starcore.starcore.module.nation.model.NationId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Test-only public contract subset; production is the separately built upstream patch. */
public interface NationSettlementService {
    enum SettlementStatus { APPLIED, ALREADY_APPLIED, INSUFFICIENT_FUNDS, REJECTED }
    record SettlementRequest(String transactionId,NationId payerId,Map<NationId,BigDecimal> credits,String description){}
    record SettlementReceipt(String transactionId,NationId payerId,Map<NationId,BigDecimal> credits,String description,Instant appliedAt){}
    record SettlementRefundRequest(String transactionId,String originalTransactionId){}
    record SettlementRefundReceipt(String transactionId,String originalTransactionId,NationId payerId,Map<NationId,BigDecimal> credits,Instant appliedAt){}
    record SettlementResult(SettlementStatus status,String transactionId,Map<NationId,BigDecimal> balancesAfter){}
    SettlementResult refundSettlement(SettlementRefundRequest request);
    SettlementResult settle(SettlementRequest request);
    Optional<SettlementReceipt> settlementReceipt(String transactionId);
    Optional<SettlementRefundReceipt> settlementRefundReceipt(String transactionId);
    Optional<SettlementRefundReceipt> refundForPayment(String originalTransactionId);
}

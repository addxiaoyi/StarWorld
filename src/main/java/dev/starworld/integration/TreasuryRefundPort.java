package dev.starworld.integration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** A verified full reversal of an original national payment; never a newly minted credit. */
public interface TreasuryRefundPort {
    record Request(String transactionId,TreasuryTransferPort.Request original) {
        public Request {id(transactionId);Objects.requireNonNull(original);if(transactionId.equals(original.transactionId()))throw new IllegalArgumentException("退款必须使用独立凭据");}
    }
    record Receipt(String transactionId,String originalTransactionId,UUID payer,Map<UUID,BigDecimal> credits,Instant appliedAt) {
        public Receipt {id(transactionId);id(originalTransactionId);Objects.requireNonNull(payer);Objects.requireNonNull(appliedAt);
            if(credits==null||credits.isEmpty()||credits.size()>16)throw new IllegalArgumentException("退款收款明细无效");
            Map<UUID,BigDecimal> normalized=new HashMap<>();credits.forEach((nation,amount)->{Objects.requireNonNull(nation);if(nation.equals(payer))throw new IllegalArgumentException("退款原付款方不能是原收款方");normalized.put(nation,TreasuryTransferPort.money(amount));});credits=Map.copyOf(normalized);}
        public boolean matches(Request request){var original=request.original();return transactionId.equals(request.transactionId())&&originalTransactionId.equals(original.transactionId())&&payer.equals(original.payer())&&credits.equals(original.credits());}
    }
    record Delivery(String transactionId,TreasuryTransferPort.Status status,Receipt receipt,String detail) {
        public Delivery {id(transactionId);Objects.requireNonNull(status);detail=detail==null?"":detail.substring(0,Math.min(1000,detail.length()));}
    }
    Delivery refund(Request request);
    Optional<Receipt> receipt(String transactionId);
    String status();
    private static void id(String value){if(value==null||value.isBlank()||value.length()>200)throw new IllegalArgumentException("退款凭据无效");}
    static TreasuryRefundPort unavailable(){return new TreasuryRefundPort(){
        public Delivery refund(Request request){return new Delivery(request.transactionId(),TreasuryTransferPort.Status.RETRY,null,"核心退款接口尚未启用");}
        public Optional<Receipt> receipt(String id){throw new IllegalStateException("核心退款回执不可查询");}
        public String status(){return "核心原子退款不可用";}
    };}
}

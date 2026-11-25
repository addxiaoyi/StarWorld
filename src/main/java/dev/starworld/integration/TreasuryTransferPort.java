package dev.starworld.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Public national treasury transfer boundary. All implementation calls require the Paper main thread. */
public interface TreasuryTransferPort {
    enum Status { CONFIRMED, RETRY, REJECTED, CONFLICT }
    record Request(String transactionId,UUID payer,Map<UUID,BigDecimal> credits,String description) {
        public Request { transactionId=identifier(transactionId);credits=allocation(payer,credits);description=description==null?"":description.substring(0,Math.min(200,description.length())); }
        public Request(String id,UUID payer,UUID receiver,BigDecimal amount,String description){this(id,payer,Map.of(receiver,amount),description);}
        public BigDecimal amount(){return total(credits);}
        /** Legacy single-beneficiary accessor; multi-party callers must inspect the allocation. */
        public UUID receiver(){if(credits.size()!=1)throw new IllegalStateException("付款包含多个收款国家");return credits.keySet().iterator().next();}
    }
    record Receipt(String transactionId,UUID payer,UUID receiver,BigDecimal amount,String category,Instant appliedAt,Map<UUID,BigDecimal> credits,String description) {
        public Receipt { transactionId=identifier(transactionId);credits=allocation(payer,credits);amount=money(amount);Objects.requireNonNull(category);Objects.requireNonNull(appliedAt);Objects.requireNonNull(description);if(amount.compareTo(total(credits))!=0||!Objects.equals(receiver,credits.size()==1?credits.keySet().iterator().next():null))throw new IllegalArgumentException("付款回执合计或收款方不符"); }
        public Receipt(String id,UUID payer,UUID receiver,BigDecimal amount,String category,Instant at){this(id,payer,receiver,amount,category,at,Map.of(receiver,amount),"");}
        public Receipt(String id,UUID payer,Map<UUID,BigDecimal> credits,String category,Instant at,String description){this(id,payer,credits.size()==1?credits.keySet().iterator().next():null,total(credits),category,at,credits,description);}
        public boolean matches(Request request){return transactionId.equals(request.transactionId())&&payer.equals(request.payer())&&credits.equals(request.credits())&&(credits.size()==1?category.equals("OTHER"):category.equals("SETTLEMENT")&&description.equals(request.description()));}
    }
    record Delivery(String transactionId,Status status,Receipt receipt,String detail) {
        public Delivery { transactionId=identifier(transactionId);Objects.requireNonNull(status);detail=detail==null?"":detail.substring(0,Math.min(1000,detail.length())); }
    }
    Delivery transfer(Request request);
    Optional<Receipt> receipt(String transactionId);
    /** A missing multi-party service must never be interpreted as proof of non-payment. */
    default Optional<Receipt> receipt(Request request){if(request.credits().size()!=1)throw new IllegalStateException("核心多方结算回执不可查询");return receipt(request.transactionId());}
    String status();
    static Map<UUID,BigDecimal> allocation(UUID payer,Map<UUID,BigDecimal> input){
        Objects.requireNonNull(payer);if(input==null||input.isEmpty()||input.size()>16)throw new IllegalArgumentException("国家结算须含1至16个收款方");
        Map<UUID,BigDecimal> result=new HashMap<>();input.forEach((nation,amount)->{Objects.requireNonNull(nation);if(nation.equals(payer))throw new IllegalArgumentException("付款国不能同时收款");result.put(nation,money(amount));});money(total(result));return Map.copyOf(result);
    }
    static BigDecimal total(Map<UUID,BigDecimal> values){return values.values().stream().reduce(new BigDecimal("0.00"),BigDecimal::add);}
    static BigDecimal money(BigDecimal value){
        Objects.requireNonNull(value);
        // Reject exponent-sized values before setScale can try to construct an enormous power of ten.
        if(value.signum()<=0||value.compareTo(new BigDecimal("0.01"))<0||value.compareTo(new BigDecimal("1000000000000.00"))>0)throw new IllegalArgumentException("金币金额必须为0.01至一万亿，最多两位小数");
        if(value.scale()>64||value.precision()>80)throw new IllegalArgumentException("金币金额表示过长");
        try{return value.setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException fraction){throw new IllegalArgumentException("金币金额最多两位小数",fraction);}
    }
    private static String identifier(String id){if(id==null||id.isBlank()||id.length()>200)throw new IllegalArgumentException("Invalid treasury transaction id");return id;}
}

package dev.starworld.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Public national treasury transfer boundary. All implementation calls require the Paper main thread. */
public interface TreasuryTransferPort {
    enum Status { CONFIRMED, RETRY, REJECTED, CONFLICT }
    record Request(String transactionId,UUID payer,UUID receiver,BigDecimal amount,String description) {
        public Request { transactionId=identifier(transactionId);Objects.requireNonNull(payer);Objects.requireNonNull(receiver);if(payer.equals(receiver))throw new IllegalArgumentException("国家付款双方必须不同");amount=money(amount);description=description==null?"":description.substring(0,Math.min(200,description.length())); }
    }
    record Receipt(String transactionId,UUID payer,UUID receiver,BigDecimal amount,String category,Instant appliedAt) {
        public Receipt { transactionId=identifier(transactionId);Objects.requireNonNull(payer);Objects.requireNonNull(receiver);amount=money(amount);Objects.requireNonNull(category);Objects.requireNonNull(appliedAt); }
        public boolean matches(Request request){return transactionId.equals(request.transactionId())&&payer.equals(request.payer())&&receiver.equals(request.receiver())&&amount.compareTo(request.amount())==0&&category.equals("OTHER");}
    }
    record Delivery(String transactionId,Status status,Receipt receipt,String detail) {
        public Delivery { transactionId=identifier(transactionId);Objects.requireNonNull(status);detail=detail==null?"":detail.substring(0,Math.min(1000,detail.length())); }
    }
    Delivery transfer(Request request);
    Optional<Receipt> receipt(String transactionId);
    String status();
    static BigDecimal money(BigDecimal value){
        Objects.requireNonNull(value);
        // Reject exponent-sized values before setScale can try to construct an enormous power of ten.
        if(value.signum()<=0||value.compareTo(new BigDecimal("0.01"))<0||value.compareTo(new BigDecimal("1000000000000.00"))>0)throw new IllegalArgumentException("金币金额必须为0.01至一万亿，最多两位小数");
        if(value.scale()>64||value.precision()>80)throw new IllegalArgumentException("金币金额表示过长");
        try{return value.setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException fraction){throw new IllegalArgumentException("金币金额最多两位小数",fraction);}
    }
    private static String identifier(String id){if(id==null||id.isBlank()||id.length()>200)throw new IllegalArgumentException("Invalid treasury transaction id");return id;}
}

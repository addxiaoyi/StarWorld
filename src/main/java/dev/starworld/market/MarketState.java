package dev.starworld.market;

import dev.starworld.integration.TreasuryTransferPort;
import java.math.BigDecimal;
import java.util.*;

public final class MarketState {
    private MarketState(){}
    public enum ListingStatus { AVAILABLE, LOCKED, SHIPPED, CANCELLED, EXPIRED }
    public enum PurchaseStatus { PENDING, IN_FLIGHT, RETRY, REJECTED, REVIEW, PAID, SHIPPED, CANCELLED }
    public record Listing(UUID id,UUID sellerCity,UUID sellerNation,String item,long units,BigDecimal unitPrice,ListingStatus status,long createdPeriod,long expiresPeriod){public BigDecimal total(){return TreasuryTransferPort.money(unitPrice.multiply(BigDecimal.valueOf(units)));}public Map<String,Long> cargo(){return Map.of(item,units);}}
    public record Purchase(UUID id,UUID listingId,UUID buyerCity,UUID buyerNation,UUID sellerCity,UUID sellerNation,UUID routeId,UUID shipmentId,String transactionId,BigDecimal total,PurchaseStatus status,int attempts,long nextAttempt,String detail,long createdPeriod){public TreasuryTransferPort.Request request(){return new TreasuryTransferPort.Request(transactionId,buyerNation,sellerNation,total,"StarWorld实体贸易 "+id);}}
    public record Dispatch(Purchase purchase,String leaseToken){}
    public record PriceHistory(UUID listingId,String item,long units,BigDecimal unitPrice,UUID sellerCity,UUID buyerCity,long period){}
    /** Completed paid deliveries, aggregated from the durable logistics completion state. */
    public record DeliveryStats(String item,long orders,long units,BigDecimal gross,BigDecimal averageUnitPrice,long firstCompletedPeriod,long lastCompletedPeriod){
        public DeliveryStats {Objects.requireNonNull(item);Objects.requireNonNull(gross);Objects.requireNonNull(averageUnitPrice);if(orders<0||units<0||gross.signum()<0||averageUnitPrice.signum()<0||firstCompletedPeriod<0||lastCompletedPeriod<firstCompletedPeriod)throw new IllegalArgumentException("Invalid delivery statistics");}
    }
}

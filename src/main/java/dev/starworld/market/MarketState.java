package dev.starworld.market;

import dev.starworld.integration.TreasuryTransferPort;
import dev.starworld.integration.MarketTaxPort;
import dev.starworld.integration.TransitPermitPort.Permit;
import dev.starworld.logistics.TransitCharges;
import java.math.BigDecimal;
import java.util.*;

public final class MarketState {
    private MarketState(){}
    public enum ListingStatus { AVAILABLE, LOCKED, SHIPPED, CANCELLED, EXPIRED }
    public enum PurchaseStatus { PENDING, IN_FLIGHT, RETRY, REJECTED, REVIEW, PAID, SHIPPED, REFUNDING, REFUNDED, CANCELLED }
    /** Original quantity is immutable; the four balances always partition that original escrow. */
    public record Listing(UUID id,UUID sellerCity,UUID sellerNation,String item,long units,BigDecimal unitPrice,
                          ListingStatus status,long createdPeriod,long expiresPeriod,
                          long availableUnits,long reservedUnits,long shippedUnits,long returnedUnits){
        public Listing {
            Objects.requireNonNull(id);Objects.requireNonNull(sellerCity);Objects.requireNonNull(sellerNation);
            Objects.requireNonNull(item);Objects.requireNonNull(unitPrice);Objects.requireNonNull(status);
            if(units<1||availableUnits<0||reservedUnits<0||shippedUnits<0||returnedUnits<0
                    ||Math.addExact(Math.addExact(availableUnits,reservedUnits),Math.addExact(shippedUnits,returnedUnits))!=units)
                throw new IllegalArgumentException("Invalid market escrow balances");
        }
        /** Legacy whole-lot snapshot constructor. */
        public Listing(UUID id,UUID sellerCity,UUID sellerNation,String item,long units,BigDecimal unitPrice,
                       ListingStatus status,long createdPeriod,long expiresPeriod){
            this(id,sellerCity,sellerNation,item,units,unitPrice,status,createdPeriod,expiresPeriod,
                    status==ListingStatus.AVAILABLE?units:0,status==ListingStatus.LOCKED?units:0,
                    status==ListingStatus.SHIPPED?units:0,
                    status==ListingStatus.CANCELLED||status==ListingStatus.EXPIRED?units:0);
        }
        public BigDecimal total(){return TreasuryTransferPort.money(unitPrice.multiply(BigDecimal.valueOf(units)));}
        public BigDecimal availableTotal(){return unitPrice.multiply(BigDecimal.valueOf(availableUnits));}
        public Map<String,Long> cargo(){return Map.of(item,units);}
    }
    public record Purchase(UUID id,UUID listingId,UUID buyerCity,UUID buyerNation,UUID sellerCity,UUID sellerNation,UUID routeId,UUID shipmentId,String transactionId,BigDecimal total,PurchaseStatus status,int attempts,long nextAttempt,String detail,long createdPeriod,long units,BigDecimal goodsTotal,BigDecimal taxAmount,UUID taxQuote,Map<UUID,Permit> transit){
        public Purchase {if(units<1)throw new IllegalArgumentException("Purchase quantity must be positive");transit=Map.copyOf(transit);total=TreasuryTransferPort.money(total);goodsTotal=TreasuryTransferPort.money(goodsTotal);taxAmount=MarketTaxPort.tax(taxAmount);if(total.compareTo(goodsTotal.add(taxAmount).add(TreasuryTransferPort.total(TransitCharges.tolls(transit,units))))!=0||taxAmount.compareTo(goodsTotal)>0||(taxAmount.signum()>0||!transit.isEmpty())&&taxQuote==null||transit.values().stream().anyMatch(p->!p.terms().originNation().equals(sellerNation)||!p.terms().targetNation().equals(buyerNation)))throw new IllegalArgumentException("购买税费、过境费与总额不符");}
        public Purchase(UUID id,UUID listingId,UUID buyerCity,UUID buyerNation,UUID sellerCity,UUID sellerNation,UUID routeId,UUID shipmentId,String transactionId,BigDecimal total,PurchaseStatus status,int attempts,long nextAttempt,String detail,long createdPeriod,long units,BigDecimal goodsTotal,BigDecimal taxAmount,UUID taxQuote){this(id,listingId,buyerCity,buyerNation,sellerCity,sellerNation,routeId,shipmentId,transactionId,total,status,attempts,nextAttempt,detail,createdPeriod,units,goodsTotal,taxAmount,taxQuote,Map.of());}
        public Purchase(UUID id,UUID listingId,UUID buyerCity,UUID buyerNation,UUID sellerCity,UUID sellerNation,UUID routeId,UUID shipmentId,String transactionId,BigDecimal total,PurchaseStatus status,int attempts,long nextAttempt,String detail,long createdPeriod,long units){this(id,listingId,buyerCity,buyerNation,sellerCity,sellerNation,routeId,shipmentId,transactionId,total,status,attempts,nextAttempt,detail,createdPeriod,units,total,new BigDecimal("0.00"),null);}
        public BigDecimal tollTotal(){return TreasuryTransferPort.total(TransitCharges.tolls(transit,units));}
        public TreasuryTransferPort.Request request(){Map<UUID,BigDecimal> credits=new HashMap<>(TransitCharges.tolls(transit,units));credits.put(sellerNation,goodsTotal.add(taxAmount));return new TreasuryTransferPort.Request(transactionId,buyerNation,credits,"StarWorld实体贸易 "+id);}
    }
    public record Dispatch(Purchase purchase,String leaseToken){}
    public record PriceHistory(UUID listingId,String item,long units,BigDecimal unitPrice,UUID sellerCity,UUID buyerCity,long period,UUID purchaseId){}
    /** Completed paid deliveries, aggregated from the durable logistics completion state. */
    public record DeliveryStats(String item,long orders,long units,BigDecimal gross,BigDecimal averageUnitPrice,long firstCompletedPeriod,long lastCompletedPeriod){
        public DeliveryStats {Objects.requireNonNull(item);Objects.requireNonNull(gross);Objects.requireNonNull(averageUnitPrice);if(orders<0||units<0||gross.signum()<0||averageUnitPrice.signum()<0||firstCompletedPeriod<0||lastCompletedPeriod<firstCompletedPeriod)throw new IllegalArgumentException("Invalid delivery statistics");}
    }
}

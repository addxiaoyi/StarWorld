package dev.starworld.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/** Read-only assessment of the exporting nation's tariff, funded by the buyer's treasury. */
public interface MarketTaxPort {
    record Quote(UUID id,UUID collectingNation,UUID payerActor,BigDecimal goodsTotal,BigDecimal taxAmount) {
        public Quote {
            Objects.requireNonNull(id);Objects.requireNonNull(collectingNation);Objects.requireNonNull(payerActor);
            goodsTotal=TreasuryTransferPort.money(goodsTotal);taxAmount=tax(taxAmount);
            if(taxAmount.compareTo(goodsTotal)>0)throw new IllegalArgumentException("关税报价超过货物金额");
            TreasuryTransferPort.money(goodsTotal.add(taxAmount));
        }
        public BigDecimal total(){return goodsTotal.add(taxAmount);}
    }
    Quote quote(UUID id,UUID collectingNation,UUID payerActor,BigDecimal goodsTotal);
    String status();
    static BigDecimal tax(BigDecimal amount){
        Objects.requireNonNull(amount);
        if(amount.signum()==0)return new BigDecimal("0.00");
        return TreasuryTransferPort.money(amount).setScale(2,RoundingMode.UNNECESSARY);
    }
}

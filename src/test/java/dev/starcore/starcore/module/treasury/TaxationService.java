package dev.starcore.starcore.module.treasury;

import dev.starcore.starcore.module.nation.model.NationId;
import java.math.BigDecimal;
import java.util.UUID;

/** Test-only public signature fixture; no imitation of the upstream tax implementation. */
public interface TaxationService {
    enum TaxType { LAND_TAX, BUSINESS_TAX, INCOME_TAX, TARIFF }
    record TaxQuote(UUID transactionId,NationId nationId,TaxType type,UUID payerId,BigDecimal taxableAmount,BigDecimal taxAmount){}
    TaxQuote quoteTax(UUID id,NationId nation,TaxType type,UUID payer,BigDecimal taxable);
    BigDecimal fundTax(TaxQuote quote);
    BigDecimal reverseFundedTax(TaxQuote quote);
}

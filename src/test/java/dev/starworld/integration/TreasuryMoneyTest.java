package dev.starworld.integration;

import dev.starworld.market.MarketController;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreasuryMoneyTest {
    @Test void hugePositiveAndNegativeExponentsFailBeforeRescaling(){
        for(String value:new String[]{"1e100000000","1e-100000000","1e2147483647","1e-2147483647"})
            assertThrows(IllegalArgumentException.class,()->TreasuryTransferPort.money(new BigDecimal(value)),value);
    }
    @Test void ordinaryExactCentsAndBoundariesKeepTheirValue(){
        assertEquals(new BigDecimal("0.01"),TreasuryTransferPort.money(new BigDecimal("0.01")));
        assertEquals(new BigDecimal("125.50"),TreasuryTransferPort.money(new BigDecimal("125.5000")));
        assertEquals(new BigDecimal("1000000000000.00"),TreasuryTransferPort.money(new BigDecimal("1000000000000")));
        for(String value:new String[]{"0","-1","0.001","1.001","1000000000000.01"})assertThrows(IllegalArgumentException.class,()->TreasuryTransferPort.money(new BigDecimal(value)));
    }
    @Test void playerPriceSyntaxRejectsScientificNotationBeforeBigDecimalParsing(){
        assertEquals(new BigDecimal("1.25"),MarketController.parsePrice("1.25"));
        assertEquals(new BigDecimal("1000000000000.00"),MarketController.parsePrice("1000000000000.00"));
        for(String value:new String[]{"1e100000000","1e-100000000","1E2","NaN","-1","1.001","01.00","1".repeat(1000),"1000000000001"})assertThrows(IllegalArgumentException.class,()->MarketController.parsePrice(value),value);
    }
}

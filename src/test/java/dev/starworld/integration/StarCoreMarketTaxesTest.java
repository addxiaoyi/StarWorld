package dev.starworld.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TaxationService;
import dev.starcore.starcore.module.treasury.TaxationService.*;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.Server;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StarCoreMarketTaxesTest {
    final UUID id=UUID.randomUUID(),nation=UUID.randomUUID(),actor=UUID.randomUUID();
    final BigDecimal base=new BigDecimal("25.00");Server server;Plugin core;StarCoreApi api;TaxationService taxes;StarCoreMarketTaxes port;
    @BeforeEach void setup(){
        JavaPlugin owner=mock(JavaPlugin.class);server=mock(Server.class);PluginManager plugins=mock(PluginManager.class);ServicesManager services=mock(ServicesManager.class);core=mock(Plugin.class);api=mock(StarCoreApi.class);taxes=mock(TaxationService.class);
        when(owner.getServer()).thenReturn(server);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(plugins);when(server.getServicesManager()).thenReturn(services);when(plugins.getPlugin("StarCore")).thenReturn(core);when(core.isEnabled()).thenReturn(true);when(services.load(StarCoreApi.class)).thenReturn(api);when(api.service(TaxationService.class)).thenReturn(Optional.of(taxes));port=new StarCoreMarketTaxes(owner);
    }
    TaxQuote proof(BigDecimal fee){return new TaxQuote(id,NationId.of(nation),TaxType.TARIFF,actor,base,fee);}
    @Test void quotesUseTheExactPublicTariffAndNeverFundOrReverseTaxSeparately(){
        when(taxes.quoteTax(id,NationId.of(nation),TaxType.TARIFF,actor,base)).thenReturn(proof(new BigDecimal("0.75")),proof(BigDecimal.ZERO));
        var quoted=port.quote(id,nation,actor,base);assertEquals(new BigDecimal("25.75"),quoted.total());assertEquals(nation,quoted.collectingNation());assertEquals(actor,quoted.payerActor());
        assertEquals(new BigDecimal("0.00"),port.quote(id,nation,actor,base).taxAmount());verify(taxes,never()).fundTax(any());verify(taxes,never()).reverseFundedTax(any());
    }
    @Test void wrongIdentityNationActorTaxTypeOrTaxableBaseCannotBecomePaymentTerms(){
        for(TaxQuote wrong:List.of(new TaxQuote(UUID.randomUUID(),NationId.of(nation),TaxType.TARIFF,actor,base,BigDecimal.ONE),
                new TaxQuote(id,NationId.of(UUID.randomUUID()),TaxType.TARIFF,actor,base,BigDecimal.ONE),
                new TaxQuote(id,NationId.of(nation),TaxType.BUSINESS_TAX,actor,base,BigDecimal.ONE),
                new TaxQuote(id,NationId.of(nation),TaxType.TARIFF,UUID.randomUUID(),base,BigDecimal.ONE),
                new TaxQuote(id,NationId.of(nation),TaxType.TARIFF,actor,new BigDecimal("26.00"),BigDecimal.ONE))){
            when(taxes.quoteTax(any(),any(),any(),any(),any())).thenReturn(wrong);assertThrows(IllegalArgumentException.class,()->port.quote(id,nation,actor,base));
        }
        verify(taxes,never()).fundTax(any());
    }
    @Test void missingCapabilityAndWrongThreadNeverAssumeZeroTax(){
        when(server.isPrimaryThread()).thenReturn(false);assertThrows(IllegalArgumentException.class,()->port.quote(id,nation,actor,base));verifyNoInteractions(taxes);
        when(server.isPrimaryThread()).thenReturn(true);when(api.service(TaxationService.class)).thenReturn(Optional.empty());assertThrows(IllegalArgumentException.class,()->port.quote(id,nation,actor,base));
        when(core.isEnabled()).thenReturn(false);assertThrows(IllegalArgumentException.class,()->port.quote(id,nation,actor,base));assertTrue(port.status().contains("等待"));
    }
    @Test void invalidTaxPrecisionNegativeOrExcessiveAmountsAreRejected(){
        for(BigDecimal fee:List.of(new BigDecimal("0.001"),new BigDecimal("-0.01"),new BigDecimal("26.00"),new BigDecimal("1E+100000"))){
            when(taxes.quoteTax(any(),any(),any(),any(),any())).thenReturn(proof(fee));assertThrows(IllegalArgumentException.class,()->port.quote(id,nation,actor,base));
        }
    }
}

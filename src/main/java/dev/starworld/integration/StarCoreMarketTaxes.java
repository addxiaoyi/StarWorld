package dev.starworld.integration;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Calls only the public TaxationService quote, never its separate fundTax/deposit mutations. */
public final class StarCoreMarketTaxes implements MarketTaxPort {
    private static final String BASE="dev.starcore.starcore.";
    private final JavaPlugin owner;
    public StarCoreMarketTaxes(JavaPlugin owner){this.owner=Objects.requireNonNull(owner);}
    @Override public Quote quote(UUID id,UUID nation,UUID actor,BigDecimal goods){
        goods=TreasuryTransferPort.money(goods);
        try{
            Access a=access();Class<?> nationType=a.type("module.nation.model.NationId"),taxType=a.type("module.treasury.TaxationService$TaxType");
            Object collector=nationType.getMethod("of",UUID.class).invoke(null,nation),tariff=taxType.getField("TARIFF").get(null);
            Object value=a.contract().getMethod("quoteTax",UUID.class,nationType,taxType,UUID.class,BigDecimal.class).invoke(a.service(),id,collector,tariff,actor,goods);
            Class<?> type=a.type("module.treasury.TaxationService$TaxQuote");
            if(!id.equals(type.getMethod("transactionId").invoke(value))||!actor.equals(type.getMethod("payerId").invoke(value))
                    ||!collector.equals(type.getMethod("nationId").invoke(value))||!tariff.equals(type.getMethod("type").invoke(value))
                    ||goods.compareTo((BigDecimal)type.getMethod("taxableAmount").invoke(value))!=0)
                throw new IllegalStateException("核心关税报价明细不符");
            return new Quote(id,nation,actor,goods,(BigDecimal)type.getMethod("taxAmount").invoke(value));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw new IllegalArgumentException("核心关税报价暂不可用，未预留商品或付款",failure);}
    }
    @Override public String status(){try{access();return "StarCore出口国关税报价可用；按已确认报价金额结算";}catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return "市场报价等待核心TaxationService";}}
    private record Access(ClassLoader loader,Class<?> contract,Object service){Class<?> type(String name)throws ClassNotFoundException{return Class.forName(BASE+name,true,loader);}}
    @SuppressWarnings({"rawtypes","unchecked"}) private Access access()throws ReflectiveOperationException{
        if(!owner.getServer().isPrimaryThread())throw new IllegalStateException("税费报价必须在Paper主线程捕获");
        Plugin core=owner.getServer().getPluginManager().getPlugin("StarCore");if(core==null||!core.isEnabled())throw new IllegalStateException("StarCore未启用");ClassLoader loader=core.getClass().getClassLoader();
        Class<?> apiType=Class.forName(BASE+"api.StarCoreApi",true,loader),contract=Class.forName(BASE+"module.treasury.TaxationService",true,loader);
        Object api=owner.getServer().getServicesManager().load((Class)apiType);if(api==null)throw new IllegalStateException("StarCoreApi未注册");
        Object raw=apiType.getMethod("service",Class.class).invoke(api,contract);Object service=raw instanceof Optional<?> optional?optional.orElse(null):null;if(service==null)throw new IllegalStateException("TaxationService未注册");return new Access(loader,contract,service);
    }
}

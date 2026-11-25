package dev.starworld.market;

import dev.starworld.integration.TreasuryTransferPort;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Physical city goods priced in the existing national treasury currency. */
public record MarketDefinitions(Set<String> items,int maxListingsPerCity,int listingLifetimePeriods,int dispatchBatch,long maxUnits,BigDecimal maximumUnitPrice,int maxOpenPurchasesPerCity){
    public MarketDefinitions(Set<String> items,int maxListingsPerCity,int listingLifetimePeriods,int dispatchBatch,long maxUnits,BigDecimal maximumUnitPrice){this(items,maxListingsPerCity,listingLifetimePeriods,dispatchBatch,maxUnits,maximumUnitPrice,100);}
    public MarketDefinitions{items=Set.copyOf(items);if(items.isEmpty()||items.size()>256||maxOpenPurchasesPerCity<1||maxOpenPurchasesPerCity>1000||maxListingsPerCity<1||maxListingsPerCity>1000||listingLifetimePeriods<1||listingLifetimePeriods>100000||dispatchBatch<1||dispatchBatch>50||maxUnits<1||maxUnits>1000000)throw new IllegalArgumentException("Invalid physical market limits");for(String id:items)if(!id.matches("[a-z0-9._-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Invalid market item");maximumUnitPrice=TreasuryTransferPort.money(maximumUnitPrice);}
    public static MarketDefinitions defaults(){return new MarketDefinitions(Set.of("minecraft:wheat","minecraft:bread","minecraft:oak_log","minecraft:oak_planks","minecraft:iron_ingot","minecraft:arrow","minecraft:shield","minecraft:paper","kaleidoscopecookery:baozi","kaleidoscope_tavern:wine"),20,48,10,2304,new BigDecimal("1000000.00"));}
    public static void writeDefaults(File file)throws Exception{if(file.exists())return;File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IllegalStateException("Cannot create market config directory");MarketDefinitions d=defaults();YamlConfiguration y=new YamlConfiguration();y.set("market-version",1);y.set("items",d.items().stream().sorted().toList());y.set("max-listings-per-city",d.maxListingsPerCity());y.set("listing-lifetime-periods",d.listingLifetimePeriods());y.set("dispatch-batch",d.dispatchBatch());y.set("max-units",d.maxUnits());y.set("max-open-purchases-per-city",d.maxOpenPurchasesPerCity());y.set("maximum-unit-price",d.maximumUnitPrice().toPlainString());y.save(file);}
    public static MarketDefinitions load(File file)throws Exception{YamlConfiguration y=new YamlConfiguration();y.load(file);if(y.getInt("market-version")!=1)throw new IllegalArgumentException("Unsupported market-version");return new MarketDefinitions(new HashSet<>(y.getStringList("items")),y.getInt("max-listings-per-city"),y.getInt("listing-lifetime-periods"),y.getInt("dispatch-batch"),y.getLong("max-units"),new BigDecimal(y.getString("maximum-unit-price","0")),y.getInt("max-open-purchases-per-city",100));}
}

package dev.starworld.fixture;

import com.google.gson.Gson;
import dev.starworld.api.StarWorldApi;
import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.integration.CustomItems;
import dev.starworld.life.LifePlatform;
import dev.starworld.life.LifeState.*;
import dev.starworld.runtime.WorldRuntime;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Disposable console fixture: real inventory/CE setup and public read APIs only; no civic mutations or SQL. */
final class CivicSenLuoFixture {
    private static final List<String> IDS=List.of("minecraft:wheat","minecraft:bread","minecraft:oak_planks","minecraft:paper","kaleidoscopecookery:baozi","kaleidoscopecookery:oil");
    private final JavaPlugin owner;private final WorldRuntime runtime;private final CustomItems items;private final LifePlatform platform;
    private final Gson json=new Gson();private final Map<Integer,Object> chairs=new HashMap<>();
    CivicSenLuoFixture(JavaPlugin owner,WorldRuntime runtime,CustomItems items,LifePlatform platform){this.owner=Objects.requireNonNull(owner);this.runtime=Objects.requireNonNull(runtime);this.items=Objects.requireNonNull(items);this.platform=Objects.requireNonNull(platform);}
    void execute(String[] args,Player player)throws Exception {
        switch(args[0]) {
            case "civic-seed"->seed(player,argument(args));
            case "civic-snapshot"->snapshot(player);
            case "civic-chair"->chair(player,slot(args));
            case "civic-aim"->aim(player,slot(args));
            case "civic-remove"->remove();
            case "civic-gui"->gui(player);
            default->throw new IllegalArgumentException("Unknown console civic fixture action");
        }
    }
    private static String argument(String[] args){if(args.length!=3)throw new IllegalArgumentException("A civic fixture argument is required");return args[2];}
    private static int slot(String[] args){int slot=Integer.parseInt(argument(args));if(slot<0||slot>1)throw new IllegalArgumentException("Chair slot must be 0 or 1");return slot;}
    private void seed(Player player,String bundle) {
        Map<String,Integer> quantities=switch(bundle){
            case "meals"->Map.of("minecraft:wheat",16,"minecraft:bread",4,"kaleidoscopecookery:baozi",4,"kaleidoscopecookery:oil",1);
            case "spaces"->Map.of("minecraft:oak_planks",24,"minecraft:bread",16);
            case "festival"->Map.of("minecraft:paper",8,"minecraft:bread",16);
            default->throw new IllegalArgumentException("Unknown seed bundle");
        };
        Map<String,Integer> before=inventory(player);
        for(var entry:quantities.entrySet())if(!player.getInventory().addItem(Objects.requireNonNull(items.create(entry.getKey(),entry.getValue()),entry.getKey())).isEmpty())throw new IllegalStateException("Inventory full during declared fixture seed");
        player.saveData();report("civic-seed",Map.of("bundle",bundle,"fixtureSeededPlayerInventory",quantities,"inventoryBefore",before,"inventoryAfter",inventory(player)));
    }
    private Map<String,Integer> inventory(Player player) {
        Map<String,Integer> result=new TreeMap<>();for(String id:IDS){int count=0;for(ItemStack stack:player.getInventory().getStorageContents())if(items.matches(stack,id))count+=stack.getAmount();result.put(id,count);}return result;
    }
    private void chair(Player player,int slot)throws Exception {
        if(chairs.containsKey(slot)&&Boolean.TRUE.equals(call(chairs.get(slot),"isValid")))throw new IllegalStateException("Civic chair slot is already occupied");
        int x=10+slot*3;Location at=new Location(player.getWorld(),x+.5,80,10.5);
        // Bounded disposable foundation also supports the real player's targeting position.
        for(int groundX=x-1;groundX<=x+1;groundX++)for(int groundZ=6;groundZ<=11;groundZ++)player.getWorld().getBlockAt(groundX,79,groundZ).setType(Material.STONE);
        Object chair=ceType("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture").getMethod("place",Location.class,keyType(),String.class,boolean.class).invoke(null,at,key("kaleidoscope_deco:chair/oak"),"ground",false);
        if(chair==null)throw new IllegalStateException("CE rejected civic chair placement");chairs.put(slot,chair);Entity meta=(Entity)call(chair,"getBukkitEntity");
        report("civic-chair",Map.of("slot",slot,"uuid",call(chair,"uuid"),"meta",meta.getUniqueId(),"valid",call(chair,"isValid"),"location",Map.of("world",at.getWorld().getName(),"x",x,"y",80,"z",10)));
    }
    private void aim(Player player,int slot)throws Exception {
        Object chair=Objects.requireNonNull(chairs.get(slot),"Place the real CE chair first");Entity meta=(Entity)call(chair,"getBukkitEntity");Location at=meta.getLocation(),eye=new Location(player.getWorld(),at.getX(),80,at.getZ()-3);
        eye.setDirection(at.toVector().add(new Vector(0,.4,0)).subtract(eye.toVector().add(new Vector(0,player.getEyeHeight(),0))));player.teleport(eye);
        Bukkit.getScheduler().runTaskLater(owner,()->{try{UUID city=runtime.playerCity(player).orElseThrow().cityId();report("civic-aim",Map.of("slot",slot,"furniture",call(chair,"uuid"),"metaEvidence",platform.inspectDecoration(player,city,meta).isPresent(),"ceTargetEvidence",platform.inspectTargetDecoration(player,city).isPresent()));}catch(Exception failure){failed(failure);}},4L);
    }
    private void remove()throws Exception {
        List<Map<String,Object>> removed=new ArrayList<>();for(var entry:chairs.entrySet()) {
            Object chair=entry.getValue();Entity meta=(Entity)call(chair,"getBukkitEntity");boolean changed=(boolean)ceType("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture").getMethod("remove",Entity.class,boolean.class,boolean.class).invoke(null,meta,false,false);
            removed.add(Map.of("slot",entry.getKey(),"uuid",call(chair,"uuid"),"removed",changed,"valid",call(chair,"isValid")));
        }report("civic-remove",Map.of("chairs",removed));
    }
    private void snapshot(Player player)throws Exception {
        CoreCity city=runtime.playerCity(player).orElseThrow();UUID actor=player.getUniqueId();long period=runtime.currentPeriod();Map<String,Integer> inventory=inventory(player);
        StarWorldApi api=Objects.requireNonNull(Bukkit.getServicesManager().load(StarWorldApi.class));
        CompletableFuture<Object> requests=future(api,"civicNeeds",new Class<?>[]{UUID.class,int.class,int.class},city.cityId(),8,0);
        CompletableFuture<Object> history=future(api,"civicHistory",new Class<?>[]{UUID.class,int.class,int.class},city.cityId(),32,0);
        CompletableFuture<?> life=api.life(city.cityId()),development=api.development(city.cityId()),warehouse=api.city(city.cityId());
        CompletableFuture<Map<String,Object>> receipts=requests.thenCompose(rows->{
            Map<String,CompletableFuture<Object>> reads=new LinkedHashMap<>();try{for(Object row:(List<?>)rows){UUID id=(UUID)call(row,"id");reads.put(id.toString(),future(api,"civicReceipt",new Class<?>[]{UUID.class},id));}}
            catch(Exception failure){return CompletableFuture.failedFuture(failure);}
            return CompletableFuture.allOf(reads.values().toArray(CompletableFuture[]::new)).thenApply(v->{Map<String,Object> result=new TreeMap<>();reads.forEach((id,read)->((Optional<?>)read.join()).ifPresent(receipt->result.put(id,receipt)));return result;});
        });
        CompletableFuture<Map<String,Object>> service=runtime.async(()->{
            Object extension=runtime.expansion().orElseThrow(),civic=call(extension,"civic");Map<String,Object> result=new LinkedHashMap<>();
            List<?> saved=(List<?>)civic.getClass().getMethod("requests",UUID.class,int.class,int.class).invoke(civic,city.cityId(),8,0);result.put("requests",saved);
            result.put("history",civic.getClass().getMethod("history",UUID.class,int.class,int.class).invoke(civic,city.cityId(),32,0));
            Map<String,Object> committed=new TreeMap<>();for(Object row:saved){UUID id=(UUID)call(row,"id");((Optional<?>)civic.getClass().getMethod("receipt",UUID.class).invoke(civic,id)).ifPresent(value->committed.put(id.toString(),value));}result.put("receipts",committed);
            var actual=runtime.expansion().orElseThrow().life();result.put("profession",actual.profession(actor));Map<String,Long> cultures=new TreeMap<>();for(long p=Math.max(0,period-2);p<=period;p++)cultures.put(Long.toString(p),actual.cultureInPeriod(city.cityId(),p));result.put("recentCultureByPeriod",cultures);return result;
        });
        CompletableFuture.allOf(requests,history,life,development,warehouse,receipts,service).whenComplete((nothing,error)->Bukkit.getScheduler().runTask(owner,()->{
            try {
                if(error!=null)throw new IllegalStateException("Civic public API read failed",error);
                if(!requests.join().equals(service.join().get("requests"))||!history.join().equals(service.join().get("history"))||!receipts.join().equals(service.join().get("receipts")))throw new IllegalStateException("Civic immutable API and service projections disagree");
                Object cityView=((Optional<?>)warehouse.join()).orElseThrow(),lifeView=((Optional<?>)life.join()).orElseThrow();
                Map<String,Object> result=new LinkedHashMap<>();result.put("cityId",city.cityId());result.put("nationId",city.nationId());result.put("period",period);result.put("apiVersion",api.version());result.put("requests",requests.join());result.put("history",history.join());result.put("receipts",receipts.join());result.put("life",lifeView);result.put("profile",((Optional<?>)development.join()).orElseThrow());result.put("stock",call(cityView,"stock"));result.put("inventory",inventory);result.put("profession",service.join().get("profession"));result.put("recentCultureByPeriod",service.join().get("recentCultureByPeriod"));result.put("apiServiceParity",true);
                Map<String,Boolean> validity=new TreeMap<>();for(Decoration decoration:((LifeView)lifeView).decorations())validity.put(decoration.furnitureId().toString(),platform.validateDecoration(decoration));result.put("decorationValidity",validity);report("civic-snapshot",result);
            }catch(Exception failure){failed(failure);}
        }));
    }
    private void gui(Player player) {
        InventoryView view=player.getOpenInventory();Inventory top=view.getTopInventory();List<Map<String,Object>> slots=new ArrayList<>();
        for(int slot=0;slot<top.getSize();slot++){ItemStack stack=top.getItem(slot);if(stack==null)continue;ItemMeta meta=stack.getItemMeta();List<String> lore=meta!=null&&meta.hasLore()?Objects.requireNonNull(meta.getLore()).stream().map(ChatColor::stripColor).toList():List.of();slots.add(Map.of("slot",slot,"material",stack.getType().name(),"name",meta!=null&&meta.hasDisplayName()?Objects.requireNonNull(ChatColor.stripColor(meta.getDisplayName())):"","lore",lore));}
        report("civic-gui",Map.of("title",view.getTitle(),"size",top.getSize(),"holder",top.getHolder()==null?"none":top.getHolder().getClass().getName(),"slots",slots));
    }
    @SuppressWarnings("unchecked") private static CompletableFuture<Object> future(Object target,String method,Class<?>[] types,Object...args)throws Exception{return (CompletableFuture<Object>)target.getClass().getMethod(method,types).invoke(target,args);}
    private void report(String action,Map<String,?> values){Map<String,Object> result=new LinkedHashMap<>();result.put("action",action);result.putAll(values);owner.getLogger().info("SW_SENLUO_JSON "+json.toJson(result));}
    private void failed(Throwable failure){owner.getLogger().log(java.util.logging.Level.SEVERE,"SW_SENLUO_FAILED",failure);}
    private Class<?> ceType(String name)throws Exception{return Class.forName(name,true,Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("CraftEngine")).getClass().getClassLoader());}
    private Class<?> keyType()throws Exception{return ceType("net.momirealms.craftengine.core.util.Key");}
    private Object key(String id)throws Exception{return keyType().getMethod("of",String.class).invoke(null,id);}
    private static Object call(Object target,String method)throws Exception{return target.getClass().getMethod(method).invoke(target);}
}

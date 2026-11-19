package dev.starworld.fixture;

import com.google.gson.Gson;
import dev.starworld.api.StarWorldApi;
import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.integration.*;
import dev.starworld.life.*;
import dev.starworld.life.LifeState.*;
import dev.starworld.runtime.WorldRuntime;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Disposable test helper: all state mutations use public APIs or real inventory commands. */
public final class SenLuoFixture extends JavaPlugin implements CommandExecutor {
    private static final String CORE="dev.starcore.starcore.";
    private static final List<String> CUSTOM=List.of("kaleidoscopecookery:baozi","kaleidoscopecookery:oil","kaleidoscope_tavern:wine","musicplus:handheld_instrument");
    private final Gson json=new Gson();
    private WorldRuntime runtime; private CustomItems items; private LifePlatform platform; private Object furniture;
    @Override public void onEnable() {
        runtime=(WorldRuntime)Objects.requireNonNull(Bukkit.getServicesManager().load(StarWorldApi.class));
        items=new CustomItems(this);platform=new LifePlatform(this,new StarCoreBridge(this),items,runtime.expansion().orElseThrow().life().definitions());
        Objects.requireNonNull(getCommand("swsenluo")).setExecutor(this);
        getLogger().warning("TEST ONLY: real CE item seeds, same-city membership and bounded test furniture placement.");
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("Console only; test fixture must not be deployed.");return true;}
        try {
            if(args.length<2)throw new IllegalArgumentException("swsenluo <status|seed|snapshot|audience|furniture|destroy|hold|aim|unclaim|reclaim> <player> [listener]");
            Player p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]),"Connected player required");
            switch(args[0]) {
                case "status"->status(p);
                case "seed"->seed(p);
                case "snapshot"->snapshot(p);
                case "audience"->audience(p,Objects.requireNonNull(Bukkit.getPlayerExact(args[2])));
                case "furniture"->place(p);
                case "destroy"->{if(furniture==null)throw new IllegalStateException("No test furniture");Entity meta=(Entity)call(furniture,"getBukkitEntity");boolean removed=(boolean)ceType("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture").getMethod("remove",Entity.class,boolean.class,boolean.class).invoke(null,meta,false,false);report("destroy",Map.of("removed",removed,"valid",call(furniture,"isValid")));}
                case "hold"->{ItemStack instrument=Objects.requireNonNull(items.create(CUSTOM.get(3),1));p.getInventory().setItemInMainHand(instrument);p.saveData();report("hold",Map.of("matches",items.matches(p.getInventory().getItemInMainHand(),CUSTOM.get(3))));}
                case "aim"->aim(p);
                case "unclaim"->furnitureClaim(p,false);
                case "reclaim"->furnitureClaim(p,true);
                default->throw new IllegalArgumentException("Unknown action");
            }
        }catch(Throwable e){getLogger().log(java.util.logging.Level.SEVERE,"SW_SENLUO_FAILED",e);}
        return true;
    }
    private void status(Player p)throws Exception {
        Map<String,Object> enabled=new LinkedHashMap<>();
        for(String name:List.of("CraftEngine","KaleidoscopeCookeryPlugin","KaleidoscopeTavernPlugin","KaleidoscopeDecoPlugin","KaleidoscopeMusicPlugin")) {
            Plugin plugin=Bukkit.getPluginManager().getPlugin(name);enabled.put(name,Map.of("enabled",plugin!=null&&plugin.isEnabled(),"version",plugin==null?"absent":plugin.getPluginMeta().getVersion()));
        }
        Map<String,Boolean> available=new LinkedHashMap<>();Map<String,Object> diagnostics=new LinkedHashMap<>();for(String id:CUSTOM){ItemStack built=items.create(id,1);available.put(id,built!=null&&items.matches(built,id));Map<String,Object> probe=new LinkedHashMap<>();probe.put("built",built!=null);probe.put("adapter",items.status());Object manager=ceType("net.momirealms.craftengine.bukkit.item.BukkitItemManager").getMethod("instance").invoke(null);Optional<?> definition=(Optional<?>)manager.getClass().getMethod("getBuildableItem",keyType()).invoke(manager,key(id));probe.put("definition",definition.isPresent());if(built!=null){probe.put("material",built.getType().name());Object identity=ceType("net.momirealms.craftengine.bukkit.api.CraftEngineItems").getMethod("getCustomItemId",ItemStack.class).invoke(null,built);probe.put("identity",String.valueOf(identity));}diagnostics.put(id,probe);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("plugins",enabled);result.put("items",available);result.put("integration",runtime.integrationStatus());
        result.put("itemDiagnostics",diagnostics);result.put("furnitureLoaded",ceType("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture").getMethod("byId",keyType()).invoke(null,key("kaleidoscope_deco:chair/oak"))!=null);
        report("status",result);
    }
    private void seed(Player p) {
        Map<String,Integer> amounts=new LinkedHashMap<>();amounts.put(CUSTOM.get(0),4);amounts.put(CUSTOM.get(1),1);amounts.put(CUSTOM.get(2),1);amounts.put("minecraft:bread",64);amounts.put("minecraft:oak_planks",8);
        for(var entry:amounts.entrySet()){ItemStack item=Objects.requireNonNull(items.create(entry.getKey(),entry.getValue()),entry.getKey());if(!p.getInventory().addItem(item).isEmpty())throw new IllegalStateException("Inventory full");}
        p.saveData();report("seed",Map.of("fixtureSeededPlayerInventory",amounts,"inventory",inventory(p)));
    }
    private Map<String,Integer> inventory(Player p) {
        Map<String,Integer> content=new LinkedHashMap<>();List<String> ids=new ArrayList<>(CUSTOM);ids.addAll(List.of("minecraft:bread","minecraft:oak_planks"));
        for(String id:ids){int total=0;for(ItemStack stack:p.getInventory().getStorageContents())if(items.matches(stack,id))total+=stack.getAmount();content.put(id,total);}return content;
    }
    private void snapshot(Player p) {
        CoreCity city=runtime.playerCity(p).orElseThrow();Map<String,Integer> inventory=inventory(p);long period=runtime.currentPeriod();
        runtime.async(()->{
            LifeService life=runtime.expansion().orElseThrow().life();LifeView view=life.view(city.cityId(),period);Map<String,Object> result=new LinkedHashMap<>();
            result.put("cityId",city.cityId());result.put("nationId",city.nationId());result.put("period",period);result.put("life",view);result.put("profession",life.profession(p.getUniqueId()));result.put("inventory",inventory);return result;
        }).thenCombine(runtime.city(city.cityId()),(result,view)->{result.put("stock",view.orElseThrow().stock());return result;}).whenComplete((result,error)->Bukkit.getScheduler().runTask(this,()->{
            if(error!=null){getLogger().log(java.util.logging.Level.SEVERE,"SW_SENLUO_FAILED",error);return;}
            LifeView life=(LifeView)result.get("life");Map<String,Boolean> valid=new LinkedHashMap<>();for(Decoration d:life.decorations())valid.put(d.furnitureId().toString(),platform.validateDecoration(d));result.put("decorationValidity",valid);report("snapshot",result);
        }));
    }
    private void audience(Player leader,Player listener)throws Exception {
        Object api=service("StarCore",CORE+"api.StarCoreApi");Class<?> apiType=type("StarCore",CORE+"api.StarCoreApi");
        Object nations=((Optional<?>)apiType.getMethod("nationService").invoke(api)).orElseThrow();
        Object nation=((Optional<?>)nations.getClass().getMethod("nationOf",UUID.class).invoke(nations,leader.getUniqueId())).orElseThrow();Object nationId=call(nation,"id");
        if(!Boolean.TRUE.equals(nations.getClass().getMethod("addMember",nationId.getClass(),UUID.class,String.class).invoke(nations,nationId,listener.getUniqueId(),listener.getName())))throw new IllegalStateException("addMember rejected");
        Class<?> serviceType=type("StarCore",CORE+"module.city.CityService");Object cities=((Optional<?>)apiType.getMethod("service",Class.class).invoke(api,serviceType)).orElseThrow();
        CoreCity city=runtime.playerCity(leader).orElseThrow();Class<?> rank=type("StarCore",CORE+"module.city.model.CityRank");
        @SuppressWarnings({"unchecked","rawtypes"}) Object resident=Enum.valueOf((Class)rank,"RESIDENT");
        if(!Boolean.TRUE.equals(serviceType.getMethod("addResident",UUID.class,UUID.class,rank).invoke(cities,city.cityId(),listener.getUniqueId(),resident)))throw new IllegalStateException("addResident rejected");
        Location base=new Location(leader.getWorld(),8.5,80,5.5);leader.teleport(base);listener.teleport(base.clone().add(1,0,0));report("audience",Map.of("sameCity",runtime.playerCity(listener).filter(c->c.cityId().equals(city.cityId())).isPresent()));
    }
    private void place(Player p)throws Exception {
        Location at=new Location(p.getWorld(),10.5,80,8.5);p.getWorld().getBlockAt(10,79,8).setType(Material.STONE);
        furniture=ceType("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture").getMethod("place",Location.class,keyType(),String.class,boolean.class).invoke(null,at,key("kaleidoscope_deco:chair/oak"),"ground",false);
        if(furniture==null)throw new IllegalStateException("CE rejected furniture place");Entity meta=(Entity)call(furniture,"getBukkitEntity");
        report("furniture",Map.of("uuid",call(furniture,"uuid"),"id",String.valueOf(call(furniture,"id")),"meta",meta.getUniqueId(),"valid",call(furniture,"isValid")));
    }
    private void furnitureClaim(Player p,boolean restore)throws Exception {
        Entity meta=(Entity)call(Objects.requireNonNull(furniture),"getBukkitEntity");Location at=meta.getLocation();
        Object api=service("StarCore",CORE+"api.StarCoreApi");Class<?> apiType=type("StarCore",CORE+"api.StarCoreApi");
        Object nations=((Optional<?>)apiType.getMethod("nationService").invoke(api)).orElseThrow();Class<?> contract=type("StarCore",CORE+"module.nation.NationService");
        String world=at.getWorld().getName();int x=at.getBlockX()>>4,z=at.getBlockZ()>>4;
        boolean changed=(boolean)contract.getMethod(restore?"claimCurrentChunk":"unclaimCurrentChunk",UUID.class,String.class,int.class,int.class).invoke(nations,p.getUniqueId(),world,x,z);
        Optional<?> claim=(Optional<?>)contract.getMethod("claimAt",String.class,int.class,int.class).invoke(nations,world,x,z);
        if(!changed || claim.isPresent()!=restore)throw new IllegalStateException("Furniture territory change rejected");
        UUID city=runtime.playerCity(p).orElseThrow().cityId();
        report(restore?"reclaim":"unclaim",Map.of("changed",changed,"claimed",claim.isPresent(),"world",world,"chunkX",x,"chunkZ",z,"furnitureStillValid",call(furniture,"isValid"),"decorationEvidence",platform.inspectDecoration(p,city,meta).isPresent()));
    }
    private void aim(Player p)throws Exception {
        Entity meta=(Entity)call(Objects.requireNonNull(furniture),"getBukkitEntity");Location at=meta.getLocation();Location eye=new Location(p.getWorld(),at.getX(),80,at.getZ()-3);
        eye.setDirection(at.toVector().add(new Vector(0,.4,0)).subtract(eye.toVector().add(new Vector(0,p.getEyeHeight(),0))));p.teleport(eye);
        Bukkit.getScheduler().runTaskLater(this,()->{Entity target=p.getTargetEntity(6);UUID city=runtime.playerCity(p).orElseThrow().cityId();Map<String,Object> result=new LinkedHashMap<>();result.put("targetType",target==null?"none":target.getType().name());result.put("targetId",target==null?"none":target.getUniqueId().toString());result.put("metaId",meta.getUniqueId().toString());result.put("metaEvidence",platform.inspectDecoration(p,city,meta).isPresent());result.put("ceTargetEvidence",platform.inspectTargetDecoration(p,city).isPresent());report("aim",result);},4L);
    }
    private void report(String action,Map<String,?> values){Map<String,Object> result=new LinkedHashMap<>();result.put("action",action);result.putAll(values);getLogger().info("SW_SENLUO_JSON "+json.toJson(result));}
    private Class<?> ceType(String name)throws Exception{return type("CraftEngine",name);}
    private Class<?> keyType()throws Exception{return ceType("net.momirealms.craftengine.core.util.Key");}
    private Object key(String id)throws Exception{return keyType().getMethod("of",String.class).invoke(null,id);}
    private static Object call(Object target,String method)throws Exception{return target.getClass().getMethod(method).invoke(target);}
    private static Class<?> type(String plugin,String name)throws Exception{return Class.forName(name,true,Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(plugin)).getClass().getClassLoader());}
    private static Object service(String plugin,String name)throws Exception{return Objects.requireNonNull(Bukkit.getServicesManager().load(type(plugin,name)));}
}

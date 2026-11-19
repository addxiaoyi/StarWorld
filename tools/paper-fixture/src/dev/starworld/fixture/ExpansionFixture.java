package dev.starworld.fixture;

import com.google.gson.Gson;
import java.lang.reflect.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

/** Console-only expansion acceptance. No private-field access, JDBC, or fake core services. */
final class ExpansionFixture {
    private static final String CORE="dev.starcore.starcore.";
    private final JavaPlugin owner;
    private final Gson json=new Gson();
    private final Set<UUID> registering=new HashSet<>();
    ExpansionFixture(JavaPlugin owner){this.owner=owner;}
    boolean execute(String[] args)throws Exception {
        if(args.length<2)throw new IllegalArgumentException("swfixture exp-<setup|register|node|seed|snapshot|history|delete-city|unclaim|reclaim|war|peace|receipt|replay> <player> [slot|peer|transaction|city]");
        String action=args[0];Player p=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]),"Connected player required");
        switch(action){
            case "exp-setup"->setup(p,slot(args));
            case "exp-register"->register(p,slot(args),false);
            case "exp-node"->register(p,slot(args),true);
            case "exp-seed"->seed(p);
            case "exp-snapshot"->snapshot(p);
            case "exp-delete-city"->deleteCity(p);
            case "exp-history"->history(p,UUID.fromString(args[2]));
            case "exp-unclaim","exp-reclaim"->claimChange(p,slot(args),action.equals("exp-reclaim"));
            case "exp-war","exp-peace"->war(p,Objects.requireNonNull(Bukkit.getPlayerExact(args[2]),"Connected peer required"),action.equals("exp-war"));
            case "exp-receipt","exp-replay"->receipt(p,args[2],action.equals("exp-replay"));
            case "exp-armies"->armies(p,Objects.requireNonNull(Bukkit.getPlayerExact(args[2]),"Connected peer required"),Integer.parseInt(args[3]),Integer.parseInt(args[4]));
            case "exp-combat"->combat(p,Objects.requireNonNull(Bukkit.getPlayerExact(args[2]),"Connected peer required"));
            default->throw new IllegalArgumentException("Unknown expansion fixture action");
        }return true;
    }
    private static int slot(String[] args){if(args.length!=3)throw new IllegalArgumentException("A slot 0 or 1 is required");int n=Integer.parseInt(args[2]);if(n<0||n>1)throw new IllegalArgumentException("Slot must be 0 or 1");return n;}
    private static int base(int slot){return 20+slot*32;}
    private void setup(Player p,int slot)throws Exception {
        Object nations=coreService("module.nation.NationService"),cities=coreService("module.city.CityService");
        Object nation=unwrap(invoke(nations,"nationOf",new Class<?>[]{UUID.class},p.getUniqueId()));
        if(nation==null)nation=invoke(nations,"createNation",new Class<?>[]{UUID.class,String.class,String.class},p.getUniqueId(),p.getName(),"SWExp"+p.getName());
        Object nationId=call(nation,"id");int start=base(slot);
        Object city=unwrap(invoke(cities,"getPlayerCity",new Class<?>[]{UUID.class},p.getUniqueId()));
        if(city==null){Object created=invoke(cities,"createCity",new Class<?>[]{nationId.getClass(),String.class,UUID.class,Location.class},nationId,"Exp"+p.getName(),p.getUniqueId(),new Location(p.getWorld(),start*16+8,80,8));if(!Boolean.TRUE.equals(call(created,"success")))throw new IllegalStateException(String.valueOf(call(created,"message")));city=call(created,"city");}
        UUID cityId=(UUID)call(city,"id");
        for(int chunk=start;chunk<start+5;chunk++){
            claim(nations,p,nationId,chunk);
            invoke(cities,"addClaim",new Class<?>[]{UUID.class,String.class,int.class,int.class},cityId,p.getWorld().getName(),chunk,0);
            if(!Boolean.TRUE.equals(invoke(city,"hasClaim",new Class<?>[]{String.class,int.class,int.class},p.getWorld().getName(),chunk,0)))throw new IllegalStateException("Actual city claim missing");
            platform(p.getWorld(),chunk*16+8);
        }
        p.teleport(new Location(p.getWorld(),start*16+8.5,80,5.5));
        emit("exp-setup",p,Map.of("slot",slot,"cityId",cityId,"nationId",call(nationId,"value"),"chunks",List.of(start,start+1,start+2,start+3,start+4),"anchorBiome","plains"));
    }
    private void claim(Object nations,Player p,Object nationId,int chunk)throws Exception {
        Object old=unwrap(invoke(nations,"claimAt",new Class<?>[]{String.class,int.class,int.class},p.getWorld().getName(),chunk,0));
        if(old==null)invoke(nations,"claimCurrentChunk",new Class<?>[]{UUID.class,String.class,int.class,int.class},p.getUniqueId(),p.getWorld().getName(),chunk,0);
        Object claim=unwrap(invoke(nations,"claimAt",new Class<?>[]{String.class,int.class,int.class},p.getWorld().getName(),chunk,0));
        if(claim==null||!nationId.toString().equals(call(claim,"ownerId")))throw new IllegalStateException("National claim not owned by expected nation");
    }
    private void claimChange(Player p,int slot,boolean restore)throws Exception {
        Object nations=coreService("module.nation.NationService"),nation=nation(p);Object id=call(nation,"id");int chunk=base(slot)+4;
        if(restore)claim(nations,p,id,chunk);else invoke(nations,"unclaimCurrentChunk",new Class<?>[]{UUID.class,String.class,int.class,int.class},p.getUniqueId(),p.getWorld().getName(),chunk,0);
        Object current=unwrap(invoke(nations,"claimAt",new Class<?>[]{String.class,int.class,int.class},p.getWorld().getName(),chunk,0));
        if(!restore&&current!=null)throw new IllegalStateException("Actual national claim still exists");
        emit(restore?"exp-reclaim":"exp-unclaim",p,Map.of("chunk",chunk,"nationalClaimPresent",current!=null,"cityClaimPreserved",true));
    }
    private void register(Player p,int slot,boolean node){
        if(!registering.add(p.getUniqueId()))throw new IllegalStateException("Fixture registration already running");
        String[] commands=node?new String[]{"sw node register road"}:new String[]{"sw register warehouse","sw register academy","sw register barracks","sw register farm"};
        Material[] anchors=node?new Material[]{Material.LODESTONE}:new Material[]{Material.BARREL,Material.LECTERN,Material.FLETCHING_TABLE,Material.COMPOSTER};
        registerStep(p,slot,node,commands,anchors,0);
    }
    private void registerStep(Player p,int slot,boolean node,String[] commands,Material[] anchors,int index){
        if(index==commands.length){registering.remove(p.getUniqueId());emit(node?"exp-node":"exp-register",p,Map.of("dispatched",commands.length,"slot",slot));return;}
        try{
            int x=(base(slot)+(node?4:index))*16+8;p.getWorld().getBlockAt(x,80,8).setType(anchors[index]);
            Location start=new Location(p.getWorld(),x+.5,80,5.5);start.setDirection(new Vector(x+.5,80.5,8.5).subtract(start.toVector().add(new Vector(0,p.getEyeHeight(),0))));
            if(!p.teleport(start))throw new IllegalStateException("Registration teleport cancelled");
            Bukkit.getScheduler().runTaskLater(owner,()->{
                try{if(!p.isOnline()||p.getTargetBlockExact(6)==null||p.getTargetBlockExact(6).getType()!=anchors[index])throw new IllegalStateException("Actual raycast misses fixture anchor");
                    if(!p.performCommand(commands[index]))throw new IllegalStateException("Actual command not handled");
                    Bukkit.getScheduler().runTaskLater(owner,()->registerStep(p,slot,node,commands,anchors,index+1),3);
                }catch(Exception failure){registering.remove(p.getUniqueId());failed(failure);}
            },3);
        }catch(Exception failure){registering.remove(p.getUniqueId());failed(failure);}
    }
    private void seed(Player p){
        Map<Material,Integer> quantities=new LinkedHashMap<>();quantities.put(Material.BREAD,128);quantities.put(Material.OAK_PLANKS,128);quantities.put(Material.IRON_INGOT,64);quantities.put(Material.OAK_LOG,32);quantities.put(Material.PAPER,64);quantities.put(Material.ARROW,256);quantities.put(Material.WHEAT,64);
        Map<String,Integer> provided=new LinkedHashMap<>();for(var entry:quantities.entrySet()){for(int remaining=entry.getValue();remaining>0;){int batch=Math.min(64,remaining);if(!p.getInventory().addItem(new ItemStack(entry.getKey(),batch)).isEmpty())throw new IllegalStateException("Seed inventory overflow");remaining-=batch;}provided.put(entry.getKey().getKey().toString(),entry.getValue());}p.saveData();
        emit("exp-seed",p,Map.of("source","explicit-fixture-player-inventory","items",provided,"productionEvidence",false));
    }
    private void snapshot(Player p)throws Exception {
        Object runtime=world(),expansion=unwrap(call(runtime,"expansion"));Object coreCity=unwrap(invoke(runtime,"playerCity",new Class<?>[]{Player.class},p));UUID cityId=(UUID)call(coreCity,"cityId"),actorId=p.getUniqueId();Object nationId=call(nation(p),"id");
        Object nationSnapshot=unwrap(invoke(expansion,"nation",new Class<?>[]{UUID.class},call(nationId,"value")));
        @SuppressWarnings("unchecked") CompletableFuture<Optional<?>> future=(CompletableFuture<Optional<?>>)invoke(runtime,"city",new Class<?>[]{UUID.class},cityId);
        CompletableFuture<?> nationalFuture=(CompletableFuture<?>)invoke(runtime,"nationDevelopment",new Class<?>[]{UUID.class},call(nationId,"value"));
        CompletableFuture.allOf(future,nationalFuture).whenComplete((ignored,error)->Bukkit.getScheduler().runTask(owner,()->{
            try{if(error!=null)throw new IllegalStateException(error);Object view=future.join().orElseThrow(),nationalDevelopment=unwrap(nationalFuture.join());Object valid=invoke(runtime,"verifiedBuildings",new Class<?>[]{view.getClass()},view);long period=((Number)call(runtime,"currentPeriod")).longValue();
                async(runtime,()->{
                    Object evolution=call(expansion,"evolution"),life=call(expansion,"life"),logistics=call(expansion,"logistics");
                    Map<String,Object> report=new LinkedHashMap<>();report.put("city",view);report.put("development",invoke(evolution,"profile",new Class<?>[]{UUID.class},cityId));
                    Object definitions=call(runtime,"definitions");report.put("definitionCounts",Map.of("terrains",((Map<?,?>)call(definitions,"terrains")).size(),"buildings",((Map<?,?>)call(definitions,"buildings")).size(),"civilizations",((Map<?,?>)call(definitions,"civilizations")).size(),"events",((Map<?,?>)call(definitions,"events")).size()));
                    report.put("nationalDevelopment",nationalDevelopment);report.put("nationalMultipliers",invoke(call(expansion,"national"),"multipliers",new Class<?>[]{UUID.class},call(coreCity,"nationId")));
                    report.put("capacity",invoke(evolution,"capacity",new Class<?>[]{view.getClass(),Set.class},view,valid));report.put("culture",invoke(evolution,"cultures",new Class<?>[]{UUID.class},cityId));
                    report.put("readiness",unwrap(invoke(evolution,"readiness",new Class<?>[]{UUID.class},cityId)));
                    report.put("life",invoke(life,"view",new Class<?>[]{UUID.class,long.class},cityId,period));report.put("profession",invoke(life,"profession",new Class<?>[]{UUID.class},actorId));
                    report.put("nodes",invoke(logistics,"cityNodes",new Class<?>[]{UUID.class},cityId));report.put("routes",invoke(logistics,"cityRoutes",new Class<?>[]{UUID.class,int.class,int.class},cityId,100,0));
                    report.put("orders",invoke(logistics,"cityOrders",new Class<?>[]{UUID.class,int.class,int.class},cityId,100,0));report.put("nation",nationSnapshot);report.put("currentPeriod",period);report.put("verifiedBuildings",valid);return report;
                }).whenComplete((report,failure)->Bukkit.getScheduler().runTask(owner,()->{if(failure!=null)failed(failure);else emit("exp-snapshot",p,report);}));
            }catch(Exception failure){failed(failure);}
        }));
    }
    private void deleteCity(Player p)throws Exception {
        Object cities=coreService("module.city.CityService"),city=Objects.requireNonNull(unwrap(invoke(cities,"getPlayerCity",new Class<?>[]{UUID.class},p.getUniqueId())),"Actual player city missing");
        UUID cityId=(UUID)call(city,"id"),nationId=(UUID)call(call(city,"nationId"),"value");long period=((Number)call(world(),"currentPeriod")).longValue();
        boolean deleted=Boolean.TRUE.equals(invoke(cities,"deleteCity",new Class<?>[]{UUID.class},cityId));
        boolean cityPresent=unwrap(invoke(cities,"getCity",new Class<?>[]{UUID.class},cityId))!=null,playerPresent=unwrap(invoke(cities,"getPlayerCity",new Class<?>[]{UUID.class},p.getUniqueId()))!=null;
        if(!deleted||cityPresent||playerPresent)throw new IllegalStateException("Public core CityService.deleteCity did not delete the actual city and membership");
        emit("exp-delete-city",p,Map.of("cityId",cityId,"nationId",nationId,"deleted",true,"coreCityPresent",false,"playerCityPresent",false,"deletedAtPeriod",period,"entryPoint","CityService.deleteCity"));
    }
    /** Public API still exposes historical city goods after authoritative StarCore deletion. */
    private void history(Player p,UUID cityId)throws Exception {
        Object runtime=world(),expansion=unwrap(call(runtime,"expansion"));UUID nationId=(UUID)call(call(nation(p),"id"),"value");
        boolean coreCityPresent=unwrap(invoke(coreService("module.city.CityService"),"getCity",new Class<?>[]{UUID.class},cityId))!=null;
        long period=((Number)call(runtime,"currentPeriod")).longValue();
        CompletableFuture<?> cityFuture=(CompletableFuture<?>)invoke(runtime,"city",new Class<?>[]{UUID.class},cityId),nationalFuture=(CompletableFuture<?>)invoke(runtime,"nationDevelopment",new Class<?>[]{UUID.class},nationId);
        CompletableFuture.allOf(cityFuture,nationalFuture).whenComplete((ignored,error)->{
            try{if(error!=null)throw new IllegalStateException(error);Object historicalCity=unwrap(cityFuture.join()),nationalDevelopment=unwrap(nationalFuture.join());
                async(runtime,()->{
                    Map<String,Object> report=new LinkedHashMap<>();report.put("city",historicalCity);report.put("nationalDevelopment",nationalDevelopment);
                    report.put("development",invoke(call(expansion,"evolution"),"profile",new Class<?>[]{UUID.class},cityId));
                    report.put("nationalMultipliers",invoke(call(expansion,"national"),"multipliers",new Class<?>[]{UUID.class},nationId));
                    report.put("coreCityPresent",coreCityPresent);report.put("currentPeriod",period);return report;
                }).whenComplete((report,failure)->Bukkit.getScheduler().runTask(owner,()->{if(failure!=null)failed(failure);else emit("exp-history",p,report);}));
            }catch(Exception failure){Bukkit.getScheduler().runTask(owner,()->failed(failure));}
        });
    }
    private void war(Player p,Player peer,boolean begin)throws Exception {
        Object a=call(nation(p),"id"),b=call(nation(peer),"id"),wars=coreService("module.war.WarService");Class<?> id=a.getClass();
        boolean success=(boolean)invoke(wars,begin?"declareWar":"endWar",new Class<?>[]{id,id,id},a,a,b);
        boolean atWar=(boolean)invoke(wars,"atWar",new Class<?>[]{id,id},a,b);
        if(!success||atWar!=begin)throw new IllegalStateException("Actual core "+(begin?"war declaration":"peace")+" rejected; isolated new-nation protection must be disabled and declaration cost funded/zero");
        emit(begin?"exp-war":"exp-peace",p,Map.of("peer",peer.getName(),"atWar",atWar,"nationA",call(a,"value"),"nationB",call(b,"value")));
    }
    private void receipt(Player p,String transaction,boolean replay)throws Exception {
        Object transactions=coreService("module.resource.NationResourceTransactionService"),resources=coreService("module.resource.ResourceService");
        Object value=unwrap(invoke(transactions,"transactionReceipt",new Class<?>[]{String.class},transaction));
        if(value==null){emit(replay?"exp-replay":"exp-receipt",p,Map.of("transactionId",transaction,"present",false));return;}
        Object id=call(value,"nationId");Map<String,Object> report=new LinkedHashMap<>();report.put("present",true);report.put("receipt",value);
        report.put("stockBefore",invoke(resources,"stockpile",new Class<?>[]{id.getClass()},id));
        if(replay){Class<?> request=type("StarWorld","dev.starworld.integration.CoreExtensionPort$ResourceRequest");
            Object payload=request.getConstructor(String.class,UUID.class,Map.class,String.class).newInstance(transaction,call(id,"value"),call(value,"resources"),call(value,"description"));
            Object adapter=type("StarWorld","dev.starworld.integration.StarCoreExtensions").getConstructor(JavaPlugin.class).newInstance(Bukkit.getPluginManager().getPlugin("StarWorld"));
            report.put("delivery",invoke(adapter,"deliver",new Class<?>[]{request},payload));}
        report.put("stockAfter",invoke(resources,"stockpile",new Class<?>[]{id.getClass()},id));emit(replay?"exp-replay":"exp-receipt",p,report);
    }
    private void armies(Player p,Player peer,int firstSlot,int secondSlot)throws Exception {
        if(firstSlot<0||firstSlot>1||secondSlot<0||secondSlot>1||firstSlot==secondSlot)throw new IllegalArgumentException("Distinct slots 0 and 1 required");
        List<Map<String,Object>> created=new ArrayList<>();int[] slots={firstSlot,secondSlot};Player[] players={p,peer};
        Object service=coreService("module.army.ArmyService"),treasury=coreService("module.treasury.TreasuryService");Class<?> armyType=type("StarCore",CORE+"module.army.model.ArmyType");
        Object infantry=Arrays.stream(armyType.getEnumConstants()).filter(value->((Enum<?>)value).name().equals("INFANTRY")).findFirst().orElseThrow();
        for(int index=0;index<players.length;index++){
            Player player=players[index];Object id=call(nation(player),"id");UUID uuid=(UUID)call(id,"value");
            Object before=invoke(treasury,"balance",new Class<?>[]{id.getClass()},id);
            invoke(treasury,"deposit",new Class<?>[]{id.getClass(),java.math.BigDecimal.class},id,new java.math.BigDecimal("10000"));
            Object army=invoke(service,"createArmy",new Class<?>[]{UUID.class,armyType,int.class,Location.class},uuid,infantry,10,new Location(player.getWorld(),(base(slots[index])+2)*16+8.5,80,8.5));
            created.add(Map.of("player",player.getName(),"nation",uuid,"armyId",call(army,"id"),"soldiers",call(army,"soldiers"),"treasurySeed",10000,"treasuryBefore",before,"treasuryAfter",invoke(treasury,"balance",new Class<?>[]{id.getClass()},id),"locationChunk",base(slots[index])+2));
        }emit("exp-armies",p,Map.of("source","explicit-public-treasury-fixture-seed","armies",created));
    }
    private Object firstArmy(Player p)throws Exception {
        UUID id=(UUID)call(call(nation(p),"id"),"value");Collection<?> armies=(Collection<?>)invoke(coreService("module.army.ArmyService"),"getNationArmies",new Class<?>[]{UUID.class},id);
        return armies.stream().findFirst().orElseThrow(()->new IllegalStateException("A real persisted army is required"));
    }
    private void combat(Player p,Player peer)throws Exception {
        Object attacker=firstArmy(p),defender=firstArmy(peer);Class<?> registryType=type("StarCore",CORE+"core.extension.ExtensionRegistry");Object registry=registryType.getMethod("getInstance").invoke(null);
        Class<?> context=type("StarCore",CORE+"core.extension.army.ArmyCombatExtensionPoint$CombatContext");
        Object request=context.getConstructor(String.class,String.class,String.class,String.class,double.class,int.class,int.class).newInstance(call(attacker,"id").toString(),call(defender,"id").toString(),((Enum<?>)call(attacker,"type")).name(),((Enum<?>)call(defender,"type")).name(),100.0,call(attacker,"soldiers"),call(defender,"soldiers"));
        Object result=invoke(registry,"execute",new Class<?>[]{String.class,Object.class},"army_combat",request);
        if(result==null)throw new IllegalStateException("Real army_combat extension point is missing");
        List<String> contributors=new ArrayList<>();for(Object value:(Collection<?>)invoke(registry,"getContributors",new Class<?>[]{String.class},"army_combat"))contributors.add(String.valueOf(call(value,"getId")));
        emit("exp-combat",p,Map.of("attackerId",call(attacker,"id"),"defenderId",call(defender,"id"),"request",request,"result",result,"contributors",contributors,"entryPoint","ExtensionRegistry.execute"));
    }
    @FunctionalInterface private interface Read {Object run()throws Exception;}
    @SuppressWarnings("unchecked") private CompletableFuture<Object> async(Object runtime,Read work)throws Exception {
        Class<?> contract=type("StarWorld","dev.starworld.runtime.WorldRuntime$Work");
        Object proxy=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(ignored,method,args)->{if(method.getName().equals("run"))return work.run();if(method.getName().equals("toString"))return "Expansion acceptance read";throw new UnsupportedOperationException(method.getName());});
        return (CompletableFuture<Object>)invoke(runtime,"async",new Class<?>[]{contract},proxy);
    }
    private Object nation(Player p)throws Exception{return Objects.requireNonNull(unwrap(invoke(coreService("module.nation.NationService"),"nationOf",new Class<?>[]{UUID.class},p.getUniqueId())),"Actual nation missing");}
    private Object coreService(String name)throws Exception {Object api=service("StarCore",CORE+"api.StarCoreApi");return Objects.requireNonNull(unwrap(type("StarCore",CORE+"api.StarCoreApi").getMethod("service",Class.class).invoke(api,type("StarCore",CORE+name))),name);}
    private Object world()throws Exception{return service("StarWorld","dev.starworld.api.StarWorldApi");}
    private void emit(String action,Player p,Object values){try{Map<String,Object> record=new LinkedHashMap<>();record.put("schema",2);record.put("action",action);record.put("player",p.getName());record.put("observed",tree(values));owner.getLogger().info("SW_FIXTURE_EXPANSION "+json.toJson(record));}catch(Exception e){failed(e);}}
    private static Object tree(Object value)throws Exception {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof UUID||value instanceof TemporalAccessor||value instanceof Enum<?>)return value.toString();
        if(value instanceof Optional<?> optional)return tree(optional.orElse(null));
        if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();for(var e:map.entrySet())result.put(String.valueOf(e.getKey()),tree(e.getValue()));return result;}
        if(value instanceof Collection<?> values){List<Object> result=new ArrayList<>();for(Object item:values)result.add(tree(item));return result;}
        if(value.getClass().isRecord()){Map<String,Object> result=new LinkedHashMap<>();for(RecordComponent component:value.getClass().getRecordComponents())result.put(component.getName(),tree(component.getAccessor().invoke(value)));return result;}
        return value.toString();
    }
    private void failed(Throwable failure){owner.getLogger().log(java.util.logging.Level.SEVERE,"SW_FIXTURE_FAILED expansion",failure);}
    private static void platform(World world,int x){for(int dx=-2;dx<=2;dx++)for(int dz=-4;dz<=2;dz++){world.getBlockAt(x+dx,79,8+dz).setType(Material.STONE);for(int y=80;y<=83;y++)world.getBlockAt(x+dx,y,8+dz).setType(Material.AIR);}world.setBiome(x,80,8,org.bukkit.block.Biome.PLAINS);world.getChunkAt(x>>4,0).setForceLoaded(true);}
    private static Object call(Object target,String method)throws Exception{return invoke(target,method,new Class<?>[0]);}
    private static Object invoke(Object target,String method,Class<?>[] types,Object... args)throws Exception{return target.getClass().getMethod(method,types).invoke(target,args);}
    private static Object unwrap(Object value){return ((Optional<?>)value).orElse(null);}
    private static Class<?> type(String plugin,String name)throws Exception {Plugin p=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(plugin),plugin);return Class.forName(name,true,p.getClass().getClassLoader());}
    private static Object service(String plugin,String name)throws Exception{return Objects.requireNonNull(Bukkit.getServicesManager().load(type(plugin,name)),name);}
}

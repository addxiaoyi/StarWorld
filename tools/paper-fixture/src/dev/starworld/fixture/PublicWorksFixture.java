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

/** Console-only, disposable-world works acceptance. No JDBC, private reflection, or gameplay-state injection. */
public final class PublicWorksFixture {
    private static final String CORE="dev.starcore.starcore.";
    private static final String[] KINDS={"farm","well","warehouse","academy","workshop","barracks","kitchen","market","sawmill"};
    private static final Material[] ANCHORS={Material.COMPOSTER,Material.CAULDRON,Material.BARREL,Material.LECTERN,Material.BLAST_FURNACE,Material.FLETCHING_TABLE,Material.SMOKER,Material.BELL,Material.STONECUTTER};
    private static final int[][] OFFSETS={{3,3},{11,3},{3,11},{11,11},{19,3},{27,3},{19,11},{27,11},{23,7}};
    private final JavaPlugin owner;
    private final Gson json=new Gson();
    private final Set<UUID> registering=new HashSet<>();
    public PublicWorksFixture(JavaPlugin owner){this.owner=Objects.requireNonNull(owner);}

    public boolean execute(String[] args)throws Exception{
        if(args.length<2)throw new IllegalArgumentException("swfixture works-<setup|register|seed|snapshot|inventory|unclaim|reclaim|break|repair> <connected-player> [slot] [facility]");
        Player player=Objects.requireNonNull(Bukkit.getPlayerExact(args[1]),"A real connected works player is required");
        switch(args[0]){
            case "works-setup"->setup(player,slot(args));
            case "works-register"->{int slot=slot(args);if(!registering.add(player.getUniqueId()))throw new IllegalStateException("Works registration is already running");register(player,slot,0);}
            case "works-seed"->seed(player);
            case "works-resupply"->{if(args.length!=4)throw new IllegalArgumentException("Expected literal item ID and amount");resupply(player,args[2],Integer.parseInt(args[3]));}
            case "works-node"->node(player,slot(args));
            case "works-war","works-peace"->{if(args.length!=3)throw new IllegalArgumentException("Expected connected peer");war(player,Objects.requireNonNull(Bukkit.getPlayerExact(args[2]),"Connected peer required"),args[0].equals("works-war"));}
            case "works-famine"->famine(player);
            case "works-snapshot"->snapshot(player);
            case "works-inventory"->inventory(player);
            case "works-unclaim","works-reclaim"->claimChange(player,slot(args),args[0].equals("works-reclaim"));
            case "works-break","works-repair"->{if(args.length!=4)throw new IllegalArgumentException("Expected slot and facility");block(player,slot(args),args[3],args[0].equals("works-repair"));}
            default->throw new IllegalArgumentException("Unknown works fixture action");
        }
        return true;
    }
    private static int slot(String[] args){if(args.length<3)throw new IllegalArgumentException("Works slot 0..2 required");int slot=Integer.parseInt(args[2]);if(slot<0||slot>2)throw new IllegalArgumentException("Works slot must be 0..2");return slot;}
    private static int base(int slot){return 180+slot*32;}
    private static int index(String kind){for(int index=0;index<KINDS.length;index++)if(KINDS[index].equals(kind))return index;throw new IllegalArgumentException("Unknown works fixture facility");}

    private void setup(Player player,int slot)throws Exception{
        Object nations=coreService("module.nation.NationService"),cities=coreService("module.city.CityService");
        Object nation=unwrap(invoke(nations,"nationOf",new Class<?>[]{UUID.class},player.getUniqueId()));
        if(nation==null)nation=invoke(nations,"createNation",new Class<?>[]{UUID.class,String.class,String.class},player.getUniqueId(),player.getName(),"SWWorks"+player.getName());
        Object nationId=call(nation,"id"),city=unwrap(invoke(cities,"getPlayerCity",new Class<?>[]{UUID.class},player.getUniqueId()));
        if(city==null){Object created=invoke(cities,"createCity",new Class<?>[]{nationId.getClass(),String.class,UUID.class,Location.class},nationId,"Works"+player.getName(),player.getUniqueId(),new Location(player.getWorld(),base(slot)*16+3,80,3));if(!Boolean.TRUE.equals(call(created,"success")))throw new IllegalStateException(String.valueOf(call(created,"message")));city=call(created,"city");}
        UUID cityId=(UUID)call(city,"id");List<Integer> chunks=List.of(base(slot),base(slot)+1);
        for(int chunk:chunks){claim(nations,player,nationId,chunk);invoke(cities,"addClaim",new Class<?>[]{UUID.class,String.class,int.class,int.class},cityId,player.getWorld().getName(),chunk,0);if(!Boolean.TRUE.equals(invoke(city,"hasClaim",new Class<?>[]{String.class,int.class,int.class},player.getWorld().getName(),chunk,0)))throw new IllegalStateException("Real city territory missing");player.getWorld().getChunkAt(chunk,0).setForceLoaded(true);}
        for(int index=0;index<KINDS.length;index++){int x=base(slot)*16+OFFSETS[index][0],z=OFFSETS[index][1];for(int dx=-2;dx<=2;dx++)for(int dz=-3;dz<=2;dz++){player.getWorld().getBlockAt(x+dx,79,z+dz).setType(Material.STONE);for(int y=80;y<=83;y++)player.getWorld().getBlockAt(x+dx,y,z+dz).setType(Material.AIR);}player.getWorld().setBiome(x,80,z,org.bukkit.block.Biome.PLAINS);}
        player.teleport(new Location(player.getWorld(),base(slot)*16+3.5,80,.5));
        emit("works-setup",player,Map.of("slot",slot,"cityId",cityId,"nationId",call(nationId,"value"),"chunks",chunks,"chunkZ",0,"dualTerritoryVerified",true,"anchorBiome","plains","forceLoadedChunks",chunks));
    }
    private void claim(Object nations,Player player,Object nationId,int chunk)throws Exception{
        String world=player.getWorld().getName();Object before=unwrap(invoke(nations,"claimAt",new Class<?>[]{String.class,int.class,int.class},world,chunk,0));
        if(before==null)invoke(nations,"claimCurrentChunk",new Class<?>[]{UUID.class,String.class,int.class,int.class},player.getUniqueId(),world,chunk,0);
        Object after=unwrap(invoke(nations,"claimAt",new Class<?>[]{String.class,int.class,int.class},world,chunk,0));if(after==null||!nationId.toString().equals(call(after,"ownerId")))throw new IllegalStateException("Real national territory not owned by expected nation");
    }
    private void register(Player player,int slot,int index){
        if(index==KINDS.length){registering.remove(player.getUniqueId());emit("works-register",player,Map.of("slot",slot,"dispatched",KINDS.length,"commands",Arrays.stream(KINDS).map(kind->"sw register "+kind).toList()));return;}
        try{
            if(!player.isOnline())throw new IllegalStateException("Works client left during registration");int x=base(slot)*16+OFFSETS[index][0],z=OFFSETS[index][1];player.getWorld().getBlockAt(x,80,z).setType(ANCHORS[index]);
            Location location=new Location(player.getWorld(),x+.5,80,z-2.5);location.setDirection(new Vector(x+.5,80.5,z+.5).subtract(location.toVector().add(new Vector(0,player.getEyeHeight(),0))));if(!player.teleport(location))throw new IllegalStateException("Works registration teleport cancelled");
            Bukkit.getScheduler().runTaskLater(owner,()->{try{
                if(!player.isOnline()||player.getTargetBlockExact(6)==null||player.getTargetBlockExact(6).getType()!=ANCHORS[index]||player.getTargetBlockExact(6).getX()!=x||player.getTargetBlockExact(6).getZ()!=z)throw new IllegalStateException("Actual works raycast misses "+KINDS[index]);
                if(!player.performCommand("sw register "+KINDS[index]))throw new IllegalStateException("Real registration command was not handled");
                Bukkit.getScheduler().runTaskLater(owner,()->register(player,slot,index+1),4);
            }catch(Exception failure){registering.remove(player.getUniqueId());failed(failure);}},4);
        }catch(Exception failure){registering.remove(player.getUniqueId());failed(failure);}
    }
    private void seed(Player player){
        Map<Material,Integer> goods=new LinkedHashMap<>();goods.put(Material.BREAD,512);goods.put(Material.OAK_PLANKS,512);goods.put(Material.OAK_LOG,128);goods.put(Material.PAPER,256);goods.put(Material.IRON_INGOT,256);goods.put(Material.WHEAT,256);goods.put(Material.ARROW,128);goods.put(Material.STONE,64);
        Map<String,Integer> recorded=new LinkedHashMap<>();for(var entry:goods.entrySet()){for(int remaining=entry.getValue();remaining>0;){int batch=Math.min(64,remaining);if(!player.getInventory().addItem(new ItemStack(entry.getKey(),batch)).isEmpty())throw new IllegalStateException("Works seed exceeds actual inventory capacity");remaining-=batch;}recorded.put(entry.getKey().getKey().toString(),entry.getValue());}player.saveData();emit("works-seed",player,Map.of("source","explicit-test-player-inventory","items",recorded,"productionEvidence",false,"profileSeeded",false));
    }
    private void inventory(Player player){Map<String,Integer> items=new TreeMap<>();for(ItemStack stack:player.getInventory().getStorageContents())if(stack!=null&&!stack.isEmpty())items.merge(stack.getType().getKey().toString(),stack.getAmount(),Integer::sum);emit("works-inventory",player,Map.of("items",items));}
    private void resupply(Player player,String id,int amount){
        if(!Set.of("minecraft:bread","minecraft:oak_planks","minecraft:oak_log","minecraft:paper","minecraft:iron_ingot","minecraft:wheat","minecraft:arrow","minecraft:stone").contains(id)||amount<1||amount>512)throw new IllegalArgumentException("Only bounded ordinary test materials may be explicitly seeded");Material material=Objects.requireNonNull(Material.matchMaterial(id));
        for(int left=amount;left>0;){int batch=Math.min(64,left);if(!player.getInventory().addItem(new ItemStack(material,batch)).isEmpty())throw new IllegalStateException("Actual inventory full during explicit resupply");left-=batch;}player.saveData();emit("works-resupply",player,Map.of("source","explicit-continuous-world-player-inventory-resupply","items",Map.of(id,amount),"productionEvidence",false,"profileSeeded",false));
    }
    private void node(Player player,int slot){
        int x=base(slot)*16+7,z=7;for(int dx=-2;dx<=2;dx++)for(int dz=-3;dz<=2;dz++){player.getWorld().getBlockAt(x+dx,79,z+dz).setType(Material.STONE);for(int y=80;y<=83;y++)player.getWorld().getBlockAt(x+dx,y,z+dz).setType(Material.AIR);}player.getWorld().getBlockAt(x,80,z).setType(Material.LODESTONE);player.getWorld().setBiome(x,80,z,org.bukkit.block.Biome.PLAINS);
        Location location=new Location(player.getWorld(),x+.5,80,z-2.5);location.setDirection(new Vector(x+.5,80.5,z+.5).subtract(location.toVector().add(new Vector(0,player.getEyeHeight(),0))));if(!player.teleport(location))throw new IllegalStateException("Road-node teleport cancelled");
        Bukkit.getScheduler().runTaskLater(owner,()->{try{if(player.getTargetBlockExact(6)==null||player.getTargetBlockExact(6).getType()!=Material.LODESTONE||player.getTargetBlockExact(6).getX()!=x||player.getTargetBlockExact(6).getZ()!=z)throw new IllegalStateException("Real road raycast missed");if(!player.performCommand("sw node register road"))throw new IllegalStateException("Real node command not handled");emit("works-node",player,Map.of("entryPoint","player /sw node register road","slot",slot,"x",x,"y",80,"z",z,"dispatched",1));}catch(Exception failure){failed(failure);}},4);
    }
    private void war(Player player,Player peer,boolean begin)throws Exception{
        Object nations=coreService("module.nation.NationService"),first=call(Objects.requireNonNull(unwrap(invoke(nations,"nationOf",new Class<?>[]{UUID.class},player.getUniqueId()))),"id"),second=call(Objects.requireNonNull(unwrap(invoke(nations,"nationOf",new Class<?>[]{UUID.class},peer.getUniqueId()))),"id"),wars=coreService("module.war.WarService");Class<?> id=first.getClass();
        boolean changed=Boolean.TRUE.equals(invoke(wars,begin?"declareWar":"endWar",new Class<?>[]{id,id,id},first,first,second)),atWar=Boolean.TRUE.equals(invoke(wars,"atWar",new Class<?>[]{id,id},first,second));if(!changed||atWar!=begin)throw new IllegalStateException("Public core war/peace rejected; isolated new-nation protection must be zero and declaration funded/zero");
        emit(begin?"works-war":"works-peace",player,Map.of("entryPoint",begin?"WarService.declareWar":"WarService.endWar","atWar",atWar,"nationA",call(first,"value"),"nationB",call(second,"value"),"peer",peer.getName()));
    }
    private void famine(Player player){
        boolean previous=player.hasPermission("starworld.admin");org.bukkit.permissions.PermissionAttachment permission=player.addAttachment(owner,"starworld.admin",true);
        try{if(!player.performCommand("sw event famine"))throw new IllegalStateException("Normal famine admin command not handled");}
        finally{player.removeAttachment(permission);}
        emit("works-famine",player,Map.of("entryPoint","player /sw event famine","temporaryTestPermission","starworld.admin","permissionBefore",previous,"permissionAfter",player.hasPermission("starworld.admin"),"directSimulationMutation",false,"dispatched",true));
    }
    private void claimChange(Player player,int slot,boolean restore)throws Exception{
        Object nations=coreService("module.nation.NationService"),nation=Objects.requireNonNull(unwrap(invoke(nations,"nationOf",new Class<?>[]{UUID.class},player.getUniqueId()))),nationId=call(nation,"id");int chunk=base(slot)+1;
        if(restore)claim(nations,player,nationId,chunk);else invoke(nations,"unclaimCurrentChunk",new Class<?>[]{UUID.class,String.class,int.class,int.class},player.getUniqueId(),player.getWorld().getName(),chunk,0);
        Object after=unwrap(invoke(nations,"claimAt",new Class<?>[]{String.class,int.class,int.class},player.getWorld().getName(),chunk,0)),cities=coreService("module.city.CityService"),city=unwrap(invoke(cities,"getPlayerCity",new Class<?>[]{UUID.class},player.getUniqueId()));
        if(!restore&&after!=null)throw new IllegalStateException("National claim remains after unclaim");boolean cityClaim=Boolean.TRUE.equals(invoke(city,"hasClaim",new Class<?>[]{String.class,int.class,int.class},player.getWorld().getName(),chunk,0));if(!cityClaim)throw new IllegalStateException("Fixture must preserve the independent city claim");
        emit(restore?"works-reclaim":"works-unclaim",player,Map.of("entryPoint",restore?"NationService.claimCurrentChunk":"NationService.unclaimCurrentChunk","chunkX",chunk,"chunkZ",0,"nationalClaimPresent",after!=null,"cityClaimPreserved",cityClaim));
    }
    private void block(Player player,int slot,String kind,boolean repair){int index=index(kind),x=base(slot)*16+OFFSETS[index][0],z=OFFSETS[index][1];Material before=player.getWorld().getBlockAt(x,80,z).getType();player.getWorld().getBlockAt(x,80,z).setType(repair?ANCHORS[index]:Material.AIR);emit(repair?"works-repair":"works-break",player,Map.of("facility",kind,"x",x,"y",80,"z",z,"before",before.getKey().toString(),"after",player.getWorld().getBlockAt(x,80,z).getType().getKey().toString(),"actualWorldEdit",true));}

    /** Only public API Futures and bounded read methods; never invoke works.start/cycle/completed here. */
    private void snapshot(Player player)throws Exception{
        Object runtime=service("StarWorld","dev.starworld.api.StarWorldApi"),expansion=unwrap(call(runtime,"expansion")),core=unwrap(invoke(runtime,"playerCity",new Class<?>[]{Player.class},player));UUID cityId=(UUID)call(core,"cityId");
        CompletableFuture<?> city=future(runtime,"city",new Class<?>[]{UUID.class},cityId),profile=future(runtime,"development",new Class<?>[]{UUID.class},cityId),atlas=future(runtime,"regions",new Class<?>[]{UUID.class,String.class,int.class},cityId,"",16),projects=future(runtime,"publicWorks",new Class<?>[]{UUID.class,int.class,int.class},cityId,64,0),culture=future(runtime,"culture",new Class<?>[]{UUID.class},cityId),life=future(runtime,"life",new Class<?>[]{UUID.class},cityId),routes=future(runtime,"routes",new Class<?>[]{UUID.class,int.class,int.class},cityId,64,0),orders=future(runtime,"orders",new Class<?>[]{UUID.class,int.class,int.class},cityId,64,0),responses=future(runtime,"disasterResponses",new Class<?>[]{UUID.class,int.class,int.class},cityId,64,0),disasterHistory=future(runtime,"disasterHistory",new Class<?>[]{UUID.class,int.class,int.class},cityId,64,0);
        CompletableFuture.allOf(city,profile,atlas,projects,culture,life,routes,orders,responses,disasterHistory).whenComplete((ignored,error)->Bukkit.getScheduler().runTask(owner,()->{try{
            if(error!=null)throw new IllegalStateException(error);Object view=Objects.requireNonNull(unwrap(city.join())),current=unwrap(invoke(runtime,"playerCity",new Class<?>[]{Player.class},player));if(current==null||!cityId.equals(call(current,"cityId"))||!call(core,"nationId").equals(call(current,"nationId")))throw new IllegalStateException("Works snapshot core authority changed");
            Object verified=invoke(runtime,"verifiedBuildings",new Class<?>[]{view.getClass()},view);long period=((Number)call(runtime,"currentPeriod")).longValue();
            Object nation=invoke(expansion,"nation",new Class<?>[]{UUID.class},call(current,"nationId"));
            async(runtime,()->{Object works=call(expansion,"works"),logistics=call(expansion,"logistics");Map<String,Object> report=new LinkedHashMap<>();report.put("version",call(runtime,"version"));report.put("city",view);report.put("development",unwrap(profile.join()));report.put("atlas",unwrap(atlas.join()));report.put("projects",projects.join());report.put("history",worksHistory(works,cityId));report.put("culture",culture.join());report.put("life",unwrap(life.join()));report.put("core",current);report.put("nation",nation);report.put("routes",routes.join());report.put("orders",orders.join());report.put("nodes",invoke(logistics,"cityNodes",new Class<?>[]{UUID.class},cityId));report.put("tradeReceipts",invoke(logistics,"completedAfter",new Class<?>[]{long.class,int.class},0L,64));report.put("responses",responses.join());report.put("disasterHistory",disasterHistory.join());report.put("verified",verified);report.put("currentPeriod",period);report.put("capturedAt",System.currentTimeMillis());report.put("feasibility",invoke(works,"inspect",new Class<?>[]{current.getClass(),view.getClass(),long.class,Set.class},current,view,period,verified));report.put("worksDefinitions",call(call(works,"configuration"),"data"));report.put("definitions",call(runtime,"definitions"));report.put("evolutionOptions",call(call(expansion,"evolution"),"options"));return report;}).whenComplete((record,failure)->Bukkit.getScheduler().runTask(owner,()->{if(failure!=null)failed(failure);else emit("works-snapshot",player,record);}));
        }catch(Exception failure){failed(failure);}}));
    }
    /** Called on the existing storage executor: four public bounded pages cover the default 200-row history. */
    private List<Object> worksHistory(Object works,UUID city)throws Exception{
        List<Object> result=new ArrayList<>();
        for(int page=0;page<4;page++){
            Object value=invoke(works,"history",new Class<?>[]{UUID.class,int.class,int.class},city,64,page*64);
            if(!(value instanceof List<?> rows))throw new IllegalStateException("Public works history did not return a list");
            result.addAll(rows);if(rows.size()<64)break;
        }
        return List.copyOf(result);
    }
    @FunctionalInterface private interface Read {Object run()throws Exception;}
    @SuppressWarnings("unchecked") private CompletableFuture<Object> async(Object runtime,Read read)throws Exception{Class<?> work=type("StarWorld","dev.starworld.runtime.WorldRuntime$Work");Object proxy=Proxy.newProxyInstance(work.getClassLoader(),new Class<?>[]{work},(ignored,method,args)->{if(method.getName().equals("run"))return read.run();if(method.getName().equals("toString"))return "Public works acceptance read";throw new UnsupportedOperationException(method.getName());});return (CompletableFuture<Object>)invoke(runtime,"async",new Class<?>[]{work},proxy);}
    private static CompletableFuture<?> future(Object runtime,String method,Class<?>[] types,Object... args)throws Exception{return (CompletableFuture<?>)invoke(runtime,method,types,args);}
    private Object coreService(String suffix)throws Exception{Object api=service("StarCore",CORE+"api.StarCoreApi");return Objects.requireNonNull(unwrap(type("StarCore",CORE+"api.StarCoreApi").getMethod("service",Class.class).invoke(api,type("StarCore",CORE+suffix))),suffix);}
    private void emit(String action,Player player,Object value){try{owner.getLogger().info("SW_FIXTURE_WORKS "+json.toJson(Map.of("schema",1,"action",action,"player",player.getName(),"observed",tree(value))));}catch(Exception failure){failed(failure);}}
    private static Object tree(Object value)throws Exception{if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;if(value instanceof UUID||value instanceof TemporalAccessor||value instanceof Enum<?>)return value.toString();if(value instanceof Optional<?> optional)return tree(optional.orElse(null));if(value instanceof Map<?,?> map){Map<String,Object> result=new LinkedHashMap<>();for(var entry:map.entrySet())result.put(String.valueOf(entry.getKey()),tree(entry.getValue()));return result;}if(value instanceof Collection<?> collection){List<Object> result=new ArrayList<>();for(Object item:collection)result.add(tree(item));return result;}if(value.getClass().isRecord()){Map<String,Object> result=new LinkedHashMap<>();for(RecordComponent component:value.getClass().getRecordComponents())result.put(component.getName(),tree(component.getAccessor().invoke(value)));return result;}return value.toString();}
    private void failed(Throwable failure){owner.getLogger().log(java.util.logging.Level.SEVERE,"SW_FIXTURE_FAILED works",failure);}
    private static Object call(Object target,String method)throws Exception{return invoke(target,method,new Class<?>[0]);}
    private static Object invoke(Object target,String method,Class<?>[] types,Object... args)throws Exception{return target.getClass().getMethod(method,types).invoke(target,args);}
    private static Object unwrap(Object value){return ((Optional<?>)value).orElse(null);}
    private static Class<?> type(String plugin,String name)throws Exception{Plugin owner=Objects.requireNonNull(Bukkit.getPluginManager().getPlugin(plugin),plugin);return Class.forName(name,true,owner.getClass().getClassLoader());}
    private static Object service(String plugin,String name)throws Exception{return Objects.requireNonNull(Bukkit.getServicesManager().load(type(plugin,name)),name);}
}

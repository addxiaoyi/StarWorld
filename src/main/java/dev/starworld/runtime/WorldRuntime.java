package dev.starworld.runtime;

import dev.starworld.api.*;
import dev.starworld.api.event.*;
import dev.starworld.config.ConfigLoader;
import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.persistence.WorldStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldRuntime implements StarWorldApi,AutoCloseable {
    private static final int MAX_SETTLEMENTS_IN_FLIGHT = StorageWorker.DEFAULT_QUEUE_CAPACITY / 4;
    private final JavaPlugin plugin;
    private final Definitions definitions;
    private final CoreBridge core;
    private final CustomItems items;
    private final WorldStore store;
    private final Simulation simulation;
    private final SchedulerGate scheduler;
    private final WorldStore.SimulationClock clock;
    private final StorageWorker io=new StorageWorker();
    private final AtomicBoolean settling=new AtomicBoolean();
    private final Set<UUID> transfers=ConcurrentHashMap.newKeySet();
    private final Set<UUID> activeTransferIds=ConcurrentHashMap.newKeySet();
    private int cursor;
    private volatile boolean closed;
    private boolean terminated;
    private ExpansionRuntime expansion;
    public WorldRuntime(JavaPlugin plugin,Definitions definitions,CoreBridge core,CustomItems items,WorldStore store){this.plugin=plugin;this.definitions=definitions;this.core=core;this.items=items;this.store=store;
        Set<String> available=new HashSet<>();for(String id:ConfigLoader.items(definitions))if(items.create(id,1)!=null)available.add(id);
        simulation=new Simulation(store,definitions,available);scheduler=new SchedulerGate(plugin);try{clock=store.simulationClock(definitions.periodSeconds(),Instant.now().getEpochSecond());}catch(java.sql.SQLException e){throw new IllegalStateException("Cannot initialize simulation clock",e);}}
    @FunctionalInterface public interface Work<T>{T run()throws Exception;}
    public <T> CompletableFuture<T> async(Work<T> work){
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("StarWorld is stopping"));
        return io.submit(work::run);
    }
    public <T> CompletableFuture<T> globalFuture(Callable<T> work){return scheduler.globalFuture(work);}
    public <T> void reply(Player player,CompletableFuture<T> future,Consumer<T> action){future.whenComplete((value,error)->scheduler.player(player,()->{
        if(error!=null){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();player.sendMessage("§cStarWorld: "+cause.getMessage());plugin.getLogger().fine(error.toString());}
        else try{action.accept(value);}catch(RuntimeException failure){player.sendMessage("§cStarWorld: "+failure.getMessage());plugin.getLogger().fine(failure.toString());}
    },()->{}));}
    public void tell(Player p,String message){scheduler.player(p,()->p.sendMessage(message),()->{});}
    public Optional<CoreCity> playerCity(Player p){return core.playerCity(p.getUniqueId());}
    public boolean manage(Player p,UUID city){return p.hasPermission("starworld.admin") || core.canManage(p.getUniqueId(),city);}
    public CompletableFuture<CityView> open(CoreCity city){return async(()->{store.ensureCity(city);return store.view(city.cityId()).orElseThrow();});}
    public long currentPeriod(){return clock.period(Instant.now().getEpochSecond());}
    public void installExpansion(java.io.File folder)throws Exception{if(expansion!=null)throw new IllegalStateException("Expansion already installed");expansion=new ExpansionRuntime(plugin,this,definitions,core,items,store,folder);expansion.start();}
    public Optional<ExpansionRuntime> expansion(){return Optional.ofNullable(expansion);}
    public boolean expansionCommand(Player p,String[] args){return expansion!=null&&expansion.command(p,args);}
    public Set<UUID> verifiedBuildings(CityView city){Set<UUID> result=new HashSet<>();for(Building b:city.buildings())if(b.nationId().equals(city.nationId())&&valid(b))result.add(b.id());return Set.copyOf(result);}
    @Override public String version(){return plugin.getPluginMeta().getVersion();}
    @Override public Definitions definitions(){return definitions;}
    @Override public CompletableFuture<Optional<CityView>> city(UUID id){return async(()->store.view(id));}
    @Override public CompletableFuture<Optional<dev.starworld.evolution.EvolutionState.Profile>> development(UUID id){return async(()->expansion==null||store.view(id).isEmpty()?Optional.empty():Optional.of(expansion.evolution().profile(id)));}
    @Override public CompletableFuture<List<dev.starworld.evolution.EvolutionState.Culture>> culture(UUID id){return async(()->expansion==null?List.of():expansion.evolution().cultures(id));}
    @Override public CompletableFuture<Optional<dev.starworld.life.LifeState.LifeView>> life(UUID id){return async(()->expansion==null||store.view(id).isEmpty()?Optional.empty():Optional.of(expansion.life().view(id,currentPeriod())));}
    @Override public CompletableFuture<Optional<dev.starworld.life.LifeState.EvolutionCredit>> lifeEvolutionCredit(UUID actionId){return async(()->expansion==null?Optional.empty():expansion.life().evolutionCredit(actionId));}
    @Override public CompletableFuture<List<dev.starworld.logistics.LogisticsState.Route>> routes(UUID id,int limit,int offset){return async(()->expansion==null?List.of():expansion.logistics().cityRoutes(id,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.logistics.LogisticsState.RoutePlan>> routePlans(UUID origin,UUID target,dev.starworld.logistics.LogisticsState.Mode mode,int limit,int offset){return async(()->expansion==null?List.of():expansion.logistics().compareRoutes(origin,target,mode,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.logistics.LogisticsState.Order>> orders(UUID id,int limit,int offset){return async(()->expansion==null?List.of():expansion.logistics().cityOrders(id,limit,offset));}
    @Override public CompletableFuture<Optional<dev.starworld.evolution.EvolutionState.Readiness>> readiness(UUID id){return async(()->expansion==null?Optional.empty():expansion.evolution().readiness(id));}
    @Override public CompletableFuture<Optional<dev.starworld.evolution.NationalEvolution.NationDevelopment>> nationDevelopment(UUID id){return async(()->{if(expansion==null||store.query("SELECT 1 FROM nation_state WHERE id=?",r->1,id).isEmpty())return Optional.empty();return Optional.of(expansion.national().view(id));});}
    @Override public CompletableFuture<Optional<dev.starworld.evolution.RegionAtlas.Page>> regions(UUID id,String after,int limit){return async(()->expansion==null||store.view(id).isEmpty()?Optional.empty():Optional.of(expansion.atlas().page(id,after,limit)));}
    @Override public CompletableFuture<Optional<dev.starworld.evolution.RegionAtlas.Summary>> geography(UUID id){return async(()->expansion==null||store.view(id).isEmpty()?Optional.empty():Optional.of(expansion.atlas().summary(id)));}
    @Override public CompletableFuture<Optional<dev.starworld.logistics.LogisticsState.TransportStatus>> transport(UUID route){return async(()->expansion==null||expansion.logistics().route(route).isEmpty()?Optional.empty():Optional.of(expansion.logistics().transportStatus(route,currentPeriod())));}
    @Override public CompletableFuture<List<dev.starworld.market.MarketState.Listing>> marketListings(int limit,int offset){return async(()->expansion==null?List.of():expansion.market().listings(null,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.market.MarketState.Purchase>> marketPurchases(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.market().purchases(city,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.market.MarketState.DeliveryStats>> marketDeliveryStats(UUID city,String item,int limit,int offset){return async(()->expansion==null?List.of():expansion.market().deliveryStats(city,item,limit,offset));}
    @Override public CompletableFuture<Optional<dev.starworld.disaster.DisasterState.Response>> disasterResponse(UUID id){return async(()->expansion==null?Optional.empty():expansion.disasters().response(id));}
    @Override public CompletableFuture<List<dev.starworld.disaster.DisasterState.Response>> disasterResponses(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.disasters().responses(city,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.disaster.DisasterState.History>> disasterHistory(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.disasters().history(city,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.works.PublicWorksState.Project>> publicWorks(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.works().projects(city,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.works.PublicWorksState.History>> publicWorksHistory(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.works().history(city,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.civic.CivicNeedsState.Request>> civicNeeds(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.civic().requests(city,limit,offset));}
    @Override public CompletableFuture<List<dev.starworld.civic.CivicNeedsState.History>> civicHistory(UUID city,int limit,int offset){return async(()->expansion==null?List.of():expansion.civic().history(city,limit,offset));}
    @Override public CompletableFuture<Optional<dev.starworld.civic.CivicNeedsState.Receipt>> civicReceipt(UUID request){return async(()->expansion==null?Optional.empty():expansion.civic().receipt(request));}
    private record ClimateRead(Optional<CityView> city,Optional<dev.starworld.works.PublicWorksState.Project> works){}
    @Override public CompletableFuture<Optional<dev.starworld.api.StarWorldApi.ClimateReport>> climate(UUID id){
        if(expansion==null)return CompletableFuture.completedFuture(Optional.empty());
        return async(()->{Optional<CityView> view=store.view(id);Optional<dev.starworld.works.PublicWorksState.Project> worksRecord=view.isEmpty()?Optional.empty():expansion.works().project(id,view.get().nationId());return new ClimateRead(view,worksRecord);})
                .thenCompose(read->read.city().isEmpty()?CompletableFuture.completedFuture(Optional.empty()):scheduler.globalFuture(()->{
                    if(expansion==null)return Optional.empty();CityView registered=read.city().get();Optional<CoreCity> current=core.city(id);
                    if(current.isEmpty()||!current.get().nationId().equals(registered.nationId()))return Optional.empty();Set<UUID> valid=verifiedBuildings(registered);
                    boolean irrigation=read.works().filter(project->project.definition().id().equals("irrigation_network")&&project.completed()&&project.lastOperationPaid()&&project.lastOperationPeriod()==registered.lastPeriod()).isPresent();
                    return Optional.of(new dev.starworld.api.StarWorldApi.ClimateReport(id,current.get().nationId(),expansion.verifiedClimateExposure(current.get(),registered,valid,irrigation)));
                }));
    }
    @Override public Map<String,String> integrationStatus(){Map<String,String> s=new LinkedHashMap<>(items.status());s.put("StarCore",core.status());if(expansion!=null)s.putAll(expansion.status());s.put("Folia","未声明支持：当前 StarCore 城市/科技服务不保证区域线程安全，需 Paper");return Map.copyOf(s);}
    public void register(Player p,String type){
        CoreCity city=playerCity(p).orElseThrow(()->new IllegalArgumentException("请先在 StarCore 加入城市"));
        if(!manage(p,city.cityId()))throw new IllegalArgumentException("需要市长或城市管理权限");
        Definitions.BuildingType d=Optional.ofNullable(definitions.buildings().get(type)).orElseThrow(()->new IllegalArgumentException("未知设施类型"));
        Block block=p.getTargetBlockExact(6);if(block==null)throw new IllegalArgumentException("请看向六格内的设施锚点");
        if(block.getType()!=Material.matchMaterial(d.anchor()))throw new IllegalArgumentException("设施锚点必须为 "+d.anchor());
        World world=block.getWorld();
        Site site=new Site(world.getUID(),world.getName(),block.getX(),block.getY(),block.getZ());
        if(!core.ownsSite(city.cityId(),site))throw new IllegalArgumentException("锚点必须同时位于本城市领地和本国领土");
        // Do not persist the initial Core snapshot before the player/territory recheck below.
        // Ownership can change while the storage worker is queued.
        reply(p,CompletableFuture.completedFuture(city),registered->{
            CoreCity member=playerCity(p).filter(current->current.cityId().equals(registered.cityId())
                    &&current.nationId().equals(registered.nationId()))
                    .orElseThrow(()->new IllegalArgumentException("城市归属已变化"));
            if(!manage(p,member.cityId()))throw new IllegalArgumentException("城市管理权限已变化");
            World currentWorld=Bukkit.getWorld(site.worldId());
            if(currentWorld==null||!currentWorld.isChunkLoaded(site.x()>>4,site.z()>>4))
                throw new IllegalArgumentException("设施所在区块已卸载，请重新登记");
            Block currentBlock=currentWorld.getBlockAt(site.x(),site.y(),site.z());
            if(currentBlock.getType()!=Material.matchMaterial(d.anchor()))
                throw new IllegalArgumentException("设施锚点已变化，请重新登记");
            if(!core.ownsSite(member.cityId(),site))throw new IllegalArgumentException("锚点领土归属已变化");
            String terrain=TerrainClassifier.classify(currentBlock.getBiome().getKey().getKey());
            Building building=new Building(UUID.randomUUID(),member.cityId(),member.nationId(),type,site,1);
            reply(p,async(()->{store.ensureCity(member);store.addBuilding(building,terrain,definitions);return building;}),created->
                    p.sendMessage("§a已登记 "+d.name()+" · "+terrain+" · "+created.id()));
        });
    }
    public void survey(Player p){
        CoreCity city=playerCity(p).orElseThrow(()->new IllegalArgumentException("请先在 StarCore 加入城市"));
        if(!manage(p,city.cityId()))throw new IllegalArgumentException("需要市长或城市管理权限");
        org.bukkit.Location location=p.getLocation();org.bukkit.World world=location.getWorld();
        if(world==null)throw new IllegalArgumentException("当前世界不可用");
        int x=location.getBlockX(),y=location.getBlockY(),z=location.getBlockZ();
        if(!world.isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("当前区块尚未加载，无法勘探");
        Site site=new Site(world.getUID(),world.getName(),x,y,z);
        if(!core.ownsSite(city.cityId(),site))throw new IllegalArgumentException("只能勘探本城市和本国都拥有的区块");
        String actor=p.getUniqueId().toString();
        // Revalidate the live Core city first; only then initialize/update its local row.
        reply(p,CompletableFuture.completedFuture(true),ignored->{
            CoreCity member=playerCity(p).filter(current->current.cityId().equals(city.cityId())&&current.nationId().equals(city.nationId())).orElseThrow(()->new IllegalArgumentException("城市归属已变化"));
            if(!manage(p,member.cityId()))throw new IllegalArgumentException("城市管理权限已变化");
            org.bukkit.World currentWorld=Bukkit.getWorld(site.worldId());
            org.bukkit.Location currentLocation=p.getLocation();
            if(currentWorld==null||!p.getWorld().getUID().equals(site.worldId())||(currentLocation.getBlockX()>>4)!=(x>>4)||(currentLocation.getBlockZ()>>4)!=(z>>4)||!currentWorld.isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("玩家位置或区块状态已变化，请重新勘探");
            if(!core.ownsSite(member.cityId(),site))throw new IllegalArgumentException("区块领土归属已变化");
            String terrain=TerrainClassifier.classify(currentWorld.getBlockAt(x,y,z).getBiome().getKey().getKey());
            reply(p,async(()->{store.ensureCity(member);return store.surveyRegion(member,site,terrain,definitions,actor);}),created->p.sendMessage(created?"§a已勘探 "+terrain+" 区块 "+(x>>4)+","+(z>>4)+"；城市仓库消耗8张纸":"§e该区块已在地图册中，无需重复勘探或消耗纸张"));
        });
    }
    public void remove(Player p,UUID id){CoreCity c=requireManager(p);reply(p,async(()->{store.removeBuilding(c.cityId(),id);store.audit(p.getUniqueId().toString(),"REMOVE "+id);return id;}),v->p.sendMessage("§a设施已注销，地区储量保留"));}
    public void upgrade(Player p,UUID id){CoreCity c=requireManager(p);reply(p,async(()->{store.upgrade(c.cityId(),id);return id;}),v->p.sendMessage("§a设施已升级"));}
    public void civilization(Player p,String id){
        CoreCity c=requireManager(p);if(!p.hasPermission("starworld.admin") && !core.canGovern(p.getUniqueId(),c.cityId()))throw new IllegalArgumentException("国家文明转型需要国家创始人权限");
        if(!definitions.civilizations().containsKey(id))throw new IllegalArgumentException("未知文明特性");
        reply(p,async(()->{store.ensureCity(c);store.civilization(c.cityId(),id,currentPeriod());store.audit(p.getUniqueId().toString(),"CIVILIZATION "+c.nationId()+" "+id);return id;}),v->p.sendMessage("§a国家文明已转型："+definitions.civilizations().get(id).name()));
    }
    private CoreCity requireManager(Player p){CoreCity c=playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));if(!manage(p,c.cityId()))throw new IllegalArgumentException("需要城市管理权限");return c;}
    public void transfer(Player p,String item,int amount,boolean deposit){
        CoreCity city=requireManager(p);if(!ConfigLoader.items(definitions).contains(item)&&(expansion==null||!expansion.itemIds().contains(item)))throw new IllegalArgumentException("该物品未在产业/生活/交通配置中定义");
        if(amount<1 || amount>2304)throw new IllegalArgumentException("数量须为 1–2304");
        if(items.create(item,1)==null)throw new IllegalArgumentException("自定义物品不可用，请检查 CraftEngine 与物品 ID");
        if(!transfers.add(p.getUniqueId()))throw new IllegalArgumentException("上一笔物品操作正在进行");
        async(()->{store.ensureCity(city);Transfer t=store.prepareTransfer(p.getUniqueId(),city.cityId(),deposit ? "DEPOSIT":"WITHDRAW",item,amount);activeTransferIds.add(t.id());return t;}).whenComplete((transfer,error)->{
            if(error!=null){transfers.remove(p.getUniqueId());tell(p,"§c准备物品操作失败："+root(error).getMessage());return;}
            scheduler.player(p,()->{
                if(!manage(p,city.cityId()) || playerCity(p).map(c->!c.cityId().equals(city.cityId()) || !c.nationId().equals(city.nationId())).orElse(true)) {cancel(transfer,p,"城市或权限已变化");return;}
                try{if(deposit)deposit(p,transfer);else withdraw(p,transfer);}catch(RuntimeException failure){
                    activeTransferIds.remove(transfer.id());transfers.remove(p.getUniqueId());
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,"Inventory operation requires reconciliation: "+transfer.id(),failure);
                    tell(p,"§c物品操作异常，已隔离待核对："+transfer.id());
                }
            },()->cancel(transfer,p,"玩家已离线"));
        });
    }
    private void deposit(Player p,Transfer t){
        ItemStack[] contents=p.getInventory().getStorageContents();boolean[] matching=new boolean[contents.length];int count=0;
        for(int i=0;i<contents.length;i++){ItemStack stack=contents[i];matching[i]=stack!=null && items.matches(stack,t.item());if(matching[i])count+=stack.getAmount();}
        if(count<t.amount()){cancel(t,p,"背包中的普通物品不足（特殊元数据道具不计入）");return;}
        int remaining=t.amount();for(int i=0;i<contents.length && remaining>0;i++){ItemStack stack=contents[i];if(matching[i]){int n=Math.min(remaining,stack.getAmount());remaining-=n;stack=stack.clone();stack.setAmount(stack.getAmount()-n);contents[i]=stack.getAmount()==0 ? null:stack;}}
        if(remaining!=0){cancel(t,p,"物品快照不一致，操作取消");return;}
        p.getInventory().setStorageContents(contents);
        try{p.saveData();}catch(RuntimeException e){activeTransferIds.remove(t.id());transfers.remove(p.getUniqueId());tell(p,"§c玩家数据保存失败，存入待核对："+t.id());return;}
        async(()->{store.completeTransfer(t.id());return true;}).whenComplete((ok,error)->{
            activeTransferIds.remove(t.id());transfers.remove(p.getUniqueId());tell(p,error==null ? "§a已存入 "+t.amount()+" "+t.item():"§c存入待人工核对，禁止重试。凭据："+t.id());
        });
    }
    private void withdraw(Player p,Transfer t){
        ItemStack sample=items.create(t.item(),1);if(sample==null){cancel(t,p,"物品定义不可用");return;}
        ItemStack[] content=p.getInventory().getStorageContents();int capacity=0;for(ItemStack s:content)if(s==null || s.getType().isAir())capacity+=sample.getMaxStackSize();else if(s.isSimilar(sample))capacity+=Math.max(0,sample.getMaxStackSize()-s.getAmount());
        if(capacity<t.amount()){cancel(t,p,"背包空间不足");return;}
        // PREPARED is durably debited before giving items. A crash at any point requires explicit reconciliation.
        List<ItemStack> batches=new ArrayList<>();for(int left=t.amount();left>0;){int n=Math.min(left,sample.getMaxStackSize());ItemStack stack=sample.clone();stack.setAmount(n);batches.add(stack);left-=n;}
        Map<Integer,ItemStack> leftovers=p.getInventory().addItem(batches.toArray(ItemStack[]::new));
        if(!leftovers.isEmpty()){activeTransferIds.remove(t.id());transfers.remove(p.getUniqueId());tell(p,"§c物品交付异常，请管理员核对凭据："+t.id());return;}
        try{p.saveData();}catch(RuntimeException e){activeTransferIds.remove(t.id());transfers.remove(p.getUniqueId());tell(p,"§c玩家数据保存失败，交付待核对："+t.id());return;}
        async(()->{store.completeTransfer(t.id());return true;}).whenComplete((ok,error)->{activeTransferIds.remove(t.id());transfers.remove(p.getUniqueId());tell(p,error==null ? "§a已取出 "+t.amount()+" "+t.item():"§c交付待核对，禁止重试。凭据："+t.id());});
    }
    private void cancel(Transfer t,Player p,String reason){async(()->{store.cancelUntouchedTransfer(t.id());return true;}).whenComplete((ok,error)->{activeTransferIds.remove(t.id());transfers.remove(p.getUniqueId());tell(p,"§c"+reason+(error==null ? "":"；凭据待核对："+t.id()));});}
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
    public CompletableFuture<List<Transfer>> pending(){return async(store::pending);}
    /** Admin-only command boundary supplies a simple filename; snapshots stay under plugins/StarWorld/backups. */
    public CompletableFuture<WorldStore.Backup> backup(String filename){
        if(filename==null||!filename.matches("[A-Za-z0-9._-]{1,96}"))throw new IllegalArgumentException("备份文件名只能包含字母、数字、点、下划线和短横线");
        java.nio.file.Path folder=plugin.getDataFolder().toPath();
        return async(()->store.backupInPluginDirectory(folder,filename));
    }
    public CompletableFuture<Boolean> resolve(UUID id,boolean credit,String actor){return async(()->{if(activeTransferIds.contains(id))throw new IllegalStateException("这笔物品操作仍在进行，不能人工处理");store.resolveTransfer(id,credit,actor);return true;});}
    public void trigger(Player p,String type){CoreCity c=playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入城市"));reply(p,async(()->{store.ensureCity(c);EventState previous=store.view(c.cityId()).orElseThrow().event();EventState next=simulation.trigger(c.cityId(),type,currentPeriod());store.audit(p.getUniqueId().toString(),"EVENT "+type+" "+c.cityId());return new WorldEvolutionEvent(c.cityId(),previous,next);}),event->{Bukkit.getPluginManager().callEvent(event);p.sendMessage("§a事件已触发："+type);});}
    public void tick(){
        if(!closed&&expansion!=null)expansion.tick();
        if(closed || !settling.compareAndSet(false,true))return;
        async(()->{List<UUID> ids=store.cities(definitions.maxCitiesPerPass(),cursor);if(ids.isEmpty()){cursor=0;ids=store.cities(definitions.maxCitiesPerPass(),0);}cursor+=ids.size();List<CityView> views=new ArrayList<>();for(UUID id:ids)store.view(id).ifPresent(views::add);return views;}).whenComplete((views,error)->{
            if(error!=null){failure(error);return;}
            scheduler.global(()->{
                long period=currentPeriod();
                try{Map<UUID,CoreCity> currentCities=new HashMap<>();for(CityView view:views)core.city(view.id()).ifPresent(city->currentCities.put(view.id(),city));
                    // Reconcile the entire captured batch before any city can use national contributions.
                    async(()->store.tx(s->{for(CityView view:views){CoreCity city=currentCities.get(view.id());if(city==null)s.invalidateCity(view.id());else s.ensureCity(city);}return null;}))
                            .thenCompose(ignored->scheduler.globalFuture(()->scheduleSettlementBatch(
                                    views,currentCities,period,0,new java.util.concurrent.atomic.AtomicReference<>()))
                                    .thenCompose(next->next))
                            .whenComplete((nothing,err)->{settling.set(false);if(err!=null&&!closed)plugin.getLogger().severe("Settlement failed (transaction rolled back): "+root(err));});
                }catch(Exception e){failure(e);return;}
            });
        });
    }
    private CompletableFuture<Void> scheduleSettlementBatch(List<CityView> views,Map<UUID,CoreCity> currentCities,
                                                               long period,int offset,
                                                               java.util.concurrent.atomic.AtomicReference<Throwable> firstFailure){
        if(offset>=views.size()){
            Throwable error=firstFailure.get();return error==null?CompletableFuture.completedFuture(null):CompletableFuture.failedFuture(error);
        }
        int end=Math.min(offset+MAX_SETTLEMENTS_IN_FLIGHT,views.size());
        List<CompletableFuture<?>> jobs=new ArrayList<>(end-offset);
        for(int index=offset;index<end;index++){
            CityView view=views.get(index);CoreCity current=currentCities.get(view.id());if(current==null)continue;
            try{
                Set<UUID> valid=verifiedBuildings(view),observed=Simulation.buildingIds(view);
                dev.starworld.integration.BorderConflictCapture.Captured captured=expansion==null
                        ?new dev.starworld.integration.BorderConflictCapture.Captured(Optional.empty(),List.of())
                        :expansion.captureBorderEvidence(current,view,valid);
                Map<String,dev.starworld.climate.ClimateRules.WeatherSnapshot> weather=expansion==null
                        ?Map.of():expansion.captureVerifiedClimate(current,view,valid);
                CompletableFuture<Settlement> settlement=async(()->expansion==null
                        ?simulation.settle(current,period,valid,observed)
                        :expansion.settle(current,period,valid,captured.nation(),captured.witnesses(),weather,observed,simulation));
                jobs.add(settlement.thenCompose(result->{
                    if(result.applied()){
                        scheduler.global(()->Bukkit.getPluginManager().callEvent(new IndustrySettledEvent(result)));
                        if(expansion!=null)expansion.afterSettlement(view.id());
                    }
                    return city(view.id());
                }).thenAccept(updated->updated.ifPresent(next->{
                    if(!Objects.equals(view.event(),next.event()))
                        scheduler.global(()->Bukkit.getPluginManager().callEvent(new WorldEvolutionEvent(view.id(),view.event(),next.event())));
                })));
            }catch(RuntimeException error){firstFailure.compareAndSet(null,error);}
        }
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).handle((ignored,error)->{
            if(error!=null)firstFailure.compareAndSet(null,root(error));return null;
        }).thenCompose(ignored->scheduler.globalFuture(()->scheduleSettlementBatch(views,currentCities,period,end,firstFailure))
                .thenCompose(next->next));
    }
    private boolean valid(Building b){
        if(!core.ownsSite(b.cityId(),b.site()))return false;Definitions.BuildingType type=definitions.buildings().get(b.type());if(type==null)return false;
        World world=Bukkit.getWorld(b.site().worldId());if(world==null || !world.isChunkLoaded(b.site().x()>>4,b.site().z()>>4))return false;
        return world.getBlockAt(b.site().x(),b.site().y(),b.site().z()).getType()==Material.matchMaterial(type.anchor());
    }
    private void failure(Throwable e){settling.set(false);if(!closed)plugin.getLogger().severe("StarWorld tick failed: "+root(e));}
    @Override public synchronized void close()throws Exception{
        if(terminated)return;
        closed=true;
        scheduler.close();
        Exception cleanupFailure=null;
        try{if(expansion!=null)expansion.close();}catch(Exception failure){cleanupFailure=failure;}
        try{
            if(!io.shutdown(10,5,TimeUnit.SECONDS))throw new IllegalStateException("Storage worker did not terminate; database deliberately left open");
            store.close();terminated=true;
        }catch(Exception failure){if(cleanupFailure!=null)failure.addSuppressed(cleanupFailure);throw failure;}
        if(cleanupFailure!=null)throw cleanupFailure;
    }
}

package dev.starworld.runtime;

import dev.starworld.api.event.CityDevelopmentEvent;
import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.disaster.*;
import dev.starworld.works.*;
import dev.starworld.civic.*;
import dev.starworld.evolution.*;
import dev.starworld.evolution.EvolutionState.*;
import dev.starworld.integration.*;
import dev.starworld.integration.CoreExtensionPort.*;
import dev.starworld.life.*;
import dev.starworld.life.LifeState.*;
import dev.starworld.logistics.*;
import dev.starworld.persistence.WorldStore;
import dev.starworld.persistence.LedgerMaintenance;
import dev.starworld.market.*;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires pure extension modules to explicit Paper thread captures and the single storage worker. */
public final class ExpansionRuntime {
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;
    private final WorldStore store;private final SchedulerGate scheduler;private final CustomItems items;
    private final EvolutionService evolution;private final ExportDefinitions exports;
    private final NationalEvolution national;
    private final RegionAtlas atlas;private final LedgerMaintenance maintenance;
    private final MarketService market;private final MarketController marketplace;
    private final DisasterResponseService disasters;private final DisasterController disasterGovernance;
    private final PublicWorksService works;private final PublicWorksController publicWorks;
    private final CivicNeedsService civic;private final CivicNeedsController civicNeeds;
    private long maintenanceTick;private volatile LedgerMaintenance.Result lastMaintenance;
    private final LogisticsService logistics;private final LogisticsController transport;
    private final LifeService life;private final LifePlatform lifePlatform;private final LifeController lifestyle;
    private final ResourceOutbox outbox;private final CoreExtensionPort extensions;
    private final BorderConflictCapture borderCapture;
    private final StarCoreCombatContributor combat;
    private final AtomicBoolean ticking=new AtomicBoolean();private int decorationCursor;
    private final Set<String> itemIds;
    public ExpansionRuntime(JavaPlugin plugin,WorldRuntime runtime,Definitions definitions,CoreBridge core,CustomItems items,WorldStore store,File folder)throws Exception {
        this.plugin=plugin;this.runtime=runtime;this.core=core;this.items=items;this.store=store;this.scheduler=new SchedulerGate(plugin);
        EvolutionDefinitions options=EvolutionDefinitions.load(new File(folder,"evolution.yml"),definitions);exports=ExportDefinitions.load(new File(folder,"exports.yml"));
        LifeDefinitions.writeDefaults(new File(folder,"life.yml"));LifeDefinitions lifeDefinitions=LifeDefinitions.load(new File(folder,"life.yml"));
        YamlConfiguration transportConfig=new YamlConfiguration();transportConfig.load(new File(folder,"logistics.yml"));
        evolution=new EvolutionService(store,definitions,options,CultureDynamics.Settings.load(new File(folder,"evolution.yml")));logistics=new LogisticsService(store,LogisticsConfig.load(transportConfig));
        PublicWorksDefinitions.writeDefaults(new File(folder,"public-works.yml"));works=new PublicWorksService(store,PublicWorksDefinitions.load(new File(folder,"public-works.yml"),definitions,options));publicWorks=new PublicWorksController(plugin,runtime,core,works);
        atlas=new RegionAtlas(store,definitions);YamlConfiguration mainConfig=new YamlConfiguration();mainConfig.load(new File(folder,"config.yml"));maintenance=new LedgerMaintenance(store,LedgerMaintenance.Settings.load(mainConfig));
        national=new NationalEvolution(store,definitions,new File(folder,"evolution.yml"));
        life=new LifeService(store,lifeDefinitions);life.initialize();lifePlatform=new LifePlatform(plugin,core,items,lifeDefinitions);
        CivicNeedsDefinitions.writeDefaults(new File(folder,"civic-needs.yml"));civic=new CivicNeedsService(store,CivicNeedsDefinitions.load(new File(folder,"civic-needs.yml")));civic.initialize();civicNeeds=new CivicNeedsController(plugin,runtime,core,civic,this::civicUnavailable);
        lifestyle=new LifeController(plugin,runtime,core,items,life,lifePlatform);outbox=new ResourceOutbox(store);extensions=new StarCoreExtensions(plugin);borderCapture=new BorderConflictCapture(plugin,core,extensions);
        YamlConfiguration evolutionConfig=new YamlConfiguration();evolutionConfig.load(new File(folder,"evolution.yml"));combat=new StarCoreCombatContributor(plugin,core,runtime::currentPeriod,StarCoreCombatContributor.Settings.load(evolutionConfig));
        transport=new LogisticsController(plugin,runtime,core,extensions,logistics);
        MarketDefinitions.writeDefaults(new File(folder,"market.yml"));market=new MarketService(store,logistics,MarketDefinitions.load(new File(folder,"market.yml")));marketplace=new MarketController(plugin,runtime,core,extensions,logistics,market,new StarCoreTreasuryTransfers(plugin));
        DisasterResponseDefinitions.writeDefaults(new File(folder,"disaster-response.yml"));DisasterResponseDefinitions disasterDefinitions=DisasterResponseDefinitions.load(new File(folder,"disaster-response.yml"),definitions);disasters=new DisasterResponseService(store,disasterDefinitions);disasterGovernance=new DisasterController(plugin,runtime,core,disasters);
        Set<String> ids=new HashSet<>(lifeDefinitions.itemIds());exports.contracts().values().forEach(c->ids.addAll(c.costs().keySet()));
        logistics.definitions().nodes().values().forEach(n->{ids.addAll(n.construction().keySet());ids.addAll(n.maintenance().keySet());});ids.addAll(market.definitions().items());ids.addAll(disasterDefinitions.itemIds());ids.addAll(works.configuration().itemIds());ids.addAll(civic.definitions().itemIds());itemIds=Set.copyOf(ids);items.validate(itemIds);
        for(var c:exports.contracts().values())if(!definitions.buildings().containsKey(c.facility()))throw new IllegalArgumentException("Unknown export facility "+c.facility());
    }
    public EvolutionService evolution(){return evolution;}
    public NationalEvolution national(){return national;}
    public RegionAtlas atlas(){return atlas;}
    public LedgerMaintenance maintenance(){return maintenance;}
    public MarketService market(){return market;}
    public DisasterResponseService disasters(){return disasters;}
    public PublicWorksService works(){return works;}
    public CivicNeedsService civic(){return civic;}
    /** Call only on Paper's owning thread; reports current item/API readiness without allocating a request slot. */
    public Optional<String> civicUnavailable(String type){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("城市需求能力必须在Paper主线程检查");
        return civicUnavailable(civic.definitions(),life.definitions(),type,id->items.create(id,1)!=null,lifePlatform.decorationEvidenceAvailable(),this::civicFurnitureLoaded);
    }
    /** Exact public CE definition lookup; no world/entity access and no assumptions from plugin presence. */
    private boolean civicFurnitureLoaded(String id){
        var ce=plugin.getServer().getPluginManager().getPlugin("CraftEngine");if(ce==null||!ce.isEnabled())return false;
        try{ClassLoader loader=ce.getClass().getClassLoader();Class<?> key=Class.forName("net.momirealms.craftengine.core.util.Key",true,loader),furniture=Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture",true,loader);
            return furniture.getMethod("byId",key).invoke(null,key.getMethod("of",String.class).invoke(null,id))!=null;
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return false;}
    }
    static Optional<String> civicUnavailable(CivicNeedsDefinitions definitions,LifeDefinitions lifeDefinitions,String type,java.util.function.Predicate<String> loadedItem,boolean furnitureApiAvailable,java.util.function.Predicate<String> loadedFurniture){
        Objects.requireNonNull(definitions);Objects.requireNonNull(lifeDefinitions);Objects.requireNonNull(loadedItem);Objects.requireNonNull(loadedFurniture);
        var need=definitions.needs().get(type);if(need==null)return Optional.of("未知城市需求配置");
        List<String> missing=need.costs().keySet().stream().filter(id->!loadedItem.test(id)).sorted().toList();if(!missing.isEmpty())return Optional.of("所需物品未加载："+missing+"；请安装兼容的物品/资源包后重试");
        return switch(need.actionKind()){
            case "decoration"->{
                var rules=lifeDefinitions.decorations();
                if(!furnitureApiAvailable)yield Optional.of("CraftEngine 公共家具查询/瞄准 API 不可用；暂不发起公共空间，已存在需求、真实凭据和历史保留");
                if(!lifeDefinitions.professions().containsKey(rules.profession()))yield Optional.of("家具维护职业不可选择；暂不发起公共空间");
                if(rules.furnitureIds().stream().noneMatch(loadedFurniture))yield Optional.of("life.yml 允许的真实家具定义均未加载；请安装对应内容包后再发起公共空间");
                Set<String> inputs=new HashSet<>(rules.registrationCost().keySet());inputs.addAll(rules.upkeep().keySet());
                List<String> absent=inputs.stream().filter(id->!loadedItem.test(id)).sorted().toList();
                yield absent.isEmpty()?Optional.empty():Optional.of("家具登记/维护物资未加载："+absent+"；暂不发起公共空间");
            }
            case "feast"->lifeDefinitions.feasts().values().stream().anyMatch(feast->lifeDefinitions.professions().containsKey(feast.profession())&&feast.inputs().keySet().stream().allMatch(loadedItem))?Optional.empty():Optional.of("没有完整加载且职业可选择的生活宴会配方；暂不发起社区聚餐");
            case "concert"->{
                var rules=lifeDefinitions.concerts();
                if(!lifeDefinitions.professions().containsKey(rules.profession()))yield Optional.of("演奏职业不可选择；暂不发起城市节庆");
                List<String> absent=rules.inputs().keySet().stream().filter(id->!loadedItem.test(id)).sorted().toList();
                if(!absent.isEmpty())yield Optional.of("演奏补给未加载："+absent+"；暂不发起城市节庆");
                yield rules.instruments().stream().anyMatch(loadedItem)?Optional.empty():Optional.of("原版音符盒或配置乐器均未加载；暂不发起城市节庆");
            }
            default->Optional.of("没有可用的真实生活凭据生产链");
        };
    }
    public void startPublicWorks(Player player,CoreCity city,String definition,UUID requestId,java.util.function.Consumer<dev.starworld.works.PublicWorksState.Project> completed){publicWorks.startFromPreview(player,city,definition,requestId,completed);}
    public void requestCivicNeed(Player player,CoreCity city,String type,UUID requestId,java.util.function.Consumer<dev.starworld.civic.CivicNeedsState.Request> completed){civicNeeds.requestFromPreview(player,city,type,requestId,completed);}
    public void completeCivicNeed(Player player,CoreCity city,UUID requestId,java.util.function.Consumer<dev.starworld.civic.CivicNeedsState.Receipt> completed){civicNeeds.completeFromPreview(player,city,requestId,completed);}
    public void respondToDisaster(org.bukkit.entity.Player player,CoreCity city,String strategy,UUID requestId,EventState previewEvent,java.util.function.Consumer<dev.starworld.disaster.DisasterState.Response> completed){disasterGovernance.respondFromPreview(player,city,strategy,requestId,previewEvent,completed);}
    public LogisticsService logistics(){return logistics;}
    public LifeService life(){return life;}
    public Set<String> itemIds(){return itemIds;}
    public void start(){combat.register();}
    public void close(){marketplace.close();combat.close();}
    public Map<String,String> status(){Map<String,String> status=new LinkedHashMap<>(lifePlatform.status());status.put("Logistics","节点、托管、改道确认、运力瓶颈与战争封锁已启用");status.put("Market",marketplace.status());status.put("Disaster governance","实物救灾、次期缓解与有界地区恢复已启用；/sw disaster options");status.put("Public works","起建、逐期施工、维护与下期运行已启用；/sw governance");status.put("Civic needs","社区聚餐/受控演奏需真实已提交凭据；公共空间需不同真实家具维护，CE家具API不可用时暂停该需求，登记不代替维护");status.put("Core exports","幂等国家资源事务 + 持久 outbox");status.put("Core combat",combat.status());return Map.copyOf(status);}
    public Optional<NationSnapshot> nation(UUID id){return extensions.snapshot(id,Set.of());}
    /** The same main-thread live capture used by normal settlement; caller-supplied IDs cannot manufacture valid anchors. */
    public BorderConflictCapture.Captured captureBorderEvidence(CoreCity current,CityView registered,Set<UUID> verified){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("边境证据必须在Paper主线程读取");
        Set<UUID> actual=new HashSet<>(runtime.verifiedBuildings(registered));actual.retainAll(verified);
        return borderCapture.capture(current,registered,Set.copyOf(actual),runtime.currentPeriod());
    }
    public Settlement settle(CoreCity core,long period,Set<UUID> valid,Optional<NationSnapshot> nation,Simulation simulation)throws SQLException {
        return settle(core,period,valid,nation,List.of(),simulation);
    }
    public Settlement settle(CoreCity core,long period,Set<UUID> valid,Optional<NationSnapshot> nation,List<BorderWitness> borders,Simulation simulation)throws SQLException {
        return works.atomicSettlement(s->{s.ensureCity(core);CityView city=s.view(core.cityId()).orElseThrow();
            if(city.lastPeriod()>=period||s.settled(city.id(),period))return simulation.settle(core,period,valid);
            return simulation.settle(core,period,valid,cycle(core,city,period,valid,nation,borders));
        });
    }
    public Simulation.Hooks cycle(CoreCity core,CityView city,long period,Set<UUID> valid,Optional<NationSnapshot> nation)throws SQLException {
        return cycle(core,city,period,valid,nation,List.of());
    }
    public Simulation.Hooks cycle(CoreCity core,CityView city,long period,Set<UUID> valid,Optional<NationSnapshot> nation,List<BorderWitness> borders)throws SQLException {
        PublicWorksService.Cycle worksCycle=works.cycle(core,city,period,valid);
        List<String> tradeActions=store.query("SELECT r.order_id FROM logistics_receipt r JOIN logistics_order o ON r.order_id=o.id LEFT JOIN evolution_trade_action a ON a.city=? AND a.receipt=r.order_id WHERE ((o.origin_city=? AND o.origin_nation=?) OR (o.target_city=? AND o.target_nation=?)) AND r.period<=? AND a.receipt IS NULL ORDER BY r.sequence LIMIT 10",r->r.getString(1),city.id(),city.id(),city.nationId(),city.id(),city.nationId(),period);
        for(String receipt:tradeActions)store.execute("INSERT INTO evolution_trade_action(city,receipt,period) VALUES(?,?,?)",city.id(),receipt,period);
        long trades=tradeActions.size();
        Context context=new Context(nation.map(NationSnapshot::weather).orElse("unknown"),nation.map(n->n.epoch()==null?"unknown":Long.toString(n.epoch().number())).orElse("unknown"),nation.map(n->!n.warOpponents().isEmpty()).orElse(false),!borders.isEmpty(),trades,life.drainEvolutionCulture(city.id(),period),life.productionMultiplier(city.id(),period),borders);
        Simulation.Hooks cityHooks=evolution.cycle(core,city,period,valid,context,nation.map(NationSnapshot::policy).orElse(""));Map<String,Double> nationalMultipliers=national.multipliers(core.nationId());DisasterResponseService.Cycle responseCycle=disasters.cycle(core,city,period,valid);
        return new Simulation.Hooks(){
            @Override public double multiplier(Building b)throws SQLException{return Math.min(10,cityHooks.multiplier(b)*nationalMultipliers.getOrDefault(b.type(),1.0)*worksCycle.multiplier(b));}
            @Override public long capacity(String item){return cityHooks.capacity(item);}
            @Override public boolean filterEvents(){return cityHooks.filterEvents();}
            @Override public Map<String,Double> eventWeights(CityView c,long p)throws SQLException{return cityHooks.eventWeights(c,p);}
            @Override public double eventFactor(Building b,EventState event,long p,double base)throws SQLException{return responseCycle.eventFactor(event,cityHooks.eventFactor(b,event,p,base));}
            @Override public void completed(CoreCity c,CityView previous,long p,Map<String,Long> produced,Set<UUID> maintained)throws SQLException{cityHooks.completed(c,previous,p,produced,maintained);responseCycle.completed();worksCycle.completed(maintained);}
        };
    }
    private record Development(Profile profile,CityView city,Optional<Readiness> readiness){}
    public void afterSettlement(UUID city){runtime.async(()->new Development(evolution.profile(city),store.view(city).orElseThrow(),evolution.readiness(city))).thenAccept(snapshot->scheduler.global(()->{
        Optional<CoreCity> current=core.city(city);if(current.isEmpty()||!current.get().nationId().equals(snapshot.city().nationId())){combat.remove(city);return;}
        Set<UUID> valid=runtime.verifiedBuildings(snapshot.city());List<Site> sites=snapshot.city().buildings().stream().filter(b->valid.contains(b.id())).map(Building::site).toList();
        snapshot.readiness().filter(r->r.required()>0).ifPresentOrElse(r->combat.update(r,sites),()->combat.remove(city));Bukkit.getPluginManager().callEvent(new CityDevelopmentEvent(snapshot.profile()));
    }));}
    public void tick(){
        if(!ticking.compareAndSet(false,true))return;
        CompletableFuture<?> trades=transport.tickFuture();
        CompletableFuture<?> resources=runtime.async(()->outbox.leaseDue(10,System.currentTimeMillis())).thenCompose(dispatches->{CompletableFuture<Void> done=new CompletableFuture<>();scheduler.global(()->{
            try{List<CompletableFuture<?>> jobs=new ArrayList<>();for(var dispatch:dispatches){Delivery result=extensions.deliver(dispatch.entry().request());jobs.add(runtime.async(()->outbox.finish(dispatch,result,System.currentTimeMillis())));}CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).whenComplete((v,e)->{if(e==null)done.complete(null);else done.completeExceptionally(e);});}catch(Exception e){done.completeExceptionally(e);}
        });return done;});
        CompletableFuture<?> decorations=settleDecorations();
        CompletableFuture<?> payments=marketplace.tickFuture();
        CompletableFuture<?> storage=++maintenanceTick%30==0?runtime.async(()->lastMaintenance=maintenance.sweep(runtime.currentPeriod(),System.currentTimeMillis())):CompletableFuture.completedFuture(null);
        CompletableFuture.allOf(trades,resources,decorations,storage,payments).thenCompose(v->spreadCulture()).whenComplete((v,e)->{ticking.set(false);if(e!=null)plugin.getLogger().warning("Extension tick failed; durable work retained: "+root(e));});
    }
    private record Exchanges(List<dev.starworld.logistics.LogisticsState.TradeReceipt> receipts,Map<UUID,CityView> cities){}
    private CompletableFuture<Void> spreadCulture(){
        return runtime.async(()->{long cursor=store.query("SELECT value FROM extension_meta WHERE key='culture-trade-cursor'",r->Long.parseLong(r.getString(1))).stream().findFirst().orElse(0L);var receipts=logistics.completedAfter(cursor,20);Map<UUID,CityView> cities=new HashMap<>();for(var r:receipts)for(UUID id:List.of(r.originCity(),r.targetCity()))if(!cities.containsKey(id))store.view(id).ifPresent(c->cities.put(id,c));return new Exchanges(receipts,Map.copyOf(cities));}).thenCompose(batch->{CompletableFuture<Void> done=new CompletableFuture<>();scheduler.global(()->{
            try{Map<UUID,Set<String>> regions=new HashMap<>();Map<UUID,UUID> nations=new HashMap<>();for(var view:batch.cities().values()){Optional<CoreCity> city=core.city(view.id());if(city.isEmpty()||!city.get().nationId().equals(view.nationId()))continue;Set<UUID> valid=runtime.verifiedBuildings(view);Set<String> keys=new HashSet<>();for(Building b:view.buildings())if(valid.contains(b.id()))keys.add(b.site().regionKey());regions.put(view.id(),Set.copyOf(keys));nations.put(view.id(),city.get().nationId());}
                runtime.async(()->store.tx(s->{for(var receipt:batch.receipts()){
                    if(receipt.originNation().equals(nations.get(receipt.originCity()))&&receipt.targetNation().equals(nations.get(receipt.targetCity())))evolution.exchange(receipt.orderId().toString(),receipt.originCity(),receipt.targetCity(),receipt.period(),regions.getOrDefault(receipt.originCity(),Set.of()),regions.getOrDefault(receipt.targetCity(),Set.of()));
                    s.execute("INSERT INTO extension_meta(key,value) VALUES('culture-trade-cursor',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",Long.toString(receipt.sequence()));
                }return true;})).whenComplete((v,e)->{if(e==null)done.complete(null);else done.completeExceptionally(e);});
            }catch(Exception e){done.completeExceptionally(e);}
        });return done;});
    }
    private record Decorations(UUID city,List<Decoration> decorations){}
    private CompletableFuture<Void> settleDecorations(){
        return runtime.async(()->{List<UUID> cities=store.cities(20,decorationCursor);if(cities.isEmpty()){decorationCursor=0;cities=store.cities(20,0);}decorationCursor+=cities.size();List<Decorations> batch=new ArrayList<>();for(UUID city:cities)batch.add(new Decorations(city,life.decorations(city)));return batch;}).thenCompose(batch->{CompletableFuture<Void> result=new CompletableFuture<>();scheduler.global(()->{
            try{List<CompletableFuture<?>> jobs=new ArrayList<>();long period=runtime.currentPeriod();for(var row:batch){Optional<CoreCity> city=core.city(row.city());if(city.isEmpty())continue;Set<UUID> valid=new HashSet<>();for(Decoration d:row.decorations())if(d.nation().equals(city.get().nationId())&&core.ownsSite(row.city(),d.site())&&lifePlatform.validateDecoration(d))valid.add(d.furnitureId());jobs.add(runtime.async(()->life.settleDecorations(city.get(),period,valid)));}CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).whenComplete((v,e)->{if(e==null)result.complete(null);else result.completeExceptionally(e);});}catch(Exception e){result.completeExceptionally(e);}
        });return result;});
    }
    public boolean command(Player p,String[] args){
        if(lifestyle.execute(p,args)||transport.execute(p,args)||marketplace.execute(p,args)||disasterGovernance.execute(p,args)||publicWorks.execute(p,args)||civicNeeds.execute(p,args))return true;
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("maintenance")){if(!p.hasPermission("starworld.admin"))throw new IllegalArgumentException("需要管理员权限");if(args.length>=2&&args[1].equalsIgnoreCase("backup")){require(args,3);runtime.reply(p,runtime.backup(args[2]),backup->p.sendMessage("§a在线备份已完成："+backup.path().getFileName()+" "+backup.bytes()+" bytes；schema "+backup.schemaVersion()+"；SHA "+backup.sha256()));return true;}if(args.length!=1)throw new IllegalArgumentException("/sw maintenance backup <文件名> 或 /sw maintenance");runtime.reply(p,runtime.async(()->maintenance.archives(0,20)),rows->{p.sendMessage("§e最近存档维护："+lastMaintenance);for(var row:rows)p.sendMessage("§7审计归档 "+row.id()+"："+row.rows()+" 条，"+row.bytes()+" bytes；SHA "+row.sha256());});return true;}
        if(!Set.of("development","plan","specialty","history","culture","export","outbox","nation","nation-development","national-specialty","regions").contains(sub))return false;
        CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));
        switch(sub){
            case "development"->{runtime.reply(p,runtime.open(city).thenCompose(v->runtime.async(()->evolution.profile(city.cityId()))),profile->p.sendMessage("§e城市发展："+profile+"\n§e规划："+evolution.options().plans().keySet()+"；专长："+evolution.options().specialties().keySet()));}
            case "plan"->{require(args,2);manager(p,city);runtime.reply(p,runtime.async(()->evolution.plan(city,args[1],runtime.currentPeriod(),p.getUniqueId().toString())),v->p.sendMessage("§a城市规划："+v.plan()));}
            case "specialty"->{require(args,2);manager(p,city);runtime.reply(p,runtime.open(city),view->{CoreCity fresh=runtime.playerCity(p).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).orElseThrow(()->new IllegalArgumentException("城市归属已变化"));manager(p,fresh);Set<UUID> valid=runtime.verifiedBuildings(view);runtime.reply(p,runtime.async(()->evolution.specialize(fresh,args[1],runtime.currentPeriod(),valid,p.getUniqueId().toString())),v->p.sendMessage("§a城市专长："+v.specialty()));});}
            case "history"->runtime.reply(p,runtime.async(()->evolution.history(city.cityId())),rows->rows.forEach(h->p.sendMessage("§e"+h.period()+" "+h.kind()+" §f"+h.detail())));
            case "culture"->runtime.reply(p,runtime.async(()->evolution.cultures(city.cityId())),rows->rows.forEach(c->p.sendMessage("§e"+c.region()+" §f"+c.tradition()+": "+c.influence())));
            case "regions"->runtime.reply(p,runtime.open(city).thenCompose(v->runtime.async(()->atlas.summary(city.cityId()))),summary->p.sendMessage("§e已登记地区："+summary.regions()+"；地形："+summary.terrains()+"\n§e剩余储量："+summary.reserves()+"；初始容量："+summary.initialCapacities()+"；每期恢复："+summary.regeneration()+"\n§7/sw atlas 查看地图册；数据来自最近有效城市复核，不探测未登记世界"));
            case "nation"->p.sendMessage("§eStarCore 公共快照："+nation(city.nationId()).map(Object::toString).orElse("不可用"));
            case "nation-development"->runtime.reply(p,runtime.open(city).thenCompose(v->runtime.async(()->national.view(city.nationId()))),v->p.sendMessage("§e国家发展："+v+"\n§e国家专长："+national.specialties()));
            case "national-specialty"->{require(args,2);if(!p.hasPermission("starworld.admin")&&!core.canGovern(p.getUniqueId(),city.cityId()))throw new IllegalArgumentException("国家专长需要国家创始人权限");runtime.reply(p,runtime.open(city),v->{CoreCity fresh=runtime.playerCity(p).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).orElseThrow(()->new IllegalArgumentException("城市归属已变化"));if(!p.hasPermission("starworld.admin")&&!core.canGovern(p.getUniqueId(),fresh.cityId()))throw new IllegalArgumentException("国家治理权限已变化");runtime.reply(p,runtime.async(()->national.choose(fresh.nationId(),args[1],runtime.currentPeriod(),p.getUniqueId().toString())),profile->p.sendMessage("§a国家专长："+profile.specialty()));});}
            case "export"->{require(args,3);manager(p,city);ExportDefinitions.Contract contract=exports.contracts().get(args[1]);if(contract==null)throw new IllegalArgumentException("出口契约："+exports.contracts().keySet());int batches=Integer.parseInt(args[2]);Map<String,Long> costs=contract.cost(batches),output=contract.output(batches);String business="player:"+p.getUniqueId()+":"+UUID.randomUUID();runtime.reply(p,runtime.open(city),view->{
                manager(p,city);if(runtime.playerCity(p).map(c->!c.cityId().equals(city.cityId())||!c.nationId().equals(city.nationId())).orElse(true))throw new IllegalArgumentException("城市归属已变化");Set<UUID> valid=runtime.verifiedBuildings(view);
                if(view.buildings().stream().noneMatch(b->b.type().equals(contract.facility())&&valid.contains(b.id())))throw new IllegalArgumentException("需要已验证的 "+contract.facility());
                runtime.reply(p,runtime.async(()->outbox.enqueue(business,city.cityId(),city.nationId(),costs,output,p.getUniqueId().toString())),entry->p.sendMessage("§a国家资源供给已托管："+entry.transactionId()+"；/sw outbox 查看回执"));
            });}
            case "outbox"->{if(args.length==1)runtime.reply(p,runtime.async(()->outbox.cityEntries(city.cityId(),100)),rows->rows.forEach(e->p.sendMessage("§e"+e.transactionId()+" "+e.state()+" "+e.lastError())));else{if(!p.hasPermission("starworld.admin"))throw new IllegalArgumentException("需要管理员权限");require(args,3);if(!Set.of("retry","cancel").contains(args[1]))throw new IllegalArgumentException("outbox retry|cancel <txid>");runtime.reply(p,runtime.async(()->{if(args[1].equals("retry"))outbox.retry(args[2],p.getUniqueId().toString());else outbox.cancelUnsent(args[2],p.getUniqueId().toString());return true;}),v->p.sendMessage("§a已记录处理决定"));}}
            default->throw new IllegalStateException();
        }return true;
    }
    private void manager(Player p,CoreCity city){if(!runtime.manage(p,city.cityId()))throw new IllegalArgumentException("需要城市管理权限");}
    private static void require(String[] args,int length){if(args.length!=length)throw new IllegalArgumentException("参数错误，请 /sw help");}
    private static Throwable root(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
}

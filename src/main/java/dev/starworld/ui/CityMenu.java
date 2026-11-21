package dev.starworld.ui;

import dev.starworld.domain.WorldState.*;
import dev.starworld.domain.Definitions;
import dev.starworld.disaster.DisasterState.*;
import dev.starworld.runtime.WorldRuntime;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

/** Every menu click is cancelled, and only exact top inventory slots dispatch actions. */
public final class CityMenu implements Listener {
    private final WorldRuntime runtime;
    private final String title;
    public CityMenu(WorldRuntime runtime,String title){this.runtime=runtime;this.title=title;}
    private static final class Holder implements InventoryHolder {
        private Inventory inventory; private final UUID viewer,city;private UUID nation;
        private final Map<Integer,String[]> commands=new HashMap<>();private boolean development,atlas,market,disaster,climate;private String nextCursor="";private int marketOffset,disasterOffset,disasterLimit;private String disasterTab="strategies";private CoreCity disasterCity;private EventState disasterEvent;private UUID responseRequest;private String responseStrategy;
        Holder(UUID viewer,UUID city){this.viewer=viewer;this.city=city;}
        @Override public Inventory getInventory(){return inventory;}
    }
    public void open(Player p){
        CoreCity core=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先在 StarCore 加入城市；管理员可用 /sw status 查看集成状态"));
        runtime.reply(p,runtime.open(core),city->{
            if(runtime.playerCity(p).map(c->!c.cityId().equals(city.id())).orElse(true))return;
            Holder holder=new Holder(p.getUniqueId(),city.id());holder.inventory=Bukkit.createInventory(holder,54,Component.text(title));Inventory inv=holder.inventory;
            inv.setItem(4,item(Material.BEACON,city.name(),List.of("国家："+core.nationName(),"文明："+city.civilization(),"国家修正："+String.format(Locale.ROOT,"%.2f",core.productionMultiplier()),core.detail(),"最近结算："+city.lastPeriod())));
            inv.setItem(10,item(Material.COMPOSTER,"登记农场",List.of("手持视线对准堆肥桶，再点击", "每个周期：小麦 → 面包", "地形肥力与文明影响产量")));
            inv.setItem(12,item(Material.STONECUTTER,"登记林场",List.of("对准切石机，再点击", "消耗面包维护；原木 → 木板")));
            inv.setItem(14,item(Material.BLAST_FURNACE,"登记矿业工坊",List.of("对准高炉，再点击", "消耗面包维护；粗铁 → 铁锭 → 箭")));
            inv.setItem(16,item(Material.WRITABLE_BOOK,"文明转型",List.of("/sw civilization <id>", "可选："+String.join(", ",runtime.definitions().civilizations().keySet()),"影响本国所有已登记城市；冷却六周期")));
            int slot=19;for(var e:city.stock().entrySet()){if(slot>34)break;Material material=Material.matchMaterial(e.getKey());if(material==null || !material.isItem())material=Material.CHEST;
                inv.setItem(slot++,item(material,e.getKey(),List.of("仓储："+e.getValue(),"/sw withdraw "+e.getKey()+" <数量>","/sw deposit "+e.getKey()+" <数量>")));}
            inv.setItem(36,item(Material.CHEST,"设施清单（点击聊天输出）",List.of("已登记："+city.buildings().size(),"升级 /sw upgrade <设施UUID>","注销 /sw remove <设施UUID>")));
            EventState e=city.event();inv.setItem(40,item(Material.CLOCK,"世界事件",e==null ? List.of("当前无事件"):List.of("类型："+e.type(),"生效至周期："+e.endPeriod(),"余波至周期："+e.aftermathEndPeriod())));
            inv.setItem(44,item(Material.PAPER,"最新结算",wrap(city.lastReport())));
            if(runtime.expansion().isPresent())inv.setItem(45,item(Material.MAP,"城市规划与文明世界",List.of("设施、规划、专长、文化与交通","点击打开发展面板")));
            if(runtime.expansion().isPresent())inv.setItem(46,item(Material.WATER_BUCKET,"灾害治理",List.of("预览策略、真实成本与所需设施","提交救灾响应、查看恢复状态与历史")));
            if(runtime.expansion().isPresent())inv.setItem(47,item(Material.BRICKS,"城市治理与公共工程",List.of("工程选择、真实施工维护与城市需求","点击打开独立治理面板")));
            if(runtime.expansion().isPresent())inv.setItem(48,item(Material.COMPASS,"世界气候与产业",List.of("按设施所在世界读取StarCore天气","查看国家控制权和当前配方倍率","点击查看已验证设施天气影响")));
            inv.setItem(49,item(Material.SUNFLOWER,"刷新",List.of("仅已加载且领土有效的设施生产","不加载区块，不补发离线产量")));
            p.openInventory(inv);
        });
    }
    public void openDevelopment(Player p){
        CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入城市"));var expansion=runtime.expansion().orElseThrow(()->new IllegalArgumentException("扩展模块未启用"));
        runtime.reply(p,runtime.open(city).thenCompose(v->runtime.async(()->expansion.evolution().profile(city.cityId()))),profile->{
            if(runtime.playerCity(p).map(c->!c.cityId().equals(city.cityId())||!c.nationId().equals(city.nationId())).orElse(true))return;
            Holder h=new Holder(p.getUniqueId(),city.cityId());h.development=true;h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · 文明与城市发展"));Inventory inv=h.inventory;
            int slot=0;for(var b:runtime.definitions().buildings().values().stream().sorted(java.util.Comparator.comparing(Definitions.BuildingType::id)).toList()){if(slot>=9)break;Material m=Material.matchMaterial(b.anchor());inv.setItem(slot,item(m==null?Material.BRICKS:m,b.name(),List.of("登记锚点："+b.anchor(),"每期维护："+b.upkeep(),"点击登记；需要城市管理权限")));h.commands.put(slot++,new String[]{"register",b.id()});}
            slot=9;for(var entry:expansion.evolution().options().plans().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){if(slot>=18)break;inv.setItem(slot,item(Material.WRITABLE_BOOK,"规划："+entry.getValue().name(),List.of("当前："+profile.plan(),"倍率："+entry.getValue().multipliers(),"点击转型；冷却六周期")));h.commands.put(slot++,new String[]{"plan",entry.getKey()});}
            slot=18;for(var entry:expansion.evolution().options().specialties().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){if(slot>=27)break;var s=entry.getValue();inv.setItem(slot,item(Material.SMITHING_TABLE,"专长："+s.name(),List.of("所需设施："+s.building(),"知识："+s.knowledge()+"；国家产能："+s.coreMultiplier(),"倍率："+s.multipliers(),"点击确立；与规划共用冷却")));h.commands.put(slot++,new String[]{"specialty",entry.getKey()});}
            slot=27;for(var c:runtime.definitions().civilizations().values().stream().sorted(java.util.Comparator.comparing(Definitions.Civilization::id)).toList()){if(slot>=36)break;inv.setItem(slot,item(Material.BEACON,c.name(),List.of("国家文明："+c.id(),"倍率："+c.multipliers(),"点击转型；需要国家创始人权限")));h.commands.put(slot++,new String[]{"civilization",c.id()});}
            inv.setItem(36,item(Material.EMERALD,"城市发展",List.of("规划/专长："+profile.plan()+" / "+profile.specialty(),"城市状况："+String.format(Locale.ROOT,"%.1f",profile.prosperity()),"稳定："+String.format(Locale.ROOT,"%.1f",profile.stability()),"知识/文化："+profile.knowledge()+" / "+profile.culture(),"本期军需消耗："+profile.supply(),"点击查看完整状态")));h.commands.put(36,new String[]{"development"});
            String[] names={"生活与职业","城市货单","城市商路","地区文化","国家状态","历史记录"};Material[] icons={Material.BREAD,Material.CHEST,Material.COMPASS,Material.PAINTING,Material.NETHER_STAR,Material.BOOK};String[][] commands={{"feast"},{"trade","list"},{"route","list"},{"culture"},{"nation"},{"history"}};
            for(int i=0;i<names.length;i++){inv.setItem(37+i,item(icons[i],names[i],List.of("点击查看；更多操作 /sw help")));h.commands.put(37+i,commands[i]);}
            inv.setItem(43,item(Material.GOLDEN_HELMET,"国家倾向与专长",List.of("本国城市的真实产出、贸易、军需和文化共同积累","/sw national-specialty <ID> （国家创始人）")));h.commands.put(43,new String[]{"nation-development"});
            inv.setItem(44,item(Material.FILLED_MAP,"地区地图册",List.of("地形、资源稀缺、恢复与文化分布","查看已登记设施所在地区")));h.commands.put(44,new String[]{"atlas"});
            inv.setItem(45,item(Material.GOLD_INGOT,"实物金币市场",List.of("真实商品托管、国库付款和商路交货","整批限价挂牌与成交记录")));h.commands.put(45,new String[]{"market-menu"});
            inv.setItem(46,item(Material.WATER_BUCKET,"灾害治理",List.of("干旱、洪灾与饥荒的六项治理策略","实物救灾、次期缓解与长期恢复")));h.commands.put(46,new String[]{"disaster-menu"});
            inv.setItem(47,item(Material.BRICKS,"公共工程与城市需求",List.of("预览起建门槛与物资缺口","请求后真实生活贡献，明确确认完成")));h.commands.put(47,new String[]{"governance"});
            inv.setItem(48,item(Material.COMPASS,"世界气候与产业",List.of("StarCore逐世界天气影响农场和矿井","科技只按明确映射生效","点击查看已验证设施倍率")));h.commands.put(48,new String[]{"climate"});
            inv.setItem(49,item(Material.ARROW,"返回城市产业",List.of()));inv.setItem(53,item(Material.SUNFLOWER,"刷新发展状态",List.of()));p.openInventory(inv);
        });
    }
    public void openMarket(Player p,int offset){
        if(offset<0||offset>100000)throw new IllegalArgumentException("市场页码超限");CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入城市"));
        runtime.reply(p,runtime.marketListings(28,offset),rows->{if(runtime.playerCity(p).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).isEmpty())return;
            Holder h=new Holder(p.getUniqueId(),city.cityId());h.market=true;h.marketOffset=offset;h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · 实物金币市场"));
            int slot=0;for(var listing:rows){Material icon=Material.matchMaterial(listing.item());if(icon==null||!icon.isItem())icon=Material.CHEST;
                h.inventory.setItem(slot,item(icon,listing.item()+" ×"+listing.units(),List.of("单价："+listing.unitPrice()+"；整批："+listing.total(),"卖方城市："+listing.sellerCity(),"挂牌："+listing.id(),"点击显示购买指令；付款须国家创始人","需要由卖城通向本城的有效商路")));h.commands.put(slot++,new String[]{"market","buy",listing.id().toString()});}
            h.inventory.setItem(45,item(Material.ARROW,"上一页",List.of()));h.inventory.setItem(49,item(Material.BOOK,"返回发展面板",List.of("/sw market sell <物品ID> <数量> <单价>","/sw market orders 查看付款和物流凭据")));if(rows.size()==28)h.inventory.setItem(53,item(Material.SUNFLOWER,"下一页",List.of()));p.openInventory(h.inventory);
        });
    }
    public void openAtlas(Player p,String after){
        CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入城市"));
        runtime.reply(p,runtime.open(city).thenCompose(v->runtime.regions(city.cityId(),after,45)),result->{
            if(runtime.playerCity(p).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).isEmpty()||result.isEmpty())return;
            var page=result.get();Holder h=new Holder(p.getUniqueId(),city.cityId());h.atlas=true;h.nextCursor=page.nextCursor();h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · 地区地图册"));
            int slot=0;for(var region:page.regions()){
                Material icon=switch(region.terrain()){case "mountains"->Material.IRON_ORE;case "river"->Material.WATER_BUCKET;case "forest"->Material.OAK_LOG;case "desert"->Material.SAND;case "snow"->Material.SNOW_BLOCK;default->Material.WHEAT;};
                List<String> lore=new ArrayList<>(List.of(region.worldName()+" 区块 "+region.chunkX()+","+region.chunkZ(),"农业 / 运输 / 防御："+region.agriculture()+" / "+region.transport()+" / "+region.defense(),"剩余："+region.reserves(),"初始容量："+region.initialCapacities(),"每期恢复："+region.regeneration(),"设施："+region.facilities().stream().map(f->f.type()+" Lv"+f.level()).toList()));
                region.culture().stream().limit(6).forEach(c->lore.add("文化 "+c.tradition()+"："+c.influence()));h.inventory.setItem(slot++,item(icon,region.terrainName()+" · "+region.chunkX()+","+region.chunkZ(),lore));
            }
            h.inventory.setItem(45,item(Material.ARROW,"第一页",List.of()));h.inventory.setItem(49,item(Material.BOOK,"返回发展面板",List.of("只显示已登记地区；不扫描或加载世界","归属来自最近一次有界核心复核")));if(!h.nextCursor.isEmpty())h.inventory.setItem(53,item(Material.SUNFLOWER,"下一页",List.of()));p.openInventory(h.inventory);
        });
    }
    public void openClimate(Player p){
        CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入城市"));
        runtime.reply(p,runtime.climate(city.cityId()),optional->{if(runtime.playerCity(p).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).isEmpty())return;
            Holder h=new Holder(p.getUniqueId(),city.cityId());h.nation=city.nationId();h.climate=true;h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · 世界气候与产业"));
            h.inventory.setItem(4,item(Material.COMPASS,"本城天气产能快照",List.of("逐设施按所属世界与StarCore天气控制国核验","只有本国控制的世界天气影响对应原料链","未控制/服务缺失时倍率为1.00","查询不推进周期、不加载区块")));
            List<dev.starworld.api.StarWorldApi.ClimateExposure> exposures=optional.map(dev.starworld.api.StarWorldApi.ClimateReport::exposures).orElse(List.of());int slot=9;
            for(var exposure:exposures.stream().limit(36).toList()){
                Material icon=switch(exposure.buildingType()){case "farm"->Material.COMPOSTER;case "workshop"->Material.BLAST_FURNACE;case "sawmill"->Material.STONECUTTER;default->Material.BRICKS;};
                List<String> lore=new ArrayList<>(List.of("世界："+exposure.worldName(),"天气："+exposure.weatherId(),"控制权："+(exposure.controlledByNation()?"本国匹配":"未匹配；天气倍率中性"),"灌溉水网本期维护："+(exposure.irrigationOperating()?"已运行；缓解农作物负面天气":"未运行")));
                exposure.recipeFactors().forEach((recipe,factor)->lore.add(recipe+" ×"+String.format(Locale.ROOT,"%.2f",factor)));
                h.inventory.setItem(slot++,item(icon,exposure.buildingType()+" · "+exposure.buildingId().toString().substring(0,8),lore));
            }
            if(exposures.isEmpty())h.inventory.setItem(22,item(Material.PAPER,"没有已验证的设施天气快照",List.of("需要已登记、已加载且领土有效的设施","StarCore缺失时天气按中性倍率处理")));
            if(exposures.size()>36)h.inventory.setItem(45,item(Material.PAPER,"显示36 / "+exposures.size(),List.of("使用 /sw climate 查看全部设施")));
            h.inventory.setItem(49,item(Material.ARROW,"返回城市产业",List.of()));h.inventory.setItem(53,item(Material.SUNFLOWER,"刷新天气快照",List.of("实时重新读取当前StarCore控制权")));p.openInventory(h.inventory);
        });
    }
    public void openDisaster(Player p){
        CoreCity city=disasterCity(p);var expansion=runtime.expansion().orElseThrow();Inventory source=currentInventory(p);
        runtime.reply(p,runtime.open(city),view->{if(!canOpenDisaster(p,city,source)||!view.nationId().equals(city.nationId()))return;
            Holder h=disasterHolder(p,city,view,"strategies",0,0,"灾害治理 · 策略");int slot=0;
            for(Strategy strategy:expansion.disasters().configuration().strategies().values().stream().sorted(Comparator.comparing(Strategy::id)).toList()){
                List<String> lore=strategyLore(strategy,view);lore.add("点击预览成本与确认；此处不会扣费");h.inventory.setItem(slot,item(eventIcon(strategy.eventType()),strategyName(strategy.id()),lore));h.commands.put(slot++,new String[]{"disaster-preview",strategy.id()});
            }
            disasterFooter(h,false);p.openInventory(h.inventory);
        });
    }
    public void openDisasterPreview(Player p,String strategyId){
        CoreCity city=disasterCity(p);var expansion=runtime.expansion().orElseThrow();Strategy strategy=expansion.disasters().configuration().strategies().get(strategyId);if(strategy==null)throw new IllegalArgumentException("救灾策略已移除");Inventory source=currentInventory(p);
        runtime.reply(p,runtime.open(city),view->{if(!canOpenDisaster(p,city,source)||!view.nationId().equals(city.nationId()))return;
            Holder h=disasterHolder(p,city,view,"confirm",0,0,"灾害治理 · 确认响应");h.responseRequest=UUID.randomUUID();h.responseStrategy=strategy.id();h.disasterEvent=view.event();
            List<String> lore=strategyLore(strategy,view);lore.add("所有成本在确认成功时一次扣除");lore.add("没有立即产出；最早下一周期生效");h.inventory.setItem(13,item(eventIcon(strategy.eventType()),strategyName(strategy.id()),lore));
            List<String> costs=new ArrayList<>();strategy.costs().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(cost->costs.add(cost.getKey()+" ×"+cost.getValue()+"；仓储："+view.stock().getOrDefault(cost.getKey(),0L)));if(strategy.importItem()!=null)costs.add("还需近期已完成物流进口回执；后台再次核实");h.inventory.setItem(21,item(Material.CHEST,"真实仓储成本",costs));
            Set<UUID> valid=runtime.verifiedBuildings(view);Set<String> facilities=new HashSet<>();view.buildings().stream().filter(b->valid.contains(b.id())).forEach(b->facilities.add(b.type()));List<String> required=new ArrayList<>();strategy.facilities().stream().sorted().forEach(type->required.add((facilities.contains(type)?"已验证：":"缺少有效设施：")+facilityName(type)));h.inventory.setItem(23,item(Material.BRICKS,"必需设施",required));
            String blocked=previewBlock(p,city,view,strategy,facilities);List<String> confirm=new ArrayList<>(List.of("请求UUID："+h.responseRequest,"原事件："+Objects.toString(view.event(),"无"),"重复点击使用同一请求，不重复扣料","归属、权限、设施、库存和事件均再次复核"));
            if(blocked==null){confirm.add("点击确认提交本次响应");h.inventory.setItem(31,item(Material.LIME_DYE,"确认支付并救灾",confirm));h.commands.put(31,new String[]{"disaster-submit"});}else{confirm.add(blocked);h.inventory.setItem(31,item(Material.BARRIER,"当前无法提交",confirm));}
            disasterFooter(h,false);p.openInventory(h.inventory);
        });
    }
    public void openDisasterStatus(Player p,int offset){
        CoreCity city=disasterCity(p);int limit=disasterLimit();disasterOffset(offset);Inventory source=currentInventory(p);
        runtime.reply(p,runtime.open(city).thenCompose(view->runtime.disasterResponses(city.cityId(),limit,offset).thenApply(rows->new ResponsePage(view,rows))),page->{if(!canOpenDisaster(p,city,source)||!page.city().nationId().equals(city.nationId()))return;
            Holder h=disasterHolder(p,city,page.city(),"status",offset,limit,"灾害治理 · 响应状态");int slot=0;long period=runtime.currentPeriod();
            for(Response response:page.rows()){
                String state=period>=response.expiresPeriod()||response.steps()>=response.maxSteps()||response.remainingBudget()==0?"已结束":!response.nationId().equals(city.nationId())?"原国家响应，当前暂停":period<response.effectivePeriod()?"等待次期生效":"结算时复核设施后恢复";
                List<String> lore=new ArrayList<>(List.of("请求："+response.requestId(),"状态："+state,"事件："+response.eventType()+" / 开始 "+response.eventStart(),"生效 / 截止："+response.effectivePeriod()+" / "+response.expiresPeriod(),"恢复步骤："+response.steps()+" / "+response.maxSteps(),"最近恢复："+response.lastAppliedPeriod()+"；剩余预算："+response.remainingBudget(),"负面缓解："+response.reliefPermille()/10.0+"%","每期资源恢复："+response.recoveryPerStep(),"每期稳定 / 繁荣："+response.stabilityPerStep()+" / "+response.prosperityPerStep(),"已支付："+response.costs()));if(response.importReceipt()!=null)lore.add("进口回执："+response.importReceipt());h.inventory.setItem(slot++,item(Material.CLOCK,strategyName(response.strategyId()),lore));
            }
            if(page.rows().isEmpty())h.inventory.setItem(22,item(Material.PAPER,"本页没有响应",List.of("可在策略页预览；已有回执不会重复扣料")));disasterFooter(h,page.rows().size()==limit);p.openInventory(h.inventory);
        });
    }
    public void openDisasterHistory(Player p,int offset){
        CoreCity city=disasterCity(p);int limit=disasterLimit();disasterOffset(offset);Inventory source=currentInventory(p);
        runtime.reply(p,runtime.open(city).thenCompose(view->runtime.disasterHistory(city.cityId(),limit,offset).thenApply(rows->new HistoryPage(view,rows))),page->{if(!canOpenDisaster(p,city,source)||!page.city().nationId().equals(city.nationId()))return;
            Holder h=disasterHolder(p,city,page.city(),"history",offset,limit,"灾害治理 · 历史");int slot=0;
            for(History history:page.rows()){List<String> lore=new ArrayList<>(List.of("请求："+history.requestId(),"周期："+history.period()));lore.addAll(wrap(history.detail()));lore.add("完整条目：/sw disaster history");h.inventory.setItem(slot++,item(Material.BOOK,history.kind().equals("requested")?"救灾请求已提交":"恢复步骤已提交",lore));}
            if(page.rows().isEmpty())h.inventory.setItem(22,item(Material.PAPER,"本页没有治理历史",List.of("仅显示已提交请求与恢复记录")));disasterFooter(h,page.rows().size()==limit);p.openInventory(h.inventory);
        });
    }
    private record ResponsePage(CityView city,List<Response> rows){ResponsePage{rows=List.copyOf(rows);}}
    private record HistoryPage(CityView city,List<History> rows){HistoryPage{rows=List.copyOf(rows);}}
    private CoreCity disasterCity(Player p){if(!p.hasPermission("starworld.use"))throw new IllegalArgumentException("需要 starworld.use 权限");if(runtime.expansion().isEmpty())throw new IllegalArgumentException("扩展模块未启用");return runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入城市"));}
    private boolean sameCity(Player p,CoreCity city){return runtime.playerCity(p).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).isPresent();}
    private static Inventory currentInventory(Player p){InventoryView view=p.getOpenInventory();return view==null?null:view.getTopInventory();}
    private boolean canOpenDisaster(Player p,CoreCity city,Inventory source){return p.hasPermission("starworld.use")&&sameCity(p,city)&&currentInventory(p)==source;}
    private int disasterLimit(){return Math.min(8,runtime.expansion().orElseThrow().disasters().configuration().data().maxPage());}
    private static void disasterOffset(int offset){if(offset<0||offset>1_000_000)throw new IllegalArgumentException("治理页码超限");}
    private Holder disasterHolder(Player p,CoreCity core,CityView view,String tab,int offset,int limit,String name){Holder h=new Holder(p.getUniqueId(),core.cityId());h.nation=core.nationId();h.disaster=true;h.disasterTab=tab;h.disasterOffset=offset;h.disasterLimit=limit;h.disasterCity=core;h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · "+name));h.inventory.setItem(40,item(Material.BEACON,view.name()+" · 当前世界事件",List.of("事件："+Objects.toString(view.event(),"无"),"当前周期："+runtime.currentPeriod(),"设施失效或归属变化会暂停恢复","不补算离线周期，不恢复不可再生矿石")));return h;}
    private void disasterFooter(Holder h,boolean next){h.inventory.setItem(45,item(Material.WATER_BUCKET,"治理策略",List.of("预览治理效果和真实成本")));h.inventory.setItem(46,item(Material.CLOCK,"响应状态",List.of("查看生效、步骤与剩余预算")));h.inventory.setItem(47,item(Material.BOOK,"治理历史",List.of("查看已提交请求与恢复凭据")));h.inventory.setItem(49,item(Material.ARROW,"返回发展面板",List.of()));h.inventory.setItem(53,item(Material.SUNFLOWER,"刷新当前页面",List.of("刷新预览会生成新的请求UUID")));if(h.disasterOffset>0)h.inventory.setItem(51,item(Material.ARROW,"上一页",List.of()));if(next&&h.disasterOffset<=1_000_000-h.disasterLimit){h.inventory.setItem(52,item(Material.ARROW,"下一页",List.of()));h.commands.put(52,new String[]{"disaster-next"});}}
    private List<String> strategyLore(Strategy strategy,CityView view){List<String> lore=new ArrayList<>(List.of("策略："+strategy.id(),"适用事件："+strategy.eventType(),"当前事件："+(view.event()==null?"无":view.event().type()),"设施："+strategy.facilities().stream().sorted().map(this::facilityName).toList(),"成本："+strategy.costs(),"负面损失缓解："+strategy.reliefPermille()/10.0+"%","每期资源恢复："+strategy.recoveryPerStep(),"每期稳定 / 繁荣："+strategy.stabilityPerStep()+" / "+strategy.prosperityPerStep(),"最多 "+strategy.maxSteps()+" 步；恢复总预算 "+strategy.budget(),"次期起最多 "+strategy.durationPeriods()+" 周期"));if(strategy.importItem()!=null)lore.add("真实进口要求："+strategy.importItem()+" ×"+strategy.importUnits()+"，最近 "+strategy.receiptAgePeriods()+" 周期");return lore;}
    private String previewBlock(Player p,CoreCity city,CityView view,Strategy strategy,Set<String> facilities){if(!runtime.manage(p,city.cityId()))return "需要城市管理权限";EventState event=view.event();long period=runtime.currentPeriod();boolean aftermath=runtime.expansion().orElseThrow().disasters().configuration().data().allowAftermath();if(event==null||!event.type().equals(strategy.eventType())||period<event.startPeriod()||period>=event.aftermathEndPeriod()||(!aftermath&&period>=event.endPeriod()))return "当前没有匹配且有效的灾害事件";if(!facilities.containsAll(strategy.facilities()))return "缺少已加载、领土有效的必需设施";if(strategy.costs().entrySet().stream().anyMatch(e->view.stock().getOrDefault(e.getKey(),0L)<e.getValue()))return "城市仓储不足，请先存入救灾物资";return null;}
    private String facilityName(String id){Definitions.BuildingType type=runtime.definitions().buildings().get(id);return type==null?id:type.name()+"（"+id+"）";}
    private static Material eventIcon(String event){return switch(event){case "drought"->Material.WATER_BUCKET;case "flood"->Material.OAK_BOAT;case "famine"->Material.BREAD;default->Material.PAPER;};}
    private static String strategyName(String id){return switch(id){case "drought_irrigation"->"灌溉工程";case "drought_rationing"->"旱灾配给";case "flood_drainage"->"排水疏浚";case "flood_relocation"->"洪灾迁置";case "famine_kitchen"->"公益厨房";case "famine_import"->"粮食进口救济";default->id;};}
    private void disasterClick(Player p,Holder h,int slot){
        switch(slot){case 45->openDisaster(p);case 46->openDisasterStatus(p,0);case 47->openDisasterHistory(p,0);case 49->openDevelopment(p);case 51->{if(h.disasterOffset>0)disasterPage(p,h,Math.max(0,h.disasterOffset-h.disasterLimit));}case 52->{if(h.commands.containsKey(52))disasterPage(p,h,h.disasterOffset+h.disasterLimit);}case 53->{if(h.disasterTab.equals("confirm"))openDisasterPreview(p,h.responseStrategy);else disasterPage(p,h,h.disasterOffset);}default->{String[] command=h.commands.get(slot);if(command==null)return;if(command[0].equals("disaster-preview"))openDisasterPreview(p,command[1]);else if(command[0].equals("disaster-submit")){runtime.expansion().orElseThrow().respondToDisaster(p,h.disasterCity,h.responseStrategy,h.responseRequest,h.disasterEvent,response->{if(p.getOpenInventory().getTopInventory().getHolder()==h&&sameCity(p,h.disasterCity))openDisasterStatus(p,0);});}}}
    }
    private void disasterPage(Player p,Holder h,int offset){if(h.disasterTab.equals("status"))openDisasterStatus(p,offset);else if(h.disasterTab.equals("history"))openDisasterHistory(p,offset);else openDisaster(p);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder))return;
        event.setCancelled(true);
        if(event.getClick()!=ClickType.LEFT && event.getClick()!=ClickType.RIGHT)return;
        if(!(event.getWhoClicked() instanceof Player p) || !p.getUniqueId().equals(holder.viewer) || event.getRawSlot()<0 || event.getRawSlot()>=54)return;
        // Bukkit disallows closing/opening inventories during the click callback; defer the action one tick.
        int slot=event.getRawSlot();Bukkit.getScheduler().runTask(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(CityMenu.class),()->{
            if(!p.isOnline())return;
            if(p.getOpenInventory().getTopInventory().getHolder()!=holder)return;
            if(runtime.playerCity(p).map(c->!c.cityId().equals(holder.city)||(holder.nation!=null&&!c.nationId().equals(holder.nation))).orElse(true)){p.closeInventory();return;}
            if(holder.disaster&&!p.hasPermission("starworld.use")){p.closeInventory();return;}
            try{if(holder.disaster){disasterClick(p,holder,slot);return;}if(holder.climate){if(slot==49)open(p);else if(slot==53)openClimate(p);return;}if(holder.market){if(slot==45)openMarket(p,Math.max(0,holder.marketOffset-28));else if(slot==49)openDevelopment(p);else if(slot==53)openMarket(p,holder.marketOffset+28);else if(holder.commands.containsKey(slot)){p.closeInventory();p.sendMessage("§e/sw market buy "+holder.commands.get(slot)[2]+" <卖城至本城商路UUID>");}return;}if(holder.atlas){if(slot==45)openAtlas(p,"");else if(slot==49)openDevelopment(p);else if(slot==53&&!holder.nextCursor.isEmpty())openAtlas(p,holder.nextCursor);return;}if(holder.development){
                if(slot==49){open(p);return;}if(slot==53){openDevelopment(p);return;}String[] command=holder.commands.get(slot);if(command==null)return;p.closeInventory();
                if(command[0].equals("register"))runtime.register(p,command[1]);else if(command[0].equals("civilization"))runtime.civilization(p,command[1]);else if(command[0].equals("atlas"))openAtlas(p,"");else if(command[0].equals("market-menu"))openMarket(p,0);else if(command[0].equals("disaster-menu"))openDisaster(p);else if(command[0].equals("governance"))p.performCommand("sw governance");else if(command[0].equals("climate"))openClimate(p);else runtime.expansionCommand(p,command);return;
            }switch(slot){
                case 45 -> {if(!holder.development)openDevelopment(p);}
                case 46 -> {if(runtime.expansion().isPresent())openDisaster(p);}
                case 47 -> {if(runtime.expansion().isPresent())p.performCommand("sw governance");}
                case 48 -> {if(runtime.expansion().isPresent())openClimate(p);}
                case 10,12,14 -> {p.closeInventory();runtime.register(p,slot==10?"farm":slot==12?"sawmill":"workshop");}
                case 16 -> {p.closeInventory();p.sendMessage("§e/sw civilization <"+String.join("|",runtime.definitions().civilizations().keySet())+">");}
                case 36 -> {p.closeInventory();runtime.reply(p,runtime.city(holder.city),c->c.ifPresent(v->v.buildings().forEach(b->p.sendMessage("§e"+b.id()+" §f"+b.type()+" Lv"+b.level()+" @ "+b.site().x()+","+b.site().y()+","+b.site().z()))));}
                case 49 -> open(p);
                default -> {}
            }}catch(Exception e){p.sendMessage("§c"+e.getMessage());}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    private static ItemStack item(Material material,String name,List<String> lore){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();meta.displayName(Component.text(name));meta.lore(lore.stream().map(Component::text).toList());stack.setItemMeta(meta);return stack;}
    private static List<String> wrap(String text){if(text==null||text.isEmpty())return List.of("尚无结算");List<String> lines=new ArrayList<>();for(int i=0;i<text.length() && lines.size()<14;i+=40)lines.add(text.substring(i,Math.min(i+40,text.length())));return lines;}
}

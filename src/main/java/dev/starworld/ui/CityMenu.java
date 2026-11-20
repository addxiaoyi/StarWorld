package dev.starworld.ui;

import dev.starworld.domain.WorldState.*;
import dev.starworld.domain.Definitions;
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
        private Inventory inventory; private final UUID viewer,city;
        private final Map<Integer,String[]> commands=new HashMap<>();private boolean development,atlas,market;private String nextCursor="";private int marketOffset;
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
            if(runtime.playerCity(p).map(c->!c.cityId().equals(holder.city)).orElse(true)){p.closeInventory();return;}
            try{if(holder.market){if(slot==45)openMarket(p,Math.max(0,holder.marketOffset-28));else if(slot==49)openDevelopment(p);else if(slot==53)openMarket(p,holder.marketOffset+28);else if(holder.commands.containsKey(slot)){p.closeInventory();p.sendMessage("§e/sw market buy "+holder.commands.get(slot)[2]+" <卖城至本城商路UUID>");}return;}if(holder.atlas){if(slot==45)openAtlas(p,"");else if(slot==49)openDevelopment(p);else if(slot==53&&!holder.nextCursor.isEmpty())openAtlas(p,holder.nextCursor);return;}if(holder.development){
                if(slot==49){open(p);return;}if(slot==53){openDevelopment(p);return;}String[] command=holder.commands.get(slot);if(command==null)return;p.closeInventory();
                if(command[0].equals("register"))runtime.register(p,command[1]);else if(command[0].equals("civilization"))runtime.civilization(p,command[1]);else if(command[0].equals("atlas"))openAtlas(p,"");else if(command[0].equals("market-menu"))openMarket(p,0);else runtime.expansionCommand(p,command);return;
            }switch(slot){
                case 45 -> {if(!holder.development)openDevelopment(p);}
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

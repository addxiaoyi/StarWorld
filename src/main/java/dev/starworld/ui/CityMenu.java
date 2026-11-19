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
            inv.setItem(49,item(Material.SUNFLOWER,"刷新",List.of("仅已加载且领土有效的设施生产","不加载区块，不补发离线产量")));
            p.openInventory(inv);
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
            try{switch(slot){
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

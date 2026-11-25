package dev.starworld.ui;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.evolution.RegionalMap;
import dev.starworld.runtime.WorldRuntime;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/** Read-only map navigation; every callback rechecks viewer, city, nation and permission. */
public final class GeographyMenu implements Listener {
    private final WorldRuntime runtime;
    public GeographyMenu(WorldRuntime runtime){this.runtime=Objects.requireNonNull(runtime);}
    static final class Holder implements InventoryHolder {
        final UUID viewer,city,nation;final Map<Integer,Consumer<Player>> actions=new HashMap<>();Inventory inventory;
        Holder(UUID viewer,UUID city,UUID nation){this.viewer=viewer;this.city=city;this.nation=nation;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private CoreCity current(Player player){
        if(!player.hasPermission("starworld.use"))throw new IllegalArgumentException("需要 starworld.use 权限");
        return runtime.playerCity(player).orElseThrow(()->new IllegalArgumentException("请先加入城市"));
    }
    private boolean same(Player player,CoreCity city){return player.isOnline()&&player.hasPermission("starworld.use")
            &&runtime.playerCity(player).filter(c->c.cityId().equals(city.cityId())&&c.nationId().equals(city.nationId())).isPresent();}
    public void open(Player player){Location at=player.getLocation();openAt(player,at.getWorld().getUID(),at.getBlockX()>>4,at.getBlockZ()>>4);}
    public void openAt(Player player,UUID world,int x,int z){
        CoreCity expected=current(player);RegionalMap.coordinates(x,z);
        runtime.reply(player,runtime.regionMap(expected.cityId(),world,x,z),result->{
            if(!same(player,expected))return;
            if(result.isEmpty()||!result.get().nation().equals(expected.nationId())||!result.get().authorityActive()){player.sendMessage("§e当前归属下尚无可用地区，请先勘探或等待城市信息更新");return;}
            RegionalMap.View view=result.get();Holder h=new Holder(player.getUniqueId(),expected.cityId(),expected.nationId());
            h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · 方位地图"));
            for(int row=0;row<RegionalMap.HEIGHT;row++)for(int column=0;column<RegionalMap.WIDTH;column++)
                h.inventory.setItem(row*RegionalMap.WIDTH+column,item(Material.GRAY_STAINED_GLASS_PANE,"未登记 · "+(x+column-4)+","+(z+row-2),List.of("本城没有此区块的地理记录","在有权限的本城领土使用 /sw survey 勘探")));
            Map<String,RegionalMap.District> groups=new HashMap<>();for(var district:view.districts())groups.put(district.id(),district);
            for(var cell:view.cells()){
                var r=cell.region();var group=groups.get(cell.districtId());
                h.inventory.setItem(cell.slot(),item(icon(r.terrain()),r.terrainName()+" · "+r.chunkX()+","+r.chunkZ(),List.of(
                        view.worldName(),"储量："+r.reserves(),"每期恢复："+r.regeneration(),"设施："+r.facilities().size(),
                        "相邻同地形地区组："+group.regions().size()+" 个区块","点击以此地区为地图中心")));
                h.actions.put(cell.slot(),p->openAt(p,world,r.chunkX(),r.chunkZ()));
            }
            button(h,45,Material.END_PORTAL_FRAME,"切换世界",List.of("只列本城已登记地区所在的世界"),p->{
                if(view.worlds().isEmpty())return;int current=-1;for(int i=0;i<view.worlds().size();i++)if(view.worlds().get(i).id().equals(world))current=i;
                UUID next=view.worlds().get((current+1)%view.worlds().size()).id();RegionalMap.District first=view.districts().stream().filter(d->d.worldId().equals(next)).findFirst().orElseThrow();openAt(p,next,first.centerX(),first.centerZ());
            });
            button(h,46,Material.ARROW,"北 ↑",List.of("向北移动4个区块"),p->openAt(p,world,x,bounded(z-4)));
            button(h,47,Material.ARROW,"西 ←",List.of("向西移动8个区块"),p->openAt(p,world,bounded(x-8),z));
            h.inventory.setItem(48,item(Material.PAPER,view.worldName()+" · 中心 "+x+","+z,List.of("上北下南，左西右东；每格一个区块","地理记录不表示当前通行许可","已登记世界："+view.worlds().size())));
            button(h,49,Material.BOOK,"返回地图册",List.of(),p->p.performCommand("sw atlas"));
            button(h,50,Material.COMPASS,"回到脚下",List.of(),this::open);
            button(h,51,Material.ARROW,"东 →",List.of("向东移动8个区块"),p->openAt(p,world,bounded(x+8),z));
            button(h,52,Material.ARROW,"南 ↓",List.of("向南移动4个区块"),p->openAt(p,world,x,bounded(z+4)));
            button(h,53,Material.FILLED_MAP,"地区组",List.of("按相邻地形汇总资源与设施"),p->districts(p,0));player.openInventory(h.inventory);
        });
    }
    public void districts(Player player,int offset){
        if(offset<0||offset>=128||offset%45!=0)throw new IllegalArgumentException("地区组页码超限");CoreCity expected=current(player);
        runtime.reply(player,runtime.districts(expected.cityId()),result->{
            if(!same(player,expected))return;
            if(result.isEmpty()||!result.get().nation().equals(expected.nationId())||!result.get().authorityActive()){player.sendMessage("§e当前归属下尚无可用地区组");return;}
            var report=result.get();Holder h=new Holder(player.getUniqueId(),expected.cityId(),expected.nationId());h.inventory=Bukkit.createInventory(h,54,Component.text("StarWorld · 地区组"));
            int end=Math.min(offset+45,report.districts().size());
            for(int i=offset;i<end;i++){
                var d=report.districts().get(i);button(h,i-offset,icon(d.terrain()),d.terrainName()+"地区组 · "+d.regions().size()+" 区块",List.of(
                        d.worldName(),"范围："+d.minChunkX()+","+d.minChunkZ()+" 至 "+d.maxChunkX()+","+d.maxChunkZ(),"储量合计："+d.reserves(),
                        "初始容量合计："+d.capacities(),"每期恢复合计："+d.regeneration(),"设施："+d.facilities(),"点击定位地图"),p->openAt(p,d.worldId(),d.centerX(),d.centerZ()));
            }
            if(offset>0)button(h,45,Material.ARROW,"上一页",List.of(),p->districts(p,offset-45));
            button(h,49,Material.COMPASS,"脚下地图",List.of("相邻且同地形的区块自动归组","储量仍保留在各个原地区中"),this::open);
            if(end<report.districts().size())button(h,53,Material.ARROW,"下一页",List.of(),p->districts(p,offset+45));player.openInventory(h.inventory);
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder h))return;event.setCancelled(true);
        if((event.getClick()!=ClickType.LEFT&&event.getClick()!=ClickType.RIGHT)||!(event.getWhoClicked() instanceof Player p)
                ||!p.getUniqueId().equals(h.viewer)||event.getRawSlot()<0||event.getRawSlot()>=54)return;
        Consumer<Player> action=h.actions.get(event.getRawSlot());if(action==null)return;
        Bukkit.getScheduler().runTask(JavaPlugin.getProvidingPlugin(GeographyMenu.class),()->{
            if(!p.isOnline()||p.getOpenInventory().getTopInventory().getHolder()!=h)return;
            if(!p.hasPermission("starworld.use")||runtime.playerCity(p).filter(c->c.cityId().equals(h.city)&&c.nationId().equals(h.nation)).isEmpty()){p.closeInventory();return;}
            try{action.accept(p);}catch(IllegalArgumentException failure){p.sendMessage("§c"+failure.getMessage());}
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    private static void button(Holder h,int slot,Material icon,String name,List<String> lore,Consumer<Player> action){h.inventory.setItem(slot,item(icon,name,lore));h.actions.put(slot,action);}
    private static int bounded(int value){return Math.max(-RegionalMap.MAX_CHUNK,Math.min(RegionalMap.MAX_CHUNK,value));}
    private static Material icon(String terrain){return switch(terrain){case "mountains"->Material.IRON_ORE;case "river"->Material.WATER_BUCKET;case "forest"->Material.OAK_LOG;case "desert"->Material.SAND;case "snow"->Material.SNOW_BLOCK;default->Material.WHEAT;};}
    private static ItemStack item(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(Component.text(name));meta.lore(lore.stream().map(Component::text).toList());item.setItemMeta(meta);return item;}
}

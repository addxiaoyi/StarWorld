package dev.starworld.life;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.life.LifeState.*;
import java.lang.reflect.Method;
import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** World operations stay on the Paper owning thread. No chunk loading, private upstream access or entity scan. */
public final class LifePlatform {
    private final JavaPlugin plugin; private final CoreBridge core; private final CustomItems items; private final LifeDefinitions definitions;
    private final Method furnitureLookup,furnitureRayTrace;
    public LifePlatform(JavaPlugin plugin,CoreBridge core,CustomItems items,LifeDefinitions definitions) {
        this(plugin,core,items,definitions,lookup(plugin),rayTrace(plugin));
    }
    LifePlatform(JavaPlugin plugin,CoreBridge core,CustomItems items,LifeDefinitions definitions,Method furnitureLookup) {
        this(plugin,core,items,definitions,furnitureLookup,null);
    }
    LifePlatform(JavaPlugin plugin,CoreBridge core,CustomItems items,LifeDefinitions definitions,Method furnitureLookup,Method furnitureRayTrace) {
        this.plugin=Objects.requireNonNull(plugin);this.core=Objects.requireNonNull(core);this.items=Objects.requireNonNull(items);this.definitions=Objects.requireNonNull(definitions);
        this.furnitureLookup=furnitureLookup;this.furnitureRayTrace=furnitureRayTrace;
    }
    private static Method lookup(JavaPlugin plugin) {
        Method lookup=null;Plugin ce=plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if(ce!=null && ce.isEnabled())try {lookup=Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture",true,ce.getClass().getClassLoader()).getMethod("getLoadedFurnitureByMetaEntity",Entity.class);}
        catch(ReflectiveOperationException|LinkageError|RuntimeException failure){plugin.getLogger().warning("CE furniture API unavailable: "+failure.getClass().getSimpleName());}
        return lookup;
    }
    private static Method rayTrace(JavaPlugin plugin) {
        Plugin ce=plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if(ce!=null && ce.isEnabled())try {return Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture",true,ce.getClass().getClassLoader()).getMethod("rayTrace",Player.class,double.class);}
        catch(ReflectiveOperationException|LinkageError|RuntimeException failure){plugin.getLogger().warning("CE furniture targeting API unavailable: "+failure.getClass().getSimpleName());}
        return null;
    }
    public Map<String,String> status() {
        return Map.of("Deco",furnitureAvailable() && furnitureRayTrace!=null?"CE 公共家具 API：显式登记并按周期复核已加载家具":"CE 家具 API 不可用：装饰登记停用；现有装饰仅在身份复核通过时结算",
                "MusicPlus","上游无公开完成 API；仅使用真实 CE 乐器；/sw concert 为 StarWorld 独立现场演奏",
                "Concert","原版音符盒或已加载 CE 乐器；逐音符声音、现场听众和完成回执");
    }
    /** Live owning-thread capability used to avoid allocating furniture-dependent civic work while CE is unavailable. */
    public boolean decorationEvidenceAvailable() { owningThread(); return furnitureAvailable() && furnitureRayTrace != null; }
    public Set<String> availableItems() {
        owningThread();Set<String> available=new HashSet<>();for(String id:definitions.itemIds())if(items.create(id,1)!=null)available.add(id);return Set.copyOf(available);
    }
    /** CE owns furniture hitboxes; a Bukkit entity raycast may hit a collider or miss a zero-size display. */
    public Optional<DecorationEvidence> inspectTargetDecoration(Player player,UUID cityId) {
        owningThread();if(!furnitureAvailable() || furnitureRayTrace==null || !player.isOnline())return Optional.empty();
        try {
            Object target=furnitureRayTrace.invoke(null,player,6d);Optional<Furniture> hit=identity(target);if(hit.isEmpty())return Optional.empty();
            Entity meta=(Entity)target.getClass().getMethod("getBukkitEntity").invoke(target);
            return inspectDecoration(player,cityId,meta).filter(e->e.furnitureId().equals(hit.get().uuid()) && e.itemId().equals(hit.get().id()));
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return Optional.empty();}
    }
    public Optional<DecorationEvidence> inspectDecoration(Player player,UUID cityId,Entity entity) {
        owningThread();if(!furnitureAvailable() || entity==null || !entity.isValid() || !player.isOnline())return Optional.empty();
        Optional<CoreCity> current=core.playerCity(player.getUniqueId());if(current.isEmpty() || !current.get().cityId().equals(cityId))return Optional.empty();
        if(!player.getWorld().equals(entity.getWorld()) || player.getLocation().distanceSquared(entity.getLocation())>36)return Optional.empty();
        Site site=site(entity.getLocation());if(!core.ownsSite(cityId,site))return Optional.empty();
        Optional<Furniture> furniture=resolve(entity);if(furniture.isEmpty() || !definitions.decorations().furnitureIds().contains(furniture.get().id()))return Optional.empty();
        return Optional.of(new DecorationEvidence(furniture.get().uuid(),entity.getUniqueId(),cityId,current.get().nationId(),player.getUniqueId(),furniture.get().id(),site));
    }
    public boolean validateDecoration(Decoration decoration) {
        owningThread();if(!furnitureAvailable())return false;
        Optional<CoreCity> current=core.city(decoration.city());if(current.isEmpty() || !current.get().nationId().equals(decoration.nation()) || !core.ownsSite(decoration.city(),decoration.site()))return false;
        World world=plugin.getServer().getWorld(decoration.site().worldId());
        if(world==null || !world.isChunkLoaded(decoration.site().x()>>4,decoration.site().z()>>4))return false;
        Entity entity=plugin.getServer().getEntity(decoration.metaEntityId());
        if(entity==null || !entity.isValid() || !site(entity.getLocation()).equals(decoration.site()))return false;
        Optional<Furniture> furniture=resolve(entity);return furniture.isPresent() && furniture.get().uuid().equals(decoration.furnitureId()) && furniture.get().id().equals(decoration.itemId()) && definitions.decorations().furnitureIds().contains(furniture.get().id());
    }
    /** Plays a real note before creating an immutable receipt; does not invoke or monitor private MusicPlus playback. */
    public NoteReceipt playNote(Player performer,ConcertView session,int pitch,int sequence) {
        owningThread();validatePerformer(performer,session);
        if(pitch<0 || pitch>24 || sequence!=session.notes()+1 || !session.state().equals("active"))throw new IllegalArgumentException("音高为 0–24，且演奏会须进行中");
        if(definitions.concerts().instruments().stream().noneMatch(id->items.matches(performer.getInventory().getItemInMainHand(),id)))throw new IllegalArgumentException("请手持音符盒或已加载的森罗乐器");
        long now=System.currentTimeMillis(),gap=now-session.lastNoteMillis();
        if(gap<definitions.concerts().noteGapMillis() || gap>definitions.concerts().maximumSilenceMillis() || now-session.startedMillis()>definitions.concerts().maximumDurationMillis())throw new IllegalArgumentException("演奏间隔无效或会话已超时，请重新开始");
        Set<UUID> audience=new HashSet<>();
        // Bounded local player query; never walks entities or loads chunks. Only verified city members count.
        for(Player listener:performer.getWorld().getNearbyPlayers(performer.getLocation(),8)) {
            if(audience.size()>=16)break;
            if(listener.getUniqueId().equals(performer.getUniqueId()) || !listener.isOnline())continue;
            if(core.playerCity(listener.getUniqueId()).filter(c->c.cityId().equals(session.city()) && c.nationId().equals(session.nation())).isPresent())audience.add(listener.getUniqueId());
        }
        if(audience.size()<definitions.concerts().audience())throw new IllegalArgumentException("八格内需要至少 "+definitions.concerts().audience()+" 位同城市听众");
        performer.getWorld().playSound(performer.getLocation(),"minecraft:block.note_block.harp",SoundCategory.RECORDS,1,(float)Math.pow(2,(pitch-12)/12.0));
        return new NoteReceipt(session.id(),performer.getUniqueId(),session.city(),session.nation(),sequence,pitch,now,audience);
    }
    public void validatePerformer(Player performer,ConcertView session) {
        owningThread();if(!performer.isOnline() || !performer.getUniqueId().equals(session.performer()))throw new IllegalArgumentException("演奏者不匹配");
        CoreCity current=core.playerCity(performer.getUniqueId()).orElseThrow(()->new IllegalArgumentException("请先加入城市"));
        if(!current.cityId().equals(session.city()) || !current.nationId().equals(session.nation()) || !core.ownsSite(current.cityId(),session.site()))throw new IllegalArgumentException("演奏城市或领土归属已改变");
        Site actual=site(performer.getLocation());
        if(!actual.worldId().equals(session.site().worldId()) || squared(actual,session.site())>64)throw new IllegalArgumentException("请返回演奏会锚点八格范围内");
    }
    /** Reserved port for a future genuine public upstream completion API. No adapter exists at the audited baseline. */
    public interface UpstreamMusicCompletionPort {
        String version();
        void onCommittedCompletion(java.util.function.Consumer<UpstreamCompletion> listener);
    }
    public record UpstreamCompletion(UUID transactionId,UUID performer,Site site,long startedMillis,long completedMillis) {
        public UpstreamCompletion{Objects.requireNonNull(transactionId);Objects.requireNonNull(performer);Objects.requireNonNull(site);if(startedMillis<0 || completedMillis<=startedMillis)throw new IllegalArgumentException("Invalid upstream performance");}
    }
    private Optional<Furniture> resolve(Entity entity) {
        try {
            return identity(furnitureLookup.invoke(null,entity));
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return Optional.empty();}
    }
    private Optional<Furniture> identity(Object furniture) {
        try {
            if(furniture==null || !Boolean.TRUE.equals(furniture.getClass().getMethod("isValid").invoke(furniture)))return Optional.empty();
            UUID id=(UUID)furniture.getClass().getMethod("uuid").invoke(furniture);Object key=furniture.getClass().getMethod("id").invoke(furniture);
            String item=(String)key.getClass().getMethod("asString").invoke(key);return id==null?Optional.empty():Optional.of(new Furniture(id,item));
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unavailable){return Optional.empty();}
    }
    private boolean furnitureAvailable(){return furnitureLookup!=null && plugin.getServer().getPluginManager().isPluginEnabled("CraftEngine");}
    private void owningThread(){if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("Life world operations require Paper main thread; Folia remains disabled until StarCore is region-safe");}
    public static Site site(Location location){return new Site(location.getWorld().getUID(),location.getWorld().getName(),location.getBlockX(),location.getBlockY(),location.getBlockZ());}
    private static double squared(Site a,Site b){double x=(double)a.x()-b.x(),y=(double)a.y()-b.y(),z=(double)a.z()-b.z();return x*x+y*y+z*z;}
    private record Furniture(UUID uuid,String id){}
}

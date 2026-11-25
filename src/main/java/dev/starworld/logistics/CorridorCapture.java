package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.integration.CoreBridge;
import dev.starworld.integration.TransitPermitPort;
import dev.starworld.integration.TransitPermitPort.Permit;
import dev.starworld.logistics.LogisticsState.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;

/** Paper main-thread bounded block/claim capture. Unloaded chunks are never accessed. */
public final class CorridorCapture {
    private static final Set<Material> ROAD=Set.of(Material.STONE,Material.SMOOTH_STONE,Material.COBBLESTONE,
            Material.STONE_BRICKS,Material.CHISELED_STONE_BRICKS,Material.POLISHED_ANDESITE,Material.POLISHED_DIORITE,
            Material.POLISHED_GRANITE,Material.DEEPSLATE_BRICKS,Material.BRICKS,Material.OAK_PLANKS,Material.SPRUCE_PLANKS,
            Material.BIRCH_PLANKS,Material.JUNGLE_PLANKS,Material.ACACIA_PLANKS,Material.DARK_OAK_PLANKS,Material.MANGROVE_PLANKS,
            Material.CHERRY_PLANKS,Material.BAMBOO_PLANKS,Material.LODESTONE,Material.BARREL);
    private final Server server;private final CoreBridge core;private int reads;
    public CorridorCapture(Server server,CoreBridge core){this.server=Objects.requireNonNull(server);this.core=Objects.requireNonNull(core);}
    public Map<UUID,Corridor.Proof> capture(Collection<Route> routes,Collection<Node> nodes,Set<UUID> validNodes){
        return capture(routes,nodes,validNodes,null);
    }
    public Map<UUID,Corridor.Proof> capture(Collection<Route> routes,Collection<Node> nodes,Set<UUID> validNodes,TransitPermitPort transit){
        if(!server.isPrimaryThread())throw new IllegalStateException("通道取证必须在 Paper 主线程执行");
        if(routes.size()>Corridor.MAX_ROUTES)throw new IllegalArgumentException("单次通道路线数量超限");
        Map<UUID,Node> index=new HashMap<>();for(Node node:nodes)index.put(node.id(),node);
        Map<UUID,Corridor.Proof> result=new LinkedHashMap<>();
        for(Route route:routes){int before=reads,checked=0;String fingerprint="";Map<UUID,Permit> permits=Map.of();
            try{
                Corridor.Plan plan=Corridor.plan(route,index);fingerprint=plan.fingerprint();
                if(!validNodes.containsAll(route.nodes()))throw new Blocked("节点锚点或领土失效");
                World world=server.getWorld(plan.columns().getFirst().worldId());
                if(world==null||!world.getName().equals(plan.columns().getFirst().worldName()))throw new Blocked("世界未加载或已更名");
                if(route.requiresTransit())permits=TransitCorridor.authorize(TransitCorridor.describe(server,core,route,index),transit);
                Map<String,Boolean> claims=new HashMap<>();Set<Site> inspected=new HashSet<>();
                for(Site column:plan.columns()){
                    if(route.mode()==Mode.LAND){checkColumn(world,route,column,claims,permits);land(world,column);}
                    else if(!plan.berths().contains(column)){
                        // A 3x3 footprint keeps corners and approaches wide enough; each water column is read once.
                        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
                            Site at=new Site(column.worldId(),column.worldName(),column.x()+x,column.y(),column.z()+z);
                            if(plan.berths().contains(at)||!inspected.add(at))continue;
                            checkColumn(world,route,at,claims,permits);water(world,at);
                        }
                    }
                    checked++;
                }
                result.put(route.id(),new Corridor.Proof(route.id(),fingerprint,true,checked,reads-before,"全程有限通道及过境权限已核验",permits));
            }catch(IllegalArgumentException|Blocked failure){result.put(route.id(),new Corridor.Proof(route.id(),fingerprint,false,checked,reads-before,failure.getMessage()));}
        }
        return Map.copyOf(result);
    }
    private void checkColumn(World world,Route route,Site at,Map<String,Boolean> claims,Map<UUID,Permit> permits){
        if(at.y()-1<world.getMinHeight()||at.y()+3>=world.getMaxHeight())throw blocked("超出世界高度",at);
        if(!world.isChunkLoaded(at.x()>>4,at.z()>>4))throw blocked("区块未加载",at);
        boolean allowed=claims.computeIfAbsent(at.regionKey(),key->core.ownsSite(route.originCity(),at)||core.ownsSite(route.targetCity(),at)||permits.values().stream().flatMap(p->p.terms().parcels().stream()).anyMatch(p->p.worldId().equals(at.worldId())&&p.worldName().equals(at.worldName())&&p.chunkX()==(at.x()>>4)&&p.chunkZ()==(at.z()>>4)));
        if(!allowed)throw blocked("沿途双层领土不属于商路双方城市",at);
    }
    private Block block(World world,Site at,int dy){if(reads>=Corridor.MAX_BLOCK_READS)throw new Blocked("本批通道读取预算已用尽，等待下次复核");reads++;return world.getBlockAt(at.x(),at.y()+dy,at.z());}
    private void land(World world,Site at){
        if(!ROAD.contains(block(world,at,0).getType()))throw blocked("道路路面缺失或材料不受支持",at);
        for(int y=1;y<=3;y++)if(!air(block(world,at,y).getType()))throw blocked("道路上方需三格净空",at);
    }
    private void water(World world,Site at){
        for(int y=-1;y<=0;y++){Block block=block(world,at,y);if(block.getType()!=Material.WATER||!(block.getBlockData() instanceof Levelled level)||level.getLevel()!=0)throw blocked("水路需两格深的静止水源",at);}
        for(int y=1;y<=2;y++)if(!air(block(world,at,y).getType()))throw blocked("水路上方需两格净空",at);
    }
    private static boolean air(Material material){return material==Material.AIR||material==Material.CAVE_AIR||material==Material.VOID_AIR;}
    private static Blocked blocked(String reason,Site at){return new Blocked(reason+" · "+at.x()+","+at.y()+","+at.z());}
    private static final class Blocked extends RuntimeException {Blocked(String message){super(message);}}
}

package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.integration.CoreBridge;
import dev.starworld.logistics.LogisticsState.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CorridorCaptureTest {
    static final class Rig {
        final Server server=mock(Server.class);final World world=mock(World.class);final CoreBridge core=mock(CoreBridge.class);
        final UUID worldId=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();final List<Node> nodes;final Route route;final Map<String,Material> changed=new HashMap<>();boolean flowing;
        Rig(Mode mode,int distance){
            when(server.isPrimaryThread()).thenReturn(true);when(server.getWorld(worldId)).thenReturn(world);when(world.getName()).thenReturn("world");when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(core.ownsSite(any(),any())).thenReturn(true);
            NodeType type=mode==Mode.SEA?NodeType.PORT:NodeType.ROAD;
            nodes=List.of(new Node(UUID.randomUUID(),a,UUID.randomUUID(),type,new Site(worldId,"world",0,64,8),"plains",1,true),new Node(UUID.randomUUID(),b,UUID.randomUUID(),type,new Site(worldId,"world",distance,64,8),"plains",1,true));
            route=new Route(UUID.randomUUID(),a,b,nodes.stream().map(Node::id).toList(),mode,true,distance,3,128,true);
            Block air=mock(Block.class),stone=mock(Block.class),water=mock(Block.class),flow=mock(Block.class),lava=mock(Block.class);when(air.getType()).thenReturn(Material.AIR);when(stone.getType()).thenReturn(Material.STONE);when(water.getType()).thenReturn(Material.WATER);when(flow.getType()).thenReturn(Material.WATER);when(lava.getType()).thenReturn(Material.LAVA);
            Levelled source=mock(Levelled.class),moving=mock(Levelled.class);when(moving.getLevel()).thenReturn(1);when(water.getBlockData()).thenReturn(source);when(flow.getBlockData()).thenReturn(moving);
            when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Material typeAt=changed.getOrDefault(x+":"+y+":"+z,mode==Mode.LAND?(y==64?Material.STONE:Material.AIR):(y==63||y==64?Material.WATER:Material.AIR));return switch(typeAt){case WATER->flowing?flow:water;case STONE->stone;case LAVA->lava;default->air;};});
        }
        Set<UUID> valid(){return Set.of(nodes.getFirst().id(),nodes.getLast().id());}
        Corridor.Proof capture(){return new CorridorCapture(server,core).capture(List.of(route),nodes,valid()).get(route.id());}
    }
    @Test void realBlockReadsRejectRoadGapsObstructionAndMissingOrForeignClaims(){
        Rig r=new Rig(Mode.LAND,32);assertTrue(r.capture().pass());assertEquals(33,r.capture().checkedColumns());
        r.changed.put("17:64:8",Material.AIR);assertTrue(r.capture().detail().contains("路面缺失"));r.changed.clear();r.changed.put("10:66:8",Material.STONE);assertTrue(r.capture().detail().contains("净空"));r.changed.clear();
        when(r.core.ownsSite(any(),any())).thenAnswer(call->((Site)call.getArgument(1)).x()<16||((Site)call.getArgument(1)).x()>=32);assertTrue(r.capture().detail().contains("双层领土"));
        doReturn(true).when(r.core).ownsSite(any(),any());assertTrue(r.capture().pass());verify(r.world,never()).getChunkAt(anyInt(),anyInt());
    }
    @Test void anUnloadedChunkOrInvalidAnchorIsNeverReadOrLoaded(){
        Rig r=new Rig(Mode.LAND,32);when(r.world.isChunkLoaded(1,0)).thenReturn(false);assertTrue(r.capture().detail().contains("未加载"));verify(r.world,never()).getBlockAt(intThat(x->x>=16),anyInt(),anyInt());verify(r.world,never()).getChunkAt(anyInt(),anyInt());
        clearInvocations(r.world);var proof=new CorridorCapture(r.server,r.core).capture(List.of(r.route),r.nodes,Set.of()).get(r.route.id());assertFalse(proof.pass());verify(r.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
        when(r.server.isPrimaryThread()).thenReturn(false);assertThrows(IllegalStateException.class,r::capture);
    }
    @Test void navigableWaterNeedsDepthWidthSourceBlocksAndHeadroom(){
        Rig r=new Rig(Mode.SEA,32);assertTrue(r.capture().pass());
        for(String coordinate:List.of("16:63:8","16:64:7","16:64:9")){r.changed.put(coordinate,Material.AIR);assertTrue(r.capture().detail().contains("两格深"));r.changed.clear();}
        r.changed.put("16:66:8",Material.STONE);assertTrue(r.capture().detail().contains("净空"));r.changed.clear();r.flowing=true;assertFalse(r.capture().pass());r.flowing=false;assertTrue(r.capture().pass());
    }
    @Test void aBatchHasAHardReadBudgetAndNextCaptureRecovers(){
        Rig r=new Rig(Mode.LAND,512);List<Route> routes=new ArrayList<>();for(int i=0;i<20;i++)routes.add(new Route(UUID.randomUUID(),r.a,r.b,r.route.nodes(),Mode.LAND,true,512,4,128,true));
        var reports=new CorridorCapture(r.server,r.core).capture(routes,r.nodes,r.valid());assertEquals(Corridor.MAX_BLOCK_READS,reports.values().stream().mapToInt(Corridor.Proof::blockReads).sum());assertTrue(reports.values().stream().anyMatch(p->p.pass()));assertTrue(reports.values().stream().anyMatch(p->p.detail().contains("预算")));assertTrue(r.capture().pass());
        assertThrows(IllegalArgumentException.class,()->new CorridorCapture(r.server,r.core).capture(Collections.nCopies(33,r.route),r.nodes,r.valid()));
    }
}

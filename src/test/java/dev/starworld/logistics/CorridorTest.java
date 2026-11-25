package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.logistics.LogisticsState.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CorridorTest {
    final UUID world=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID(),nation=UUID.randomUUID();
    Node node(UUID city,int x,int y,int z,NodeType type){return new Node(UUID.randomUUID(),city,nation,type,new Site(world,"world",x,y,z),"plains",1,true);}
    Route route(List<Node> nodes,Mode mode){return new Route(UUID.randomUUID(),a,b,nodes.stream().map(Node::id).toList(),mode,true,1,1,128);}
    Map<UUID,Node> index(List<Node> nodes){Map<UUID,Node> result=new HashMap<>();nodes.forEach(n->result.put(n.id(),n));return result;}
    @Test void cardinalTurnsSlopesAndNegativeCoordinatesHaveNoGapsOrDuplicateTotals(){
        List<Node> nodes=List.of(node(a,-17,64,-5,NodeType.ROAD),node(a,-1,66,-5,NodeType.BRIDGE),node(b,-1,66,8,NodeType.PASS));
        Route route=route(nodes,Mode.LAND);var plan=Corridor.plan(route,index(nodes));assertEquals(30,plan.columns().size());assertEquals(nodes.getFirst().site(),plan.columns().getFirst());assertEquals(nodes.getLast().site(),plan.columns().getLast());
        for(int i=1;i<plan.columns().size();i++){Site before=plan.columns().get(i-1),after=plan.columns().get(i);assertEquals(1,Math.abs(after.x()-before.x())+Math.abs(after.z()-before.z()));assertTrue(Math.abs(after.y()-before.y())<=1);}
        List<Node> reversed=nodes.reversed();var back=Corridor.plan(route(reversed,Mode.LAND),index(nodes));assertEquals(plan.columns().reversed(),back.columns());
        assertThrows(UnsupportedOperationException.class,()->plan.columns().clear());assertThrows(UnsupportedOperationException.class,()->plan.berths().clear());
        assertNotEquals(plan.fingerprint(),back.fingerprint());
    }
    @Test void boundsAndUnsupportedGeometryFailBeforeAnyWorldRead(){
        Node start=node(a,0,64,0,NodeType.ROAD),limit=node(b,1023,64,0,NodeType.ROAD);List<Node> valid=List.of(start,limit);assertEquals(1024,Corridor.plan(route(valid,Mode.LAND),index(valid)).columns().size());
        for(Node end:List.of(node(b,1024,64,0,NodeType.ROAD),node(b,1,64,1,NodeType.ROAD),node(b,0,66,0,NodeType.ROAD),node(b,1,67,0,NodeType.ROAD),node(b,Integer.MIN_VALUE,64,0,NodeType.ROAD))){List<Node> nodes=List.of(start,end);assertThrows(IllegalArgumentException.class,()->Corridor.plan(route(nodes,Mode.LAND),index(nodes)));}
        Node alien=new Node(UUID.randomUUID(),b,nation,NodeType.ROAD,new Site(UUID.randomUUID(),"nether",16,64,0),"plains",1,true);List<Node> nodes=List.of(start,alien);assertThrows(IllegalArgumentException.class,()->Corridor.plan(route(nodes,Mode.LAND),index(nodes)));
    }
    @Test void waterRequiresPortsLevelSurfaceAndWaterBetweenBerths(){
        Node port=node(a,0,64,0,NodeType.PORT),other=node(b,16,64,0,NodeType.PORT);var nodes=List.of(port,other);assertEquals(Set.of(port.site(),other.site()),Corridor.plan(route(nodes,Mode.SEA),index(nodes)).berths());
        for(Node invalid:List.of(node(b,16,64,0,NodeType.ROAD),node(b,16,65,0,NodeType.PORT),node(b,1,64,0,NodeType.PORT))){var pair=List.of(port,invalid);assertThrows(IllegalArgumentException.class,()->Corridor.plan(route(pair,Mode.SEA),index(pair)));}
    }
}

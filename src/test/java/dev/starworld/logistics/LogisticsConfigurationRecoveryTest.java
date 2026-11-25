package dev.starworld.logistics;

import dev.starworld.domain.WorldState.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsConfigurationRecoveryTest {
    @TempDir Path temporary;
    static final String BREAD="minecraft:bread",IRON="minecraft:iron_ingot",PLANKS="minecraft:oak_planks";
    static LogisticsDefinitions limits(int travel,int waypoints,double speed){LogisticsDefinitions d=LogisticsDefinitions.defaults();return new LogisticsDefinitions(d.nodes(),d.terrainTime(),d.maxNodesPerCity(),d.maxRoutesPerCity(),waypoints,d.maxOpenOrdersPerCity(),d.maxBatch(),speed,travel,d.offerLifetimePeriods(),d.maxItemsPerOrder());}
    final class Rig implements AutoCloseable {
        final Path db=temporary.resolve(UUID.randomUUID()+".db");final UUID actor=UUID.randomUUID(),world=UUID.randomUUID();
        final CoreCity seller=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"粮城","农国",1,"test"),buyer=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"铁城","工国",1,"test");
        final Set<String> anchors=new HashSet<>();final Set<UUID> nodes=new HashSet<>();WorldStore store;LogisticsService service;
        Rig()throws Exception{open(LogisticsDefinitions.defaults());for(CoreCity city:List.of(seller,buyer)){store.ensureCity(city);for(String item:List.of(BREAD,IRON,PLANKS))store.stock(city.cityId(),item,1000);}}
        void open(LogisticsDefinitions definitions)throws Exception{store=new WorldStore(db,10000);service=new LogisticsService(store,definitions);}
        void restart(LogisticsDefinitions definitions)throws Exception{store.close();open(definitions);}
        AuthoritySnapshot auth(long period,UUID...managed){return new AuthoritySnapshot(actor,period,Map.of(seller.cityId(),seller.nationId(),buyer.cityId(),buyer.nationId()),Set.of(managed),anchors,nodes,Set.of(TradePair.of(seller.cityId(),buyer.cityId())));}
        Node node(CoreCity city,int x)throws Exception{Site site=new Site(world,"world",x,64,0);anchors.add(site.anchorKey());Node node=service.registerNode(UUID.randomUUID(),city.cityId(),NodeType.ROAD,site,"plains",auth(0,city.cityId()));nodes.add(node.id());return node;}
        Route path(int from,int to)throws Exception{Node a=node(seller,from),b=node(buyer,to);return service.createRoute(UUID.randomUUID(),seller.cityId(),buyer.cityId(),List.of(a.id(),b.id()),Mode.LAND,auth(0,seller.cityId()));}
        Order offer(Route route,long period)throws Exception{return service.createOffer(UUID.randomUUID(),route.id(),Map.of(BREAD,20L),Map.of(IRON,5L),auth(period,seller.cityId()));}
        Order accepted(Route route,long period)throws Exception{Order order=offer(route,period);return service.accept(order.id(),auth(period,buyer.cityId()));}
        public void close()throws Exception{store.close();}
    }
    @Test void tightenedTravelLimitPausesOnlyOldRouteWithoutChargingAndSupportsCancelRerouteAndRestore()throws Exception{
        try(Rig r=new Rig()){
            Route longRoute=r.path(0,512),shortRoute=r.path(16,80);Order original=r.accepted(longRoute,0);assertEquals(4,original.travelPeriods());r.service.settle(r.auth(1),10);
            Order rerouted=r.accepted(longRoute,1),offered=r.offer(longRoute,1),shortOrder=r.accepted(shortRoute,1);r.restart(limits(1,16,128));
            Route stored=r.service.route(longRoute.id()).orElseThrow();assertTrue(r.service.routeLimitation(stored).orElseThrow().contains("时长"));assertEquals(0,r.service.transportStatus(longRoute.id(),2).available());
            assertEquals(List.of(shortRoute.id()),r.service.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0).stream().map(RoutePlan::routeId).toList());
            assertThrows(SQLException.class,()->r.service.createRoute(UUID.randomUUID(),r.seller.cityId(),r.buyer.cityId(),longRoute.nodes(),Mode.LAND,r.auth(1,r.seller.cityId())));
            BatchResult batch=r.service.settle(r.auth(2),10);assertEquals(2,batch.paused());assertEquals(1,batch.completed());assertEquals(OrderStatus.COMPLETED,r.service.order(shortOrder.id()).orElseThrow().status());
            Order paused=r.service.order(original.id()).orElseThrow();assertEquals(OrderStatus.PAUSED,paused.status());assertEquals(1,paused.progressedPeriods());assertEquals(4,paused.travelPeriods());assertEquals(original.offered(),paused.offered());assertEquals(original.requested(),paused.requested());assertEquals(2,paused.lastPeriod());
            TransportStatus status=r.service.transportStatus(longRoute.id(),2);assertEquals(0,status.routeUsed());assertTrue(status.nodes().stream().allMatch(n->n.maintenancePeriod()==1));
            assertEquals(OrderStatus.CANCELLED,r.service.cancel(offered.id(),r.auth(2,r.seller.cityId())).status());assertFalse(r.service.setRouteEnabled(longRoute.id(),false,r.auth(2,r.seller.cityId())).enabled());assertThrows(SQLException.class,()->r.service.setRouteEnabled(longRoute.id(),true,r.auth(2,r.seller.cityId())));
            UUID request=UUID.randomUUID();r.service.proposeReroute(request,rerouted.id(),shortRoute.id(),r.auth(2,r.seller.cityId()));assertEquals(RerouteStatus.APPLIED,r.service.acceptReroute(request,r.auth(2,r.buyer.cityId())).status());
            r.restart(LogisticsDefinitions.defaults());r.service.setRouteEnabled(longRoute.id(),true,r.auth(3,r.seller.cityId()));assertEquals(1,r.service.settle(r.auth(3),10).completed());r.service.acceptReroute(request,r.auth(3,r.buyer.cityId()));assertEquals(1,r.service.order(rerouted.id()).orElseThrow().progressedPeriods());
            assertEquals(2,r.service.order(original.id()).orElseThrow().progressedPeriods());r.service.settle(r.auth(4),10);assertEquals(1,r.service.settle(r.auth(5),10).completed());assertEquals(3,r.service.completedAfter(0,10).size());assertEquals(0,r.service.settle(r.auth(5),10).inspected());
        }
    }
    @Test void reducedWaypointLimitKeepsLegacyPathReadableAndAllowsBilateralRecovery()throws Exception{
        try(Rig r=new Rig()){
            Node a=r.node(r.seller,0),middle=r.node(r.seller,32),b=r.node(r.buyer,64);Route old=r.service.createRoute(UUID.randomUUID(),r.seller.cityId(),r.buyer.cityId(),List.of(a.id(),middle.id(),b.id()),Mode.LAND,r.auth(0,r.seller.cityId()));
            Route alternative=r.service.createRoute(UUID.randomUUID(),r.seller.cityId(),r.buyer.cityId(),List.of(a.id(),b.id()),Mode.LAND,r.auth(0,r.seller.cityId()));Order order=r.accepted(old,0);long before=r.store.stock(r.seller.cityId()).get(PLANKS);r.restart(limits(1000,2,128));
            assertEquals(3,r.service.route(old.id()).orElseThrow().nodes().size());assertTrue(r.service.routeLimitation(r.service.route(old.id()).orElseThrow()).orElseThrow().contains("节点数"));assertEquals(1,r.service.settle(r.auth(1),10).paused());assertEquals(before,r.store.stock(r.seller.cityId()).get(PLANKS));
            assertEquals(List.of(alternative.id()),r.service.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0).stream().map(RoutePlan::routeId).toList());assertEquals(List.of(alternative.id()),r.service.alternativeRoutes(order.id(),r.auth(1,r.seller.cityId()),16).stream().map(Route::id).toList());
            UUID request=UUID.randomUUID();r.service.proposeReroute(request,order.id(),alternative.id(),r.auth(1,r.seller.cityId()));r.service.acceptReroute(request,r.auth(1,r.buyer.cityId()));assertEquals(1,r.service.settle(r.auth(2),10).completed());assertEquals(1,r.service.completedAfter(0,10).size());
        }
    }
    @Test void nearZeroConfiguredSpeedKeepsInfiniteLegacyTravelQueryableAndRecoverable()throws Exception{
        try(Rig r=new Rig()){
            Route old=r.path(0,512);Order order=r.accepted(old,0);r.restart(limits(1000,16,Double.MIN_VALUE));Route stored=r.service.route(old.id()).orElseThrow();assertEquals(Integer.MAX_VALUE,stored.travelPeriods());assertTrue(r.service.routeLimitation(stored).isPresent());assertTrue(r.service.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0).isEmpty());
            assertEquals(1,r.service.settle(r.auth(1),10).paused());assertEquals(0,r.service.order(order.id()).orElseThrow().progressedPeriods());assertEquals(OrderStatus.CANCELLED,r.service.cancel(order.id(),r.auth(1,r.seller.cityId())).status());assertFalse(r.service.setRouteEnabled(old.id(),false,r.auth(1,r.seller.cityId())).enabled());
        }
    }
}

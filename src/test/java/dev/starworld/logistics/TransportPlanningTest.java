package dev.starworld.logistics;

import dev.starworld.domain.WorldState.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransportPlanningTest {
    @TempDir Path temporary;
    static final String BREAD="minecraft:bread",IRON="minecraft:iron_ingot",PLANKS="minecraft:oak_planks";
    final class Rig implements AutoCloseable {
        final Path db;final WorldStore store;final LogisticsService logistics;
        final UUID actor=UUID.randomUUID(),world=UUID.randomUUID();
        final CoreCity seller=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"甲城","甲国",1,"test"),buyer=new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"乙城","乙国",1,"test");
        final Set<String> anchors=new HashSet<>();final Set<UUID> nodes=new HashSet<>();
        final Route original,alternative;
        Rig(String name)throws Exception {
            db=temporary.resolve(name);store=new WorldStore(db,10000);logistics=new LogisticsService(store,LogisticsDefinitions.defaults());
            for(CoreCity city:List.of(seller,buyer)){store.ensureCity(city);for(String item:List.of(BREAD,IRON,PLANKS))store.stock(city.cityId(),item,1000);}
            original=path(0,640);alternative=path(16,336);
        }
        AuthoritySnapshot auth(long period,UUID...managers){return new AuthoritySnapshot(actor,period,Map.of(seller.cityId(),seller.nationId(),buyer.cityId(),buyer.nationId()),Set.of(managers),anchors,nodes,Set.of(TradePair.of(seller.cityId(),buyer.cityId())));}
        Node node(CoreCity city,int x)throws Exception{Site site=new Site(world,"world",x,64,0);anchors.add(site.anchorKey());Node node=logistics.registerNode(UUID.randomUUID(),city.cityId(),NodeType.ROAD,site,"plains",auth(0,city.cityId()));nodes.add(node.id());return node;}
        Route path(int from,int to)throws Exception{Node a=node(seller,from),b=node(buyer,to);return logistics.createRoute(UUID.randomUUID(),seller.cityId(),buyer.cityId(),List.of(a.id(),b.id()),Mode.LAND,auth(0,seller.cityId()));}
        Order order()throws Exception{Order o=logistics.createOffer(UUID.randomUUID(),original.id(),Map.of(BREAD,20L),Map.of(IRON,5L),auth(0,seller.cityId()));return logistics.accept(o.id(),auth(0,buyer.cityId()));}
        long stock(CoreCity city,String item)throws Exception{return store.stock(city.cityId()).getOrDefault(item,0L);}
        public void close()throws Exception{store.close();}
    }

    @Test void bilateralRerouteRestartsWholeJourneyWithoutRefundingSpentCapacityOrMovingTwice()throws Exception{
        try(Rig r=new Rig("reroute.db")){
            Order order=r.order();assertEquals(4,order.travelPeriods());r.logistics.settle(r.auth(1),10);
            assertEquals(1,r.logistics.order(order.id()).orElseThrow().progressedPeriods());
            UUID request=UUID.randomUUID();Reroute proposal=r.logistics.proposeReroute(request,order.id(),r.alternative.id(),r.auth(1,r.seller.cityId()));
            assertEquals(proposal,r.logistics.proposeReroute(request,order.id(),r.alternative.id(),r.auth(1,r.seller.cityId())));
            assertThrows(SQLException.class,()->r.logistics.acceptReroute(request,r.auth(1,r.seller.cityId())));
            assertEquals(RerouteStatus.APPLIED,r.logistics.acceptReroute(request,r.auth(1,r.buyer.cityId())).status());
            Order changed=r.logistics.order(order.id()).orElseThrow();assertEquals(r.alternative.id(),changed.routeId());assertEquals(0,changed.progressedPeriods());assertEquals(2,changed.travelPeriods());
            assertEquals(25,r.logistics.transportStatus(r.original.id(),1).routeUsed());assertEquals(0,r.logistics.settle(r.auth(1),10).inspected());
            assertEquals(1,r.logistics.settle(r.auth(2),10).progressed());r.logistics.acceptReroute(request,r.auth(2,r.buyer.cityId()));assertEquals(1,r.logistics.order(order.id()).orElseThrow().progressedPeriods());
            assertEquals(1,r.logistics.settle(r.auth(3),10).completed());assertEquals(1020,r.stock(r.buyer,BREAD));assertEquals(1005,r.stock(r.seller,IRON));assertEquals(1,r.logistics.completedAfter(0,10).size());
        }
    }

    @Test void alternativesRequireProvenNodesKnownPeaceCurrentNationsAndSameDirection()throws Exception{
        try(Rig r=new Rig("alternatives.db")){
            Order order=r.order();assertEquals(List.of(r.alternative.id()),r.logistics.alternativeRoutes(order.id(),r.auth(0,r.buyer.cityId()),16).stream().map(Route::id).toList());
            Route reverse=r.logistics.createRoute(UUID.randomUUID(),r.buyer.cityId(),r.seller.cityId(),List.of(r.alternative.nodes().getLast(),r.alternative.nodes().getFirst()),Mode.LAND,r.auth(0,r.buyer.cityId()));
            assertEquals(1,r.logistics.declaredAlternatives(order.id(),16).size());assertThrows(SQLException.class,()->r.logistics.proposeReroute(UUID.randomUUID(),order.id(),reverse.id(),r.auth(0,r.seller.cityId())));
            AuthoritySnapshot a=r.auth(0,r.seller.cityId());Set<UUID> valid=new HashSet<>(a.validNodes());valid.remove(r.alternative.nodes().getFirst());
            AuthoritySnapshot missing=new AuthoritySnapshot(a.actor(),a.period(),a.cityNations(),a.managedCities(),a.controlledAnchors(),valid,a.permittedTrades());assertTrue(r.logistics.alternativeRoutes(order.id(),missing,16).isEmpty());
            AuthoritySnapshot war=new AuthoritySnapshot(a.actor(),a.period(),a.cityNations(),a.managedCities(),a.controlledAnchors(),a.validNodes(),Set.of());assertThrows(SQLException.class,()->r.logistics.alternativeRoutes(order.id(),war,16));
            assertThrows(SQLException.class,()->r.logistics.alternativeRoutes(order.id(),r.auth(0),16));assertThrows(IllegalArgumentException.class,()->r.logistics.alternativeRoutes(order.id(),a,17));
        }
    }

    @Test void routeCostPlanningSortsDeclaredSameDirectionPathsWithoutGrantingTransitAuthority()throws Exception{
        try(Rig r=new Rig("route-plans.db")){
            List<RoutePlan> plans=r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0);
            assertEquals(List.of(r.alternative.id(),r.original.id()),plans.stream().map(RoutePlan::routeId).toList());
            assertEquals(2,plans.getFirst().travelPeriods());
            assertEquals(2,plans.getFirst().nodeCount());
            assertEquals(Map.of(PLANKS,1L),plans.getFirst().maintenancePerPeriod().get(r.seller.cityId()));
            assertEquals(Map.of(PLANKS,2L),plans.getFirst().standaloneJourneyMaintenance().get(r.seller.cityId()));
            long before=r.stock(r.seller,PLANKS);
            assertTrue(plans.getFirst().distanceBlocks()<plans.getLast().distanceBlocks());
            r.logistics.setRouteEnabled(r.alternative.id(),false,r.auth(0,r.seller.cityId()));
            plans=r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0);
            assertFalse(plans.getFirst().enabled());
            assertEquals(2,plans.size(),"Planning keeps disabled declarations visible so operators can compare their stored cost");
            assertEquals(1,r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,1,1).size());
            assertTrue(r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.SEA,10,0).isEmpty());
            assertTrue(r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,Integer.MAX_VALUE).isEmpty());
            assertThrows(UnsupportedOperationException.class,()->r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0).clear());
            assertEquals(before,r.stock(r.seller,PLANKS),"Read-only planning never pays maintenance");
            assertThrows(IllegalArgumentException.class,()->r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,101,0));
            Map<String,Long> immutableCost=plans.getFirst().maintenancePerPeriod().get(r.seller.cityId());
            assertThrows(UnsupportedOperationException.class,immutableCost::clear);
            AuthoritySnapshot normal=r.auth(0,r.seller.cityId());
            AuthoritySnapshot war=new AuthoritySnapshot(normal.actor(),normal.period(),normal.cityNations(),normal.managedCities(),normal.controlledAnchors(),normal.validNodes(),Set.of());
            assertThrows(SQLException.class,()->r.logistics.createOffer(UUID.randomUUID(),r.original.id(),Map.of(BREAD,1L),Map.of(IRON,1L),war));
            assertEquals(2,r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0).size(),"Read-only plans remain queryable during war without permitting transport");
        }
    }

    @Test void planningRecomputesMaintenanceFromCurrentLevelsAndRejectsOverLimitLegacySet()throws Exception{
        try(Rig r=new Rig("route-plans-bounds.db")){
            UUID upgraded=r.alternative.nodes().getFirst();r.logistics.upgradeNode(upgraded,r.auth(0,r.seller.cityId()));
            long before=r.stock(r.seller,PLANKS);
            RoutePlan plan=r.logistics.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0).stream().filter(p->p.routeId().equals(r.alternative.id())).findFirst().orElseThrow();
            assertEquals(Map.of(PLANKS,2L),plan.maintenancePerPeriod().get(r.seller.cityId()));
            assertEquals(2L*plan.travelPeriods(),plan.standaloneJourneyMaintenance().get(r.seller.cityId()).get(PLANKS));
            assertEquals(before,r.stock(r.seller,PLANKS));
            LogisticsDefinitions d=LogisticsDefinitions.defaults();
            LogisticsDefinitions reduced=new LogisticsDefinitions(d.nodes(),d.terrainTime(),d.maxNodesPerCity(),1,d.maxWaypoints(),d.maxOpenOrdersPerCity(),d.maxBatch(),d.blocksPerPeriod(),d.maxTravelPeriods(),d.offerLifetimePeriods(),d.maxItemsPerOrder());
            LogisticsService limited=new LogisticsService(r.store,reduced);
            assertThrows(IllegalArgumentException.class,()->limited.compareRoutes(r.seller.cityId(),r.buyer.cityId(),Mode.LAND,10,0));
        }
    }

    @Test void expiredAndConflictingProposalsCannotChangeTheOrderAndNewProposalCanReplaceExpiry()throws Exception{
        try(Rig r=new Rig("expiry.db")){
            Order order=r.order();UUID request=UUID.randomUUID();r.logistics.proposeReroute(request,order.id(),r.alternative.id(),r.auth(0,r.seller.cityId()));
            assertThrows(SQLException.class,()->r.logistics.proposeReroute(request,order.id(),r.original.id(),r.auth(0,r.seller.cityId())));
            assertThrows(SQLException.class,()->r.logistics.proposeReroute(UUID.randomUUID(),order.id(),r.alternative.id(),r.auth(0,r.buyer.cityId())));
            assertThrows(SQLException.class,()->r.logistics.acceptReroute(request,r.auth(24,r.buyer.cityId())));
            UUID replacement=UUID.randomUUID();r.logistics.proposeReroute(replacement,order.id(),r.alternative.id(),r.auth(24,r.buyer.cityId()));assertEquals(RerouteStatus.EXPIRED,r.logistics.reroute(request).orElseThrow().status());
            assertEquals(1,r.logistics.pendingReroutes(r.seller.cityId(),10,0).size());assertEquals(RerouteStatus.APPLIED,r.logistics.acceptReroute(replacement,r.auth(24,r.seller.cityId())).status());assertEquals(0,r.logistics.order(order.id()).orElseThrow().progressedPeriods());
        }
    }

    @Test void conquestAndAuditFailureCannotApplyOrPartiallyMutateReroute()throws Exception{
        try(Rig r=new Rig("atomic.db")){
            Order order=r.order();UUID request=UUID.randomUUID();r.logistics.proposeReroute(request,order.id(),r.alternative.id(),r.auth(0,r.seller.cityId()));
            r.store.execute("CREATE TRIGGER reject_reroute BEFORE INSERT ON audit WHEN NEW.action LIKE 'TRADE REROUTE APPLY%' BEGIN SELECT RAISE(ABORT,'audit rollback boundary'); END");
            assertThrows(SQLException.class,()->r.logistics.acceptReroute(request,r.auth(1,r.buyer.cityId())));assertEquals(r.original.id(),r.logistics.order(order.id()).orElseThrow().routeId());assertEquals(RerouteStatus.PENDING,r.logistics.reroute(request).orElseThrow().status());
            r.store.execute("DROP TRIGGER reject_reroute");UUID conqueror=UUID.randomUUID();r.store.ensureCity(new CoreCity(r.buyer.cityId(),conqueror,"乙城","新国",1,"current"));AuthoritySnapshot old=r.auth(1,r.buyer.cityId());
            AuthoritySnapshot changed=new AuthoritySnapshot(old.actor(),old.period(),Map.of(r.seller.cityId(),r.seller.nationId(),r.buyer.cityId(),conqueror),old.managedCities(),old.controlledAnchors(),old.validNodes(),old.permittedTrades());assertThrows(SQLException.class,()->r.logistics.acceptReroute(request,changed));assertEquals(980,r.stock(r.seller,BREAD));assertEquals(995,r.stock(r.buyer,IRON));
        }
    }

    @Test void trafficShowsSharedNodeBottleneckAndMaintenanceWithoutChargingForRead()throws Exception{
        try(Rig r=new Rig("traffic.db")){
            Order order=r.order();Route parallel=r.logistics.createRoute(UUID.randomUUID(),r.seller.cityId(),r.buyer.cityId(),r.original.nodes(),Mode.LAND,r.auth(0,r.seller.cityId()));
            r.logistics.settle(r.auth(1),10);long planks=r.stock(r.seller,PLANKS);TransportStatus status=r.logistics.transportStatus(parallel.id(),1);
            assertEquals(0,status.routeUsed());assertEquals(231,status.available());assertEquals(Set.copyOf(r.original.nodes()),Set.copyOf(status.bottlenecks()));assertTrue(status.nodes().stream().allMatch(NodeTraffic::maintenancePaid));assertEquals(planks,r.stock(r.seller,PLANKS));
            r.logistics.setNodeEnabled(r.original.nodes().getFirst(),false,r.auth(1,r.seller.cityId()));r.logistics.settle(r.auth(2),10);status=r.logistics.transportStatus(r.original.id(),2);assertEquals(0,status.available());assertEquals(1,status.pausedOrders());assertTrue(status.pausedReasons().keySet().stream().anyMatch(reason->reason.contains("节点")));assertFalse(status.nodes().getFirst().enabled());assertEquals(OrderStatus.PAUSED,r.logistics.order(order.id()).orElseThrow().status());
        }
    }

    @Test void pendingConsentSurvivesRestartAndAppliedReplayDoesNotRestartTheJourneyAgain()throws Exception{
        Path db;UUID order,proposal=UUID.randomUUID(),route,actor,seller,buyer,sellerNation,buyerNation;Set<String> anchors;Set<UUID> nodes;
        try(Rig r=new Rig("consent-restart.db")){
            db=r.db;order=r.order().id();route=r.alternative.id();actor=r.actor;seller=r.seller.cityId();buyer=r.buyer.cityId();sellerNation=r.seller.nationId();buyerNation=r.buyer.nationId();anchors=Set.copyOf(r.anchors);nodes=Set.copyOf(r.nodes);
            r.logistics.proposeReroute(proposal,order,route,r.auth(0,seller));
        }
        try(WorldStore store=new WorldStore(db,10000)){
            LogisticsService logistics=new LogisticsService(store,LogisticsDefinitions.defaults());AuthoritySnapshot auth=new AuthoritySnapshot(actor,1,Map.of(seller,sellerNation,buyer,buyerNation),Set.of(buyer),anchors,nodes,Set.of(TradePair.of(seller,buyer)));
            assertEquals(RerouteStatus.PENDING,logistics.pendingReroutes(buyer,10,0).getFirst().status());logistics.acceptReroute(proposal,auth);assertEquals(route,logistics.order(order).orElseThrow().routeId());
            AuthoritySnapshot next=new AuthoritySnapshot(actor,2,auth.cityNations(),auth.managedCities(),anchors,nodes,auth.permittedTrades());logistics.settle(next,10);logistics.acceptReroute(proposal,next);assertEquals(1,logistics.order(order).orElseThrow().progressedPeriods());assertEquals(0,logistics.settle(next,10).inspected());
        }
    }

    @Test void paidShipmentConsumesReservationOnceAndCannotBarterRefundAfterReceivingMoney()throws Exception{
        try(Rig r=new Rig("paid.db")){
            AtomicInteger consume=new AtomicInteger();UUID id=UUID.randomUUID();String key="market:test:one";
            Order first=r.logistics.createPaidShipment(id,r.alternative.id(),key,Map.of(BREAD,20L),r.auth(0,r.buyer.cityId()),()->{consume.incrementAndGet();r.store.execute("INSERT INTO audit(at,actor,action) VALUES(0,'reservation','consumed')");});
            assertTrue(first.requested().isEmpty());assertEquals(1000,r.stock(r.seller,BREAD),"Reservation was debited by the market before this port, never debit warehouse twice");
            assertEquals(first,r.logistics.createPaidShipment(id,r.alternative.id(),key,Map.of(BREAD,20L),r.auth(0,r.buyer.cityId()),()->fail("Claim replay")));assertEquals(1,consume.get());
            assertThrows(SQLException.class,()->r.logistics.createPaidShipment(UUID.randomUUID(),r.alternative.id(),key,Map.of(BREAD,20L),r.auth(0,r.buyer.cityId()),()->fail("Duplicate core payment")));
            assertThrows(SQLException.class,()->r.logistics.createPaidShipment(id,r.alternative.id(),key,Map.of(BREAD,21L),r.auth(0,r.buyer.cityId()),()->fail("Payload conflict")));
            assertThrows(SQLException.class,()->r.logistics.cancel(id,r.auth(0,r.seller.cityId())));assertThrows(SQLException.class,()->r.logistics.cancel(id,r.auth(0,r.buyer.cityId())));
            r.logistics.settle(r.auth(1),10);assertEquals(1,r.logistics.settle(r.auth(2),10).completed());assertEquals(1020,r.stock(r.buyer,BREAD));assertEquals(1000,r.stock(r.seller,IRON));assertEquals(1,r.logistics.completedAfter(0,10).size());
        }
    }

    @Test void paidReservationAndShipmentRollbackTogetherThenRestartDeliversOnlyOnce()throws Exception{
        Path db;UUID id=UUID.randomUUID(),route,actor,seller,buyer,sellerNation,buyerNation;Set<String> anchors;Set<UUID> nodes;
        try(Rig r=new Rig("paid-rollback.db")){
            db=r.db;route=r.alternative.id();actor=r.actor;seller=r.seller.cityId();buyer=r.buyer.cityId();sellerNation=r.seller.nationId();buyerNation=r.buyer.nationId();anchors=Set.copyOf(r.anchors);nodes=Set.copyOf(r.nodes);
            r.store.execute("CREATE TABLE test_reserved(amount INTEGER NOT NULL)");r.store.execute("INSERT INTO test_reserved VALUES(20)");r.store.execute("CREATE TRIGGER reject_paid BEFORE INSERT ON logistics_paid_shipment BEGIN SELECT RAISE(ABORT,'post-reservation failure'); END");
            assertThrows(SQLException.class,()->r.logistics.createPaidShipment(id,route,"market:rollback",Map.of(BREAD,20L),r.auth(0,r.buyer.cityId()),()->r.store.execute("UPDATE test_reserved SET amount=0")));
            assertEquals(20,r.store.query("SELECT amount FROM test_reserved",s->s.getInt(1)).getFirst());assertTrue(r.logistics.order(id).isEmpty());r.store.execute("DROP TRIGGER reject_paid");
            r.logistics.createPaidShipment(id,route,"market:rollback",Map.of(BREAD,20L),r.auth(0,r.buyer.cityId()),()->r.store.execute("UPDATE test_reserved SET amount=0"));r.logistics.settle(r.auth(1),10);
        }
        try(WorldStore store=new WorldStore(db,10000)){
            LogisticsService logistics=new LogisticsService(store,LogisticsDefinitions.defaults());AuthoritySnapshot auth=new AuthoritySnapshot(actor,2,Map.of(seller,sellerNation,buyer,buyerNation),Set.of(buyer),anchors,nodes,Set.of(TradePair.of(seller,buyer)));
            assertEquals(1,logistics.settle(auth,10).completed());assertThrows(SQLException.class,()->logistics.cancel(id,auth));assertEquals(1020,store.stock(buyer).get(BREAD));assertEquals(0,store.query("SELECT amount FROM test_reserved",s->s.getInt(1)).getFirst());assertEquals(1,logistics.completedAfter(0,10).size());assertEquals(0,logistics.settle(auth,10).inspected());
        }
    }

    @Test void paidCargoNeverExpiresOrRefundsDuringWarAndResumesOnlyAfterPeace()throws Exception{
        try(Rig r=new Rig("paid-war.db")){
            Order shipment=r.logistics.createPaidShipment(UUID.randomUUID(),r.alternative.id(),"market:war",Map.of(BREAD,20L),r.auth(0,r.buyer.cityId()),()->{});AuthoritySnapshot a=r.auth(100);
            AuthoritySnapshot war=new AuthoritySnapshot(a.actor(),a.period(),a.cityNations(),a.managedCities(),a.controlledAnchors(),a.validNodes(),Set.of());assertEquals(1,r.logistics.settle(war,10).paused());
            Order paused=r.logistics.order(shipment.id()).orElseThrow();assertEquals(OrderStatus.PAUSED,paused.status());assertEquals(0,paused.progressedPeriods());assertEquals(1000,r.stock(r.seller,BREAD));assertEquals(1000,r.stock(r.buyer,BREAD));
            assertThrows(SQLException.class,()->r.logistics.cancel(shipment.id(),r.auth(100,r.buyer.cityId())));assertEquals(1,r.logistics.settle(r.auth(101),10).progressed());assertEquals(1,r.logistics.settle(r.auth(102),10).completed());assertEquals(1020,r.stock(r.buyer,BREAD));
        }
    }
}

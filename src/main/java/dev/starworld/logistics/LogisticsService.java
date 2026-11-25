package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.persistence.WorldStore;
import java.sql.*;
import java.util.*;

/**
 * Serialized physical barter and transport. All warehouse changes and receipts share WorldStore's
 * transaction. The caller captures authority on the server thread; this class never accesses Bukkit.
 */
public final class LogisticsService {
    private final WorldStore store;
    private final LogisticsDefinitions definitions;

    public LogisticsService(WorldStore store, LogisticsDefinitions definitions) throws SQLException {
        this.store = Objects.requireNonNull(store); this.definitions = Objects.requireNonNull(definitions);
        store.tx(s -> {
            s.execute("CREATE TABLE IF NOT EXISTS logistics_node(id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,type TEXT NOT NULL,world TEXT NOT NULL,world_name TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,terrain TEXT NOT NULL,level INTEGER NOT NULL,enabled INTEGER NOT NULL,last_maintenance INTEGER NOT NULL DEFAULT -1,maintained INTEGER NOT NULL DEFAULT 0,UNIQUE(world,x,y,z))");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_node_city ON logistics_node(city)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_route(id TEXT PRIMARY KEY,origin_city TEXT NOT NULL REFERENCES city_state(id),target_city TEXT NOT NULL REFERENCES city_state(id),mode TEXT NOT NULL,enabled INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_route_origin ON logistics_route(origin_city)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_route_target ON logistics_route(target_city)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_route_planning ON logistics_route(origin_city,target_city,mode,id)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_corridor(route TEXT PRIMARY KEY REFERENCES logistics_route(id),fingerprint TEXT NOT NULL,created_period INTEGER NOT NULL,actor TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_route_node(route TEXT NOT NULL REFERENCES logistics_route(id),position INTEGER NOT NULL,node TEXT NOT NULL REFERENCES logistics_node(id),PRIMARY KEY(route,position),UNIQUE(route,node))");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_order(id TEXT PRIMARY KEY,route TEXT NOT NULL REFERENCES logistics_route(id),origin_city TEXT NOT NULL REFERENCES city_state(id),target_city TEXT NOT NULL REFERENCES city_state(id),origin_nation TEXT NOT NULL,target_nation TEXT NOT NULL,state TEXT NOT NULL,created_period INTEGER NOT NULL,expires_period INTEGER NOT NULL,last_period INTEGER NOT NULL,progress INTEGER NOT NULL,travel INTEGER NOT NULL,completed_period INTEGER NOT NULL DEFAULT -1,reason TEXT NOT NULL DEFAULT '')");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_order_pending ON logistics_order(state,last_period,id)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_order_fair ON logistics_order(state,last_period,created_period,id)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_order_origin ON logistics_order(origin_city)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_order_target ON logistics_order(target_city)");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_order_route_state ON logistics_order(route,state)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_cargo(order_id TEXT NOT NULL REFERENCES logistics_order(id),side TEXT NOT NULL,item TEXT NOT NULL,amount INTEGER NOT NULL CHECK(amount>0),PRIMARY KEY(order_id,side,item))");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_route_usage(route TEXT NOT NULL REFERENCES logistics_route(id),period INTEGER NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(route,period))");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_node_usage(node TEXT NOT NULL REFERENCES logistics_node(id),period INTEGER NOT NULL,used INTEGER NOT NULL,PRIMARY KEY(node,period))");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_receipt(sequence INTEGER PRIMARY KEY AUTOINCREMENT,order_id TEXT NOT NULL UNIQUE REFERENCES logistics_order(id),period INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_demand(city TEXT NOT NULL REFERENCES city_state(id),item TEXT NOT NULL,desired INTEGER NOT NULL CHECK(desired>=0),PRIMARY KEY(city,item))");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_clock(id INTEGER PRIMARY KEY CHECK(id=1),period INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_reroute(id TEXT PRIMARY KEY,order_id TEXT NOT NULL REFERENCES logistics_order(id),previous_route TEXT NOT NULL REFERENCES logistics_route(id),proposed_route TEXT NOT NULL REFERENCES logistics_route(id),proposer_city TEXT NOT NULL REFERENCES city_state(id),created_period INTEGER NOT NULL,expires_period INTEGER NOT NULL,state TEXT NOT NULL,applied_period INTEGER NOT NULL DEFAULT -1)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS logistics_reroute_pending ON logistics_reroute(order_id) WHERE state='PENDING'");
            s.execute("CREATE INDEX IF NOT EXISTS logistics_reroute_expiry ON logistics_reroute(state,expires_period)");
            s.execute("CREATE TABLE IF NOT EXISTS logistics_paid_shipment(order_id TEXT PRIMARY KEY REFERENCES logistics_order(id),settlement_key TEXT NOT NULL UNIQUE,initial_route TEXT NOT NULL REFERENCES logistics_route(id))");
            s.execute("INSERT OR IGNORE INTO logistics_clock(id,period) VALUES(1,-1)");
            return null;
        });
    }

    public LogisticsDefinitions definitions() { return definitions; }

    public Optional<Node> node(UUID id) throws SQLException { return store.query("SELECT * FROM logistics_node WHERE id=?", this::readNode, id).stream().findFirst(); }
    public List<Node> nodes(int limit, int offset) throws SQLException { page(limit, offset); return store.query("SELECT * FROM logistics_node ORDER BY id LIMIT ? OFFSET ?", this::readNode, limit, offset); }
    public List<Node> cityNodes(UUID city) throws SQLException { return store.query("SELECT * FROM logistics_node WHERE city=? ORDER BY id LIMIT ?", this::readNode, city, definitions.maxNodesPerCity()); }
    public Optional<Route> route(UUID id) throws SQLException { return store.query("SELECT * FROM logistics_route WHERE id=?", this::readRoute, id).stream().findFirst(); }
    public List<Route> routes(int limit, int offset) throws SQLException { page(limit, offset); return store.query("SELECT * FROM logistics_route ORDER BY id LIMIT ? OFFSET ?", this::readRoute, limit, offset); }
    public List<Route> cityRoutes(UUID city, int limit, int offset) throws SQLException { page(limit, offset); return store.query("SELECT * FROM logistics_route WHERE origin_city=? OR target_city=? ORDER BY id LIMIT ? OFFSET ?", this::readRoute, city, city, limit, offset); }
    /**
     * Deterministic planning comparison for already declared routes. This is a read-only
     * projection: it does not check current claims/diplomacy and cannot authorize transport.
     */
    public List<RoutePlan> compareRoutes(UUID origin, UUID target, Mode mode, int limit, int offset) throws SQLException {
        if (limit < 1 || limit > 100 || offset < 0) throw new IllegalArgumentException("Invalid route planning page");
        Objects.requireNonNull(origin); Objects.requireNonNull(target); Objects.requireNonNull(mode);
        if (origin.equals(target)) throw new IllegalArgumentException("路线规划需要不同的起点和终点城市");
        record Header(UUID id, boolean enabled) {}
        record Link(UUID route, int position, UUID node) {}
        List<Header> declared = store.query("SELECT id,enabled FROM logistics_route WHERE origin_city=? AND target_city=? AND mode=? ORDER BY id LIMIT ?", row -> new Header(uuid(row,"id"),row.getInt("enabled")==1),
                origin, target, mode.name(), definitions.maxRoutesPerCity() + 1);
        if (declared.size() > definitions.maxRoutesPerCity()) throw new IllegalArgumentException("城市商路数量超过当前规划上限");
        if (declared.isEmpty()) return List.of();
        // One bounded read per relation, not one node query per route/waypoint. Node registration
        // already caps each endpoint; corrupt/legacy excess is rejected rather than scanned forever.
        List<Node> declaredNodes = store.query("SELECT * FROM logistics_node WHERE city IN (?,?) ORDER BY id LIMIT ?",this::readNode,
                origin,target,Math.addExact(Math.multiplyExact(definitions.maxNodesPerCity(),2),1));
        if(declaredNodes.size()>definitions.maxNodesPerCity()*2)throw new IllegalArgumentException("端点交通节点数量超过当前规划上限");
        Map<UUID,Node> nodesById=new HashMap<>();declaredNodes.forEach(n->nodesById.put(n.id(),n));
        List<Link> links=store.query("SELECT rn.route,rn.position,rn.node FROM logistics_route_node rn JOIN logistics_route r ON r.id=rn.route WHERE r.origin_city=? AND r.target_city=? AND r.mode=? ORDER BY rn.route,rn.position LIMIT ?",
                row->new Link(uuid(row,"route"),row.getInt("position"),uuid(row,"node")),origin,target,mode.name(),Math.addExact(Math.multiplyExact(definitions.maxRoutesPerCity(),64),1));
        Map<UUID,List<UUID>> paths=new HashMap<>();
        for(Link link:links){List<UUID> path=paths.computeIfAbsent(link.route(),ignored->new ArrayList<>());if(link.position()!=path.size()||path.size()>=64)throw new IllegalArgumentException("声明路线节点序列超过持久化安全上限或不连续");path.add(link.node());}
        List<RoutePlan> unsorted=new ArrayList<>();
        for(Header header:declared){List<UUID> path=paths.getOrDefault(header.id(),List.of());List<Node> nodes=new ArrayList<>();for(UUID id:path){Node node=nodesById.get(id);if(node==null)throw new IllegalArgumentException("声明路线引用了端点城市之外或缺失的节点");nodes.add(node);}
            Metrics metrics=metrics(nodes,mode,false);
            if(nodes.size()>definitions.maxWaypoints()||metrics.periods()>definitions.maxTravelPeriods())continue;
            Map<UUID,Map<String,Long>> upkeep=new HashMap<>(),journey=new HashMap<>();
            for(Node node:nodes){Map<String,Long> cityCost=upkeep.computeIfAbsent(node.cityId(),ignored->new TreeMap<>());scale(definitions.nodes().get(node.type()).maintenance(),node.level()).forEach((item,amount)->cityCost.merge(item,amount,Math::addExact));}
            upkeep.forEach((city,items)->{Map<String,Long> total=new TreeMap<>();items.forEach((item,amount)->total.put(item,Math.multiplyExact(amount,metrics.periods())));journey.put(city,total);});
            unsorted.add(new RoutePlan(header.id(),origin,target,mode,header.enabled(),metrics.distance(),metrics.periods(),metrics.capacity(),nodes.size(),upkeep,journey));
        }
        List<RoutePlan> plans = unsorted.stream().sorted(Comparator
                        .comparingInt(RoutePlan::travelPeriods)
                        .thenComparingDouble(RoutePlan::distanceBlocks)
                        .thenComparing(Comparator.comparingLong(RoutePlan::capacity).reversed())
                        .thenComparing(Comparator.comparingInt(RoutePlan::nodeCount))
                        .thenComparing(RoutePlan::routeId)).toList();
        int end = (int) Math.min(plans.size(), (long) offset + limit);
        return plans.subList(Math.min(offset, plans.size()), end);
    }
    public Optional<Order> order(UUID id) throws SQLException { return store.query("SELECT * FROM logistics_order WHERE id=?", this::readOrder, id).stream().findFirst(); }
    /** Current operational limits, never a substitute for live ownership/anchor/diplomacy authorization. */
    public Optional<String> routeLimitation(Route route) {
        Objects.requireNonNull(route);
        if(route.nodes().size()>definitions.maxWaypoints())return Optional.of("商路节点数超过当前配置上限；原路线与托管保留，可停用、撤单或双方改道");
        if(route.travelPeriods()>definitions.maxTravelPeriods())return Optional.of("商路超过当前配置的最大运输时长；原路线与托管保留，可停用、撤单或双方改道");
        return Optional.empty();
    }
    public List<Order> pendingOrders(int limit, int offset) throws SQLException {
        page(limit, offset); return store.query("SELECT * FROM logistics_order WHERE state IN ('OFFERED','IN_TRANSIT','PAUSED') ORDER BY last_period,created_period,id LIMIT ? OFFSET ?", this::readOrder, limit, offset);
    }
    public List<Order> dueOrders(long period, int limit) throws SQLException {
        if (period < 0 || limit < 1 || limit > definitions.maxBatch()) throw new IllegalArgumentException("Invalid due-order batch");
        return store.query("SELECT * FROM logistics_order WHERE state IN ('OFFERED','IN_TRANSIT','PAUSED') AND last_period<? ORDER BY last_period,created_period,id LIMIT ?", this::readOrder, period, limit);
    }
    public List<Order> cityOrders(UUID city, int limit, int offset) throws SQLException {
        page(limit, offset); return store.query("SELECT * FROM logistics_order WHERE origin_city=? OR target_city=? ORDER BY created_period DESC,id LIMIT ? OFFSET ?", this::readOrder, city, city, limit, offset);
    }

    /** Bounded candidates preserve the exact direction and use only already declared routes. No world search occurs. */
    public List<Route> alternativeRoutes(UUID orderId, AuthoritySnapshot auth, int limit) throws SQLException {
        if(limit<1 || limit>16)throw new IllegalArgumentException("替代商路每次最多十六条");
        return store.tx(s->{
            requireFreshRead(auth);Order o=order(orderId).orElseThrow();requireOwners(o,auth);endpointManager(o,auth);requireTrade(o.originCity(),o.targetCity(),auth);
            // This indexed set cannot exceed the configured origin-city route limit.
            List<Route> candidates=declaredAlternatives(orderId,16);
            List<Route> result=new ArrayList<>();
            for(Route r:candidates){try{requireRoute(r,auth);if(o.cargoUnits()<=r.capacity())result.add(r);}catch(IllegalArgumentException invalid){/* Omitted authority never means valid. */}}
            result.sort(Comparator.comparingInt(Route::travelPeriods).thenComparing(Comparator.comparingLong(Route::capacity).reversed()).thenComparing(Route::id));
            return List.copyOf(result.subList(0,Math.min(limit,result.size())));
        });
    }

    /** At most sixteen persisted paths for a controller's live capture, not a validity or trade permission grant. */
    public List<Route> declaredAlternatives(UUID orderId,int limit)throws SQLException{
        if(limit<1||limit>16)throw new IllegalArgumentException("替代商路每次最多十六条");Order o=order(orderId).orElseThrow();
        return store.query("SELECT * FROM logistics_route WHERE origin_city=? AND target_city=? AND id<>? AND enabled=1 ORDER BY id LIMIT ?",this::readRoute,o.originCity(),o.targetCity(),o.routeId(),limit);
    }

    public Optional<Reroute> reroute(UUID id)throws SQLException{return store.query("SELECT * FROM logistics_reroute WHERE id=?",this::readReroute,id).stream().findFirst();}
    public List<Reroute> pendingReroutes(UUID city,int limit,int offset)throws SQLException{
        page(limit,offset);return store.query("SELECT r.* FROM logistics_reroute r JOIN logistics_order o ON o.id=r.order_id WHERE (o.origin_city=? OR o.target_city=?) AND r.state='PENDING' ORDER BY r.created_period,r.id LIMIT ? OFFSET ?",this::readReroute,city,city,limit,offset);
    }

    /** Proposal does not move or refund cargo, reserve capacity, pay upkeep or change either route. */
    public Reroute proposeReroute(UUID requestId,UUID orderId,UUID proposedRoute,AuthoritySnapshot auth)throws SQLException{
        Objects.requireNonNull(requestId);Objects.requireNonNull(orderId);Objects.requireNonNull(proposedRoute);
        return store.tx(s->{
            fresh(auth);Order o=order(orderId).orElseThrow();requireOwners(o,auth);UUID proposer=endpointManager(o,auth);
            Optional<Reroute> previous=reroute(requestId);
            if(previous.isPresent()){
                Reroute saved=previous.get();
                if(!saved.orderId().equals(orderId)||!saved.proposedRouteId().equals(proposedRoute)||!auth.managedCities().contains(saved.proposerCity()))throw new IllegalArgumentException("改道请求ID已用于其他参数或城市");
                expireReroute(saved,auth.period());return reroute(requestId).orElseThrow();
            }
            requireMoving(o);if(o.routeId().equals(proposedRoute))throw new IllegalArgumentException("货单已在该商路，无需改道");
            Route next=route(proposedRoute).orElseThrow();requireAlternative(o,next,auth);
            // A stale proposal cannot block a new one forever, but its durable ID is never reused.
            s.execute("UPDATE logistics_reroute SET state='EXPIRED' WHERE order_id=? AND state='PENDING' AND expires_period<=?",orderId,auth.period());
            if(count("SELECT COUNT(*) FROM logistics_reroute WHERE order_id=? AND state='PENDING'",orderId)>0)throw new IllegalArgumentException("该货单已有待确认改道，请先接受、取消或等待到期");
            s.execute("INSERT INTO logistics_reroute(id,order_id,previous_route,proposed_route,proposer_city,created_period,expires_period,state) VALUES(?,?,?,?,?,?,?,'PENDING')",requestId,orderId,o.routeId(),proposedRoute,proposer,auth.period(),Math.addExact(auth.period(),definitions.offerLifetimePeriods()));
            s.audit(auth.actor().toString(),"TRADE REROUTE PROPOSE "+requestId+" "+orderId+" "+proposedRoute);return reroute(requestId).orElseThrow();
        });
    }

    /** The other endpoint accepts new maintenance costs. Full travel restarts; spent old capacity stays spent. */
    public Reroute acceptReroute(UUID requestId,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{
            fresh(auth);Reroute proposal=reroute(requestId).orElseThrow();Order o=order(proposal.orderId()).orElseThrow();requireOwners(o,auth);
            UUID other=proposal.proposerCity().equals(o.originCity())?o.targetCity():o.originCity();manager(other,auth);
            if(proposal.status()==RerouteStatus.APPLIED)return proposal;
            if(proposal.status()!=RerouteStatus.PENDING || auth.period()>=proposal.expiresPeriod())throw new IllegalArgumentException("改道请求已取消或到期");
            requireMoving(o);if(!o.routeId().equals(proposal.previousRouteId()))throw new IllegalArgumentException("货单路径已改变，请重新提议");
            Route next=route(proposal.proposedRouteId()).orElseThrow();requireAlternative(o,next,auth);
            s.execute("UPDATE logistics_order SET route=?,state='IN_TRANSIT',last_period=?,progress=0,travel=?,reason='双方已确认改道，完整新行程待下一周期开始' WHERE id=?",next.id(),auth.period(),next.travelPeriods(),o.id());
            s.execute("UPDATE logistics_reroute SET state='APPLIED',applied_period=? WHERE id=?",auth.period(),requestId);
            s.audit(auth.actor().toString(),"TRADE REROUTE APPLY "+requestId+" "+o.id());return reroute(requestId).orElseThrow();
        });
    }

    public Reroute cancelReroute(UUID requestId,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{fresh(auth);Reroute proposal=reroute(requestId).orElseThrow();Order o=order(proposal.orderId()).orElseThrow();requireOwners(o,auth);endpointManager(o,auth);
            if(proposal.status()==RerouteStatus.APPLIED)throw new IllegalArgumentException("已确认改道不能撤回；请对当前货单重新提议");
            if(proposal.status()==RerouteStatus.PENDING)s.execute("UPDATE logistics_reroute SET state=? WHERE id=?",auth.period()>=proposal.expiresPeriod()?"EXPIRED":"CANCELLED",requestId);
            return reroute(requestId).orElseThrow();});
    }

    /** Trusted internal market port. Must consume a verified, already paid reservation in this same transaction. */
    @FunctionalInterface public interface ReservedCargoClaim { void consume() throws Exception; }

    /**
     * Moves a market reservation into one-way transport escrow after a durable core payment receipt.
     * The callback validates/consumes the market reservation; no city warehouse is debited a second time.
     * This is not a permission-free player API. A paid shipment cannot use barter cancellation/refunds.
     */
    public Order createPaidShipment(UUID shipmentId,UUID routeId,String settlementKey,Map<String,Long> goods,AuthoritySnapshot auth,ReservedCargoClaim claim)throws SQLException{
        Objects.requireNonNull(shipmentId);Objects.requireNonNull(routeId);Objects.requireNonNull(claim);Map<String,Long> cargo=cargo(goods);
        if(cargo.isEmpty()||cargo.size()>definitions.maxItemsPerOrder()||settlementKey==null||!settlementKey.matches("[A-Za-z0-9:_./-]{1,160}"))throw new IllegalArgumentException("Invalid paid shipment payload");
        return store.tx(s->{
            fresh(auth);Route route=route(routeId).orElseThrow();UUID originNation=owner(route.originCity(),auth),targetNation=manager(route.targetCity(),auth);
            Optional<Order> previous=order(shipmentId);List<String> byKey=s.query("SELECT order_id FROM logistics_paid_shipment WHERE settlement_key=?",r->r.getString(1),settlementKey);
            if(previous.isPresent()){
                Order order=previous.get();List<String[]> marker=s.query("SELECT settlement_key,initial_route FROM logistics_paid_shipment WHERE order_id=?",r->new String[]{r.getString(1),r.getString(2)},shipmentId);
                if(marker.isEmpty()||!marker.getFirst()[0].equals(settlementKey)||!marker.getFirst()[1].equals(routeId.toString())||!order.originNation().equals(originNation)||!order.targetNation().equals(targetNation)||!order.offered().equals(cargo)||!order.requested().isEmpty())throw new IllegalArgumentException("已支付货运ID与原支付/货物不一致");
                return order;
            }
            if(!byKey.isEmpty())throw new IllegalArgumentException("该支付已用于另一货运ID");requireRoute(route,auth);
            if(units(cargo,Map.of())>route.capacity())throw new IllegalArgumentException("货物超过当前商路单周期容量");
            for(UUID city:List.of(route.originCity(),route.targetCity()))if(count("SELECT COUNT(*) FROM logistics_order WHERE (origin_city=? OR target_city=?) AND state IN ('OFFERED','IN_TRANSIT','PAUSED','REFUNDING')",city,city)>=definitions.maxOpenOrdersPerCity())throw new IllegalArgumentException("城市未完成货单已达上限");
            claim.consume();
            s.execute("INSERT INTO logistics_order(id,route,origin_city,target_city,origin_nation,target_nation,state,created_period,expires_period,last_period,progress,travel,reason) VALUES(?,?,?,?,?,?,'IN_TRANSIT',?,?,?,0,?,'国家支付已确认，实物在运输托管')",shipmentId,routeId,route.originCity(),route.targetCity(),originNation,targetNation,auth.period(),Math.addExact(auth.period(),definitions.offerLifetimePeriods()),auth.period(),route.travelPeriods());
            writeCargo(shipmentId,"OFFERED",cargo);s.execute("INSERT INTO logistics_paid_shipment(order_id,settlement_key,initial_route) VALUES(?,?,?)",shipmentId,settlementKey,routeId);
            s.audit(auth.actor().toString(),"PAID SHIPMENT "+shipmentId+" "+settlementKey);return order(shipmentId).orElseThrow();
        });
    }
    public boolean paidShipment(UUID orderId)throws SQLException{return count("SELECT COUNT(*) FROM logistics_paid_shipment WHERE order_id=?",orderId)>0;}

    /** Trusted market transaction: stop delivery before a separately committed treasury reversal. */
    public Order holdPaidRefund(UUID orderId,String payment,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{fresh(auth);Order order=paidRefundOrder(orderId,payment,auth);
            if(order.status()==OrderStatus.REFUNDING)return order;
            if(order.status()!=OrderStatus.IN_TRANSIT&&order.status()!=OrderStatus.PAUSED)throw new IllegalArgumentException("已交付或终止的货物不能申请付款退款");
            s.execute("UPDATE logistics_order SET state='REFUNDING',reason='双方已确认退款，货物冻结等待原付款逆向回执' WHERE id=?",orderId);
            s.execute("UPDATE logistics_reroute SET state='CANCELLED' WHERE order_id=? AND state='PENDING'",orderId);
            return order(orderId).orElseThrow();});
    }

    /** Called only after market verifies the exact durable refund; full warehouse keeps the hold. */
    public Order returnPaidRefund(UUID orderId,String payment,Map<String,Long> expected,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{fresh(auth);Order order=paidRefundOrder(orderId,payment,auth);
            if(!order.offered().equals(expected)||!order.requested().isEmpty())throw new IllegalArgumentException("退款货物与原托管不符");
            if(order.status()==OrderStatus.CANCELLED)return order;
            if(order.status()!=OrderStatus.REFUNDING)throw new IllegalArgumentException("退款货物尚未冻结或已交付");
            if(!canCredit(order.originCity(),order.offered()))throw new IllegalArgumentException("卖方仓储已满，已退款商品继续托管");
            credit(order.originCity(),order.offered());
            s.execute("UPDATE logistics_order SET state='CANCELLED',last_period=?,reason='原国家付款已退还，托管商品归还原卖城' WHERE id=?",auth.period(),orderId);
            s.audit("market-refund","PAID CARGO RETURN "+orderId+" "+payment);return order(orderId).orElseThrow();});
    }
    private Order paidRefundOrder(UUID id,String payment,AuthoritySnapshot auth)throws SQLException{
        Order order=order(id).orElseThrow();requireOwners(order,auth);
        if(count("SELECT COUNT(*) FROM logistics_paid_shipment WHERE order_id=? AND settlement_key=?",id,payment)!=1)throw new IllegalArgumentException("退款不匹配原已支付货运凭据");
        return order;
    }

    /** Read-only persisted counters; caller must capture live authority separately before any transport action. */
    public TransportStatus transportStatus(UUID routeId,long period)throws SQLException{
        if(period<0)throw new IllegalArgumentException("Negative transport period");
        return store.tx(s->{Route route=route(routeId).orElseThrow();long used=count("SELECT COALESCE(MAX(used),0) FROM logistics_route_usage WHERE route=? AND period=?",routeId,period);long available=route.enabled()&&routeLimitation(route).isEmpty()?Math.max(0,route.capacity()-used):0;List<NodeTraffic> traffic=new ArrayList<>();
            for(UUID id:route.nodes()){Node n=node(id).orElseThrow();long capacity=Math.multiplyExact(definitions.nodes().get(n.type()).capacity(),n.level()),nodeUsed=count("SELECT COALESCE(MAX(used),0) FROM logistics_node_usage WHERE node=? AND period=?",id,period);long[] maintenance=s.query("SELECT last_maintenance,maintained FROM logistics_node WHERE id=?",r->new long[]{r.getLong(1),r.getLong(2)},id).getFirst();long remaining=n.enabled()?Math.max(0,capacity-nodeUsed):0;traffic.add(new NodeTraffic(id,n.cityId(),n.type(),capacity,nodeUsed,remaining,maintenance[0],maintenance[0]==period&&maintenance[1]==1,n.enabled()));available=Math.min(available,remaining);}
            long minimum=available;List<UUID> bottlenecks=traffic.stream().filter(n->n.remaining()==minimum).map(NodeTraffic::nodeId).toList();Map<String,Integer> reasons=new LinkedHashMap<>();
            List<long[]> counts=s.query("SELECT state,COUNT(*) FROM logistics_order WHERE route=? AND state IN ('OFFERED','IN_TRANSIT','PAUSED','REFUNDING') GROUP BY state",r->new long[]{Set.of("PAUSED","REFUNDING").contains(r.getString(1))?1:0,r.getLong(2)},routeId);int waiting=0,paused=0;for(long[] row:counts){waiting=Math.addExact(waiting,Math.toIntExact(row[1]));if(row[0]==1)paused=Math.addExact(paused,Math.toIntExact(row[1]));}
            for(var row:s.query("SELECT reason,COUNT(*) FROM logistics_order WHERE route=? AND state IN ('PAUSED','REFUNDING') GROUP BY reason ORDER BY COUNT(*) DESC,reason LIMIT 16",r->Map.entry(r.getString(1),r.getInt(2)),routeId))reasons.put(row.getKey(),row.getValue());
            return new TransportStatus(routeId,period,route.capacity(),used,available,traffic,bottlenecks,waiting,paused,reasons,route.enabled());});
    }

    /** IDs are supplied by the command/API caller and may safely be retried after an uncertain reply. */
    public Node registerNode(UUID id, UUID city, NodeType type, Site site, String terrain, AuthoritySnapshot auth) throws SQLException {
        Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(site);
        return store.tx(s -> {
            fresh(auth);
            UUID nation = manager(city, auth);
            if (!definitions.terrainTime().containsKey(terrain)) throw new IllegalArgumentException("未配置的交通地形");
            if (!auth.controlledAnchors().contains(site.anchorKey())) throw new IllegalArgumentException("节点锚点未通过领地、国家和实体校验");
            Optional<Node> previous = node(id);
            if (previous.isPresent()) {
                Node n = previous.get();
                if (!n.cityId().equals(city) || !n.nationId().equals(nation) || n.type() != type || !n.site().equals(site) || !n.terrain().equals(terrain)) throw new IllegalArgumentException("节点 ID 已用于其他注册请求");
                return n;
            }
            if (count("SELECT COUNT(*) FROM logistics_node WHERE city=?", city) >= definitions.maxNodesPerCity()) throw new IllegalArgumentException("城市交通节点已达上限");
            debit(city, definitions.nodes().get(type).construction());
            s.execute("INSERT INTO logistics_node(id,city,nation,type,world,world_name,x,y,z,terrain,level,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,1,1)", id, city, nation, type.name(), site.worldId(), site.worldName(), site.x(), site.y(), site.z(), terrain);
            s.audit(auth.actor().toString(), "LOGISTICS REGISTER " + id + " " + city + " " + type);
            return node(id).orElseThrow();
        });
    }

    public Node upgradeNode(UUID id, AuthoritySnapshot auth) throws SQLException {
        return store.tx(s -> {
            fresh(auth);
            Node n = node(id).orElseThrow(); manager(n.cityId(), auth); requireNode(n, auth);
            if (n.level() >= 3) throw new IllegalArgumentException("交通节点已达三级");
            debit(n.cityId(), scale(definitions.nodes().get(n.type()).construction(), n.level()));
            s.execute("UPDATE logistics_node SET level=level+1 WHERE id=?", id);
            s.audit(auth.actor().toString(), "LOGISTICS UPGRADE " + id); return node(id).orElseThrow();
        });
    }

    public Node setNodeEnabled(UUID id, boolean enabled, AuthoritySnapshot auth) throws SQLException {
        return store.tx(s -> {
            fresh(auth);
            Node n = node(id).orElseThrow(); manager(n.cityId(), auth); requireOwner(n.cityId(), n.nationId(), auth);
            if (enabled && !auth.validNodes().contains(id)) throw new IllegalArgumentException("节点实体或领土无效");
            s.execute("UPDATE logistics_node SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
            return node(id).orElseThrow();
        });
    }

    /** Only endpoint cities may supply waypoints, so a route cannot charge a third city's warehouse. */
    public Route createRoute(UUID id, UUID origin, UUID target, List<UUID> path, Mode mode, AuthoritySnapshot auth) throws SQLException {
        List<UUID> copy = List.copyOf(path); Objects.requireNonNull(id); Objects.requireNonNull(mode);
        return store.tx(s -> {
            fresh(auth);
            manager(origin, auth); owner(target, auth); requireTrade(origin, target, auth);
            if (copy.size() < 2 || copy.size() > definitions.maxWaypoints() || new HashSet<>(copy).size() != copy.size()) throw new IllegalArgumentException("商路需要二至上限数量的不重复节点");
            List<Node> nodes = new ArrayList<>(); for (UUID nodeId : copy) { Node n = node(nodeId).orElseThrow(); requireNode(n, auth); nodes.add(n); }
            if (!nodes.getFirst().cityId().equals(origin) || !nodes.getLast().cityId().equals(target)) throw new IllegalArgumentException("商路起终点必须属于双方城市");
            for (Node n : nodes) if (!n.cityId().equals(origin) && !n.cityId().equals(target)) throw new IllegalArgumentException("路径不得向第三城市收取维护费");
            metrics(nodes, mode);
            Optional<Route> previous = route(id);
            if (previous.isPresent()) {
                Route r = previous.get();
                if (!r.originCity().equals(origin) || !r.targetCity().equals(target) || !r.nodes().equals(copy) || r.mode() != mode) throw new IllegalArgumentException("商路 ID 已用于其他请求");
                return r;
            }
            if (count("SELECT COUNT(*) FROM logistics_route WHERE origin_city=?", origin) >= definitions.maxRoutesPerCity()) throw new IllegalArgumentException("城市发起商路已达上限");
            s.execute("INSERT INTO logistics_route(id,origin_city,target_city,mode,enabled) VALUES(?,?,?,?,1)", id, origin, target, mode.name());
            for (int i = 0; i < copy.size(); i++) s.execute("INSERT INTO logistics_route_node(route,position,node) VALUES(?,?,?)", id, i, copy.get(i));
            s.audit(auth.actor().toString(), "LOGISTICS ROUTE " + id + " " + origin + " -> " + target);
            return route(id).orElseThrow();
        });
    }

    public Route setRouteEnabled(UUID id, boolean enabled, AuthoritySnapshot auth) throws SQLException {
        return store.tx(s -> { fresh(auth); Route r = route(id).orElseThrow(); manager(r.originCity(), auth); if(enabled)requireRouteLimits(r);s.execute("UPDATE logistics_route SET enabled=? WHERE id=?", enabled ? 1 : 0, id); return route(id).orElseThrow(); });
    }

    /** Permanent stricter operating contract; original declarations and cargo are retained. */
    public Route bindCorridor(UUID id,AuthoritySnapshot auth)throws SQLException{
        return store.tx(s->{
            fresh(auth);Route route=route(id).orElseThrow();manager(route.originCity(),auth);owner(route.targetCity(),auth);requireRoute(route,auth);
            Map<UUID,Node> nodes=new HashMap<>();for(UUID nodeId:route.nodes())nodes.put(nodeId,node(nodeId).orElseThrow());
            Corridor.Plan plan=Corridor.plan(route,nodes);Corridor.Proof proof=auth.corridors().get(id);
            if(proof==null||!proof.pass()||!id.equals(proof.routeId())||!plan.fingerprint().equals(proof.fingerprint()))throw new IllegalArgumentException("绑定通道需要匹配本路线的完整现场证据");
            if(!route.corridorRequired()){
                // Freeze the rule before either party has contracted cargo/payment on this route.
                if(!s.query("SELECT id FROM logistics_order WHERE route=? LIMIT 1",r->r.getString(1),id).isEmpty()
                        ||(count("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='market_purchase'")>0
                        &&!s.query("SELECT id FROM market_purchase WHERE route=? LIMIT 1",r->r.getString(1),id).isEmpty()))
                    throw new IllegalArgumentException("已有货单或购买记录的商路不能变更通道约定；请新建路线后绑定");
                s.execute("INSERT INTO logistics_corridor(route,fingerprint,created_period,actor) VALUES(?,?,?,?)",id,plan.fingerprint(),auth.period(),auth.actor());
                s.audit(auth.actor().toString(),"LOGISTICS CORRIDOR BIND "+id+" "+plan.fingerprint());
            }
            return route(id).orElseThrow();
        });
    }

    /** Internal storage-worker guard shared by barter and market dispatch; never queries Bukkit. */
    public void requireCorridor(Route route,AuthoritySnapshot auth)throws SQLException{
        Corridor.limitation(route,auth.corridors().get(route.id())).ifPresent(reason->{throw new IllegalArgumentException(reason);});
        if(!route.corridorRequired())return;
        String expected=store.query("SELECT fingerprint FROM logistics_corridor WHERE route=?",r->r.getString(1),route.id()).stream().findFirst().orElseThrow();
        if(!expected.equals(auth.corridors().get(route.id()).fingerprint()))throw new IllegalArgumentException("商路通道证据与绑定路线不符");
    }

    public Order createOffer(UUID id, UUID routeId, Map<String, Long> offered, Map<String, Long> requested, AuthoritySnapshot auth) throws SQLException {
        Map<String, Long> give = cargo(offered), take = cargo(requested); Objects.requireNonNull(id);
        if (give.isEmpty() || take.isEmpty() || give.size() + take.size() > definitions.maxItemsPerOrder()) throw new IllegalArgumentException("双向报价均须包含物品且不能超过种类上限");
        return store.tx(s -> {
            fresh(auth);
            Route r = route(routeId).orElseThrow(); UUID originNation = manager(r.originCity(), auth); UUID targetNation = owner(r.targetCity(), auth);
            requireRoute(r, auth);
            Optional<Order> previous = order(id);
            if (previous.isPresent()) {
                Order o = previous.get();
                if (!o.routeId().equals(routeId) || !o.offered().equals(give) || !o.requested().equals(take)) throw new IllegalArgumentException("货单 ID 已用于其他报价");
                return o;
            }
            long units = units(give, take); if (units > r.capacity()) throw new IllegalArgumentException("货单总量超过商路单周期容量");
            for (UUID city : List.of(r.originCity(), r.targetCity())) if (count("SELECT COUNT(*) FROM logistics_order WHERE (origin_city=? OR target_city=?) AND state IN ('OFFERED','IN_TRANSIT','PAUSED','REFUNDING')", city, city) >= definitions.maxOpenOrdersPerCity()) throw new IllegalArgumentException("城市未完成货单已达上限");
            debit(r.originCity(), give);
            s.execute("INSERT INTO logistics_order(id,route,origin_city,target_city,origin_nation,target_nation,state,created_period,expires_period,last_period,progress,travel) VALUES(?,?,?,?,?,?,'OFFERED',?,?,?,0,?)", id, routeId, r.originCity(), r.targetCity(), originNation, targetNation, auth.period(), Math.addExact(auth.period(), definitions.offerLifetimePeriods()), auth.period(), r.travelPeriods());
            writeCargo(id, "OFFERED", give); writeCargo(id, "REQUESTED", take);
            s.audit(auth.actor().toString(), "TRADE OFFER " + id); return order(id).orElseThrow();
        });
    }

    /** Target city's manager consents to the route, its upkeep, and the requested physical price. */
    public Order accept(UUID id, AuthoritySnapshot auth) throws SQLException {
        return store.tx(s -> {
            fresh(auth);
            Order o = order(id).orElseThrow(); manager(o.targetCity(), auth); requireOwners(o, auth);
            if (o.status() == OrderStatus.IN_TRANSIT || o.status() == OrderStatus.PAUSED || o.status() == OrderStatus.COMPLETED) return o;
            if (o.status() != OrderStatus.OFFERED || auth.period() >= o.expiresPeriod()) throw new IllegalArgumentException("报价已取消或过期");
            Route r = route(o.routeId()).orElseThrow(); requireRoute(r, auth);
            if (o.cargoUnits() > r.capacity()) throw new IllegalArgumentException("货单超过当前商路容量，请撤销并拆分报价");
            debit(o.targetCity(), o.requested());
            s.execute("UPDATE logistics_order SET state='IN_TRANSIT',last_period=?,travel=?,reason='' WHERE id=?", auth.period(), r.travelPeriods(), id);
            s.audit(auth.actor().toString(), "TRADE ACCEPT " + id); return order(id).orElseThrow();
        });
    }

    /** Before acceptance only the offerer may cancel. In transit either party may request a full bilateral refund. */
    public Order cancel(UUID id, AuthoritySnapshot auth) throws SQLException {
        return store.tx(s -> {
            fresh(auth);
            Order o = order(id).orElseThrow(); requireOwners(o, auth);
            if(paidShipment(id))throw new IllegalArgumentException("已支付货运不能按实物易货撤销；需原国家支付回执和退款流程核对，实物继续托管");
            if (o.status() == OrderStatus.OFFERED) manager(o.originCity(), auth);
            else if (!auth.managedCities().contains(o.originCity()) && !auth.managedCities().contains(o.targetCity())) throw new IllegalArgumentException("需要货单一方的城市管理权限");
            if (o.status() == OrderStatus.CANCELLED || o.status() == OrderStatus.EXPIRED) return o;
            if (o.status() == OrderStatus.COMPLETED) throw new IllegalArgumentException("已完成贸易不能撤销");
            credit(o.originCity(), o.offered()); if (o.status() != OrderStatus.OFFERED) credit(o.targetCity(), o.requested());
            s.execute("UPDATE logistics_order SET state='CANCELLED',reason='双方托管原物退还' WHERE id=?", id);
            s.execute("UPDATE logistics_reroute SET state='CANCELLED' WHERE order_id=? AND state='PENDING'",id);
            s.audit(auth.actor().toString(), "TRADE CANCEL " + id); return order(id).orElseThrow();
        });
    }

    /**
     * Processes at most limit orders once per period. A skipped offline period never creates movement.
     * Oldest last_period first provides bounded fairness. A complete two-way exchange is one transaction.
     */
    public BatchResult settle(AuthoritySnapshot auth, int limit) throws SQLException {
        return settleCaptured(auth, limit, null);
    }
    /** Exact captured IDs avoid selecting an uncaptured next order if another command cancels one. */
    public BatchResult settleCaptured(AuthoritySnapshot auth, List<UUID> capturedIds) throws SQLException {
        List<UUID> ids = List.copyOf(capturedIds);
        if (ids.isEmpty()) return new BatchResult(auth.period(), 0, 0, 0, 0, 0);
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Duplicate captured orders");
        return settleCaptured(auth, ids.size(), ids);
    }
    private BatchResult settleCaptured(AuthoritySnapshot auth, int limit, List<UUID> capturedIds) throws SQLException {
        if (limit < 1 || limit > definitions.maxBatch()) throw new IllegalArgumentException("超出交通批处理上限");
        return store.tx(s -> {
            fresh(auth);
            List<Order> orders;
            if (capturedIds == null) orders = dueOrders(auth.period(), limit);
            else {
                String placeholders = String.join(",", Collections.nCopies(capturedIds.size(), "?"));
                List<Object> args = new ArrayList<>(); args.add(auth.period()); args.addAll(capturedIds);
                orders = s.query("SELECT * FROM logistics_order WHERE state IN ('OFFERED','IN_TRANSIT','PAUSED') AND last_period<? AND id IN (" + placeholders + ") ORDER BY last_period,created_period,id", this::readOrder, args.toArray());
            }
            int progressed = 0, completed = 0, paused = 0, expired = 0;
            for (Order o : orders) {
                s.execute("UPDATE logistics_reroute SET state='EXPIRED' WHERE order_id=? AND state='PENDING' AND expires_period<=?",o.id(),auth.period());
                if (o.status() == OrderStatus.OFFERED) {
                    if(paidShipment(o.id())){touch(o,auth.period(),OrderStatus.PAUSED,"已支付货物保留托管，禁止按未付款报价到期退回");paused++;continue;}
                    if (auth.period() >= o.expiresPeriod() && ownersValid(o, auth)) {
                        if (canCredit(o.originCity(), o.offered())) {
                            credit(o.originCity(), o.offered()); s.execute("UPDATE logistics_order SET state='EXPIRED',last_period=?,reason='报价到期，原物退还' WHERE id=?", auth.period(), o.id()); expired++;
                        } else touch(o, auth.period(), OrderStatus.OFFERED, "报价到期但原仓已满，托管保留");
                    } else touch(o, auth.period(), OrderStatus.OFFERED, "等待目标城市接受；失去归属时托管冻结");
                    continue;
                }
                Route r = route(o.routeId()).orElseThrow();
                String invalid = invalid(o, r, auth);
                if (invalid != null) { touch(o, auth.period(), OrderStatus.PAUSED, invalid); paused++; continue; }
                boolean maintained = true;
                for (UUID nodeId : r.nodes()) if (!maintain(node(nodeId).orElseThrow(), auth.period())) maintained = false;
                if (!maintained) { touch(o, auth.period(), OrderStatus.PAUSED, "道路/桥梁/港口/关隘维护物资不足"); paused++; continue; }
                long used = count("SELECT COALESCE(MAX(used),0) FROM logistics_route_usage WHERE route=? AND period=?", r.id(), auth.period());
                if (o.cargoUnits() > r.capacity() - used) { touch(o, auth.period(), OrderStatus.PAUSED, "本周期商路容量不足"); paused++; continue; }
                boolean nodeCapacity = true;
                for (UUID nodeId : r.nodes()) {
                    Node node = node(nodeId).orElseThrow();
                    long nodeUsed = count("SELECT COALESCE(MAX(used),0) FROM logistics_node_usage WHERE node=? AND period=?", nodeId, auth.period());
                    if (o.cargoUnits() > Math.multiplyExact(definitions.nodes().get(node.type()).capacity(), node.level()) - nodeUsed) nodeCapacity = false;
                }
                if (!nodeCapacity) { touch(o, auth.period(), OrderStatus.PAUSED, "本周期共享交通节点容量不足"); paused++; continue; }
                int next = Math.min(o.travelPeriods(), o.progressedPeriods() + 1);
                if (next == o.travelPeriods() && (!canCredit(o.targetCity(), o.offered()) || !canCredit(o.originCity(), o.requested()))) {
                    touch(o, auth.period(), OrderStatus.PAUSED, "目的城市仓储已满，实物仍在托管"); paused++; continue;
                }
                s.execute("INSERT INTO logistics_route_usage(route,period,used) VALUES(?,?,?) ON CONFLICT(route,period) DO UPDATE SET used=used+excluded.used", r.id(), auth.period(), o.cargoUnits());
                for (UUID nodeId : r.nodes()) s.execute("INSERT INTO logistics_node_usage(node,period,used) VALUES(?,?,?) ON CONFLICT(node,period) DO UPDATE SET used=used+excluded.used", nodeId, auth.period(), o.cargoUnits());
                progressed++;
                if (next == o.travelPeriods()) {
                    credit(o.targetCity(), o.offered()); credit(o.originCity(), o.requested());
                    s.execute("UPDATE logistics_order SET state='COMPLETED',last_period=?,progress=?,completed_period=?,reason='双向实物交换已完成' WHERE id=?", auth.period(), next, auth.period(), o.id());
                    s.execute("INSERT INTO logistics_receipt(order_id,period) VALUES(?,?)", o.id(), auth.period()); completed++;
                    s.execute("UPDATE logistics_reroute SET state='CANCELLED' WHERE order_id=? AND state='PENDING'",o.id());
                    s.audit("logistics", "TRADE COMPLETE " + o.id());
                } else s.execute("UPDATE logistics_order SET state='IN_TRANSIT',last_period=?,progress=?,reason='' WHERE id=?", auth.period(), next, o.id());
            }
            // A route usage row is irrelevant once its period is older than all current processing.
            s.execute("DELETE FROM logistics_route_usage WHERE period<?", Math.max(0, auth.period() - 2));
            s.execute("DELETE FROM logistics_node_usage WHERE period<?", Math.max(0, auth.period() - 2));
            return new BatchResult(auth.period(), orders.size(), progressed, completed, paused, expired);
        });
    }

    public List<TradeReceipt> completedAfter(long cursor, int limit) throws SQLException {
        if (cursor < 0) throw new IllegalArgumentException("Negative trade receipt cursor"); page(limit, 0);
        return store.query("SELECT sequence,order_id,period FROM logistics_receipt WHERE sequence>? ORDER BY sequence LIMIT ?", r -> {
            Order o = order(UUID.fromString(r.getString("order_id"))).orElseThrow();
            return new TradeReceipt(r.getLong("sequence"), o.id(), o.originCity(), o.targetCity(), o.originNation(), o.targetNation(), r.getLong("period"), o.offered(), o.requested());
        }, cursor, limit);
    }

    public void setDemand(UUID city, String item, long desired, AuthoritySnapshot auth) throws SQLException {
        cargo(Map.of(item, 1L)); if (desired < 0 || desired > 1_000_000_000L) throw new IllegalArgumentException("Invalid desired stock");
        store.tx(s -> { fresh(auth); manager(city, auth); s.execute("INSERT INTO logistics_demand(city,item,desired) VALUES(?,?,?) ON CONFLICT(city,item) DO UPDATE SET desired=excluded.desired", city, item, desired); return null; });
    }
    public List<SupplyDemand> supplyDemand(UUID city, int limit, int offset) throws SQLException {
        page(limit, offset);
        return store.tx(s -> s.query("SELECT item FROM (SELECT item FROM stock WHERE city=? UNION SELECT item FROM logistics_demand WHERE city=?) ORDER BY item LIMIT ? OFFSET ?", r -> {
            String item = r.getString(1); long available = s.stock(city).getOrDefault(item, 0L);
            long desired = count("SELECT COALESCE(MAX(desired),0) FROM logistics_demand WHERE city=? AND item=?", city, item);
            return new SupplyDemand(city, item, available, desired, Math.max(0, available - desired), Math.max(0, desired - available));
        }, city, city, limit, offset));
    }

    private String invalid(Order o, Route r, AuthoritySnapshot auth) throws SQLException {
        try { requireOwners(o, auth); requireRoute(r, auth); return null; }
        catch (IllegalArgumentException failure) { return failure.getMessage(); }
    }
    private void fresh(AuthoritySnapshot auth) throws SQLException {
        long latest = count("SELECT period FROM logistics_clock WHERE id=1");
        if (auth.period() < latest) throw new IllegalArgumentException("交通授权快照周期已过期");
        store.execute("UPDATE logistics_clock SET period=? WHERE id=1", auth.period());
    }
    private void requireFreshRead(AuthoritySnapshot auth)throws SQLException{if(auth.period()<count("SELECT period FROM logistics_clock WHERE id=1"))throw new IllegalArgumentException("交通授权快照周期已过期");}
    private UUID endpointManager(Order order,AuthoritySnapshot auth)throws SQLException{
        if(auth.managedCities().contains(order.originCity())){manager(order.originCity(),auth);return order.originCity();}
        manager(order.targetCity(),auth);return order.targetCity();
    }
    private void requireMoving(Order order){if(order.status()!=OrderStatus.IN_TRANSIT&&order.status()!=OrderStatus.PAUSED)throw new IllegalArgumentException("仅已接受且未完成的货单可改道");}
    private void requireAlternative(Order order,Route route,AuthoritySnapshot auth)throws SQLException{
        if(!route.originCity().equals(order.originCity())||!route.targetCity().equals(order.targetCity()))throw new IllegalArgumentException("替代商路须保持货单同方向的双方城市");
        requireRoute(route,auth);if(order.cargoUnits()>route.capacity())throw new IllegalArgumentException("替代商路容量不足");
    }
    private void expireReroute(Reroute reroute,long period)throws SQLException{if(reroute.status()==RerouteStatus.PENDING&&period>=reroute.expiresPeriod())store.execute("UPDATE logistics_reroute SET state='EXPIRED' WHERE id=?",reroute.id());}
    private boolean ownersValid(Order o, AuthoritySnapshot auth) throws SQLException { try { requireOwners(o, auth); return true; } catch (IllegalArgumentException failure) { return false; } }
    private void requireOwners(Order o, AuthoritySnapshot auth) throws SQLException { requireOwner(o.originCity(), o.originNation(), auth); requireOwner(o.targetCity(), o.targetNation(), auth); }
    private UUID owner(UUID city, AuthoritySnapshot auth) throws SQLException {
        UUID nation = auth.cityNations().get(city);
        if (nation == null || store.view(city).map(v -> !v.nationId().equals(nation)).orElse(true)) throw new IllegalArgumentException("城市或国家授权快照已失效");
        return nation;
    }
    private void requireOwner(UUID city, UUID expected, AuthoritySnapshot auth) throws SQLException { if (!expected.equals(owner(city, auth))) throw new IllegalArgumentException("领土/国家归属变化，货单托管冻结"); }
    private UUID manager(UUID city, AuthoritySnapshot auth) throws SQLException { UUID nation = owner(city, auth); if (!auth.managedCities().contains(city)) throw new IllegalArgumentException("需要城市管理权限"); return nation; }
    private void requireTrade(UUID origin, UUID target, AuthoritySnapshot auth) { if (!auth.permittedTrades().contains(TradePair.of(origin, target))) throw new IllegalArgumentException("当前战争/外交状态不允许该商路贸易"); }
    private void requireNode(Node n, AuthoritySnapshot auth) throws SQLException { requireOwner(n.cityId(), n.nationId(), auth); if (!n.enabled() || !auth.validNodes().contains(n.id())) throw new IllegalArgumentException("领土/节点实体失效，运输暂停"); }
    private void requireRouteLimits(Route route){routeLimitation(route).ifPresent(reason->{throw new IllegalArgumentException(reason);});}
    private void requireRoute(Route r, AuthoritySnapshot auth) throws SQLException { if (!r.enabled()) throw new IllegalArgumentException("商路已停用");requireRouteLimits(r); requireTrade(r.originCity(), r.targetCity(), auth); for (UUID id : r.nodes()) requireNode(node(id).orElseThrow(), auth);requireCorridor(r,auth); }

    private boolean maintain(Node n, long period) throws SQLException {
        List<long[]> state = store.query("SELECT last_maintenance,maintained FROM logistics_node WHERE id=?", r -> new long[]{r.getLong(1), r.getLong(2)}, n.id());
        if (state.getFirst()[0] >= period) return state.getFirst()[0] == period && state.getFirst()[1] == 1;
        Map<String, Long> upkeep = scale(definitions.nodes().get(n.type()).maintenance(), n.level());
        Map<String, Long> stock = store.stock(n.cityId()); boolean enough = upkeep.entrySet().stream().allMatch(e -> stock.getOrDefault(e.getKey(), 0L) >= e.getValue());
        if (enough) debit(n.cityId(), upkeep);
        store.execute("UPDATE logistics_node SET last_maintenance=?,maintained=? WHERE id=?", period, enough ? 1 : 0, n.id()); return enough;
    }
    private void touch(Order o, long period, OrderStatus status, String reason) throws SQLException { store.execute("UPDATE logistics_order SET state=?,last_period=?,reason=? WHERE id=?", status.name(), period, reason, o.id()); }
    private void debit(UUID city, Map<String, Long> amounts) throws SQLException {
        Map<String, Long> stock = store.stock(city);
        for (var e : amounts.entrySet()) if (stock.getOrDefault(e.getKey(), 0L) < e.getValue()) throw new IllegalArgumentException("城市实物库存不足：" + e.getKey());
        for (var e : amounts.entrySet()) store.stock(city, e.getKey(), stock.getOrDefault(e.getKey(), 0L) - e.getValue());
    }
    private boolean canCredit(UUID city, Map<String, Long> amounts) throws SQLException {
        long capacity = store.cityCapacity(city); Map<String, Long> stock = store.stock(city);
        for (var entry : amounts.entrySet()) if (entry.getValue() > capacity - stock.getOrDefault(entry.getKey(), 0L)) return false;
        return true;
    }
    private void credit(UUID city, Map<String, Long> amounts) throws SQLException { Map<String, Long> stock = store.stock(city); for (var e : amounts.entrySet()) store.stock(city, e.getKey(), Math.addExact(stock.getOrDefault(e.getKey(), 0L), e.getValue())); }
    private void writeCargo(UUID id, String side, Map<String, Long> cargo) throws SQLException { for (var e : cargo.entrySet()) store.execute("INSERT INTO logistics_cargo(order_id,side,item,amount) VALUES(?,?,?,?)", id, side, e.getKey(), e.getValue()); }
    private Map<String, Long> readCargo(UUID id, String side) throws SQLException { Map<String, Long> cargo = new TreeMap<>(); store.query("SELECT item,amount FROM logistics_cargo WHERE order_id=? AND side=? ORDER BY item", r -> { cargo.put(r.getString(1), r.getLong(2)); return 0; }, id, side); return Map.copyOf(cargo); }
    private Map<String, Long> cargo(Map<String, Long> cargo) { return LogisticsDefinitions.amounts(cargo); }
    private static Map<String, Long> scale(Map<String, Long> input, int level) { Map<String, Long> result = new HashMap<>(); input.forEach((key, value) -> result.put(key, Math.multiplyExact(value, level))); return Map.copyOf(result); }
    private static long units(Map<String, Long> give, Map<String, Long> take) { long total = 0; for (long amount : give.values()) total = Math.addExact(total, amount); for (long amount : take.values()) total = Math.addExact(total, amount); return total; }
    private long count(String sql, Object... args) throws SQLException { return store.query(sql, r -> r.getLong(1), args).getFirst(); }
    private void page(int limit, int offset) { if (limit < 1 || limit > 1000 || offset < 0) throw new IllegalArgumentException("Invalid logistics page"); }

    private Node readNode(ResultSet r) throws SQLException { return new Node(uuid(r, "id"), uuid(r, "city"), uuid(r, "nation"), NodeType.valueOf(r.getString("type")), new Site(uuid(r, "world"), r.getString("world_name"), r.getInt("x"), r.getInt("y"), r.getInt("z")), r.getString("terrain"), r.getInt("level"), r.getInt("enabled") == 1); }
    private Route readRoute(ResultSet r) throws SQLException {
        UUID id = uuid(r, "id"); List<UUID> ids = store.query("SELECT node FROM logistics_route_node WHERE route=? ORDER BY position", row -> UUID.fromString(row.getString(1)), id);
        List<Node> nodes = new ArrayList<>(); for (UUID nodeId : ids) nodes.add(node(nodeId).orElseThrow()); Mode mode = Mode.valueOf(r.getString("mode")); Metrics metrics = metrics(nodes, mode,false);
        return new Route(id, uuid(r, "origin_city"), uuid(r, "target_city"), ids, mode, r.getInt("enabled") == 1, metrics.distance(), metrics.periods(), metrics.capacity(),count("SELECT COUNT(*) FROM logistics_corridor WHERE route=?",id)==1);
    }
    private Order readOrder(ResultSet r) throws SQLException {
        UUID id = uuid(r, "id"); return new Order(id, uuid(r, "route"), uuid(r, "origin_city"), uuid(r, "target_city"), uuid(r, "origin_nation"), uuid(r, "target_nation"), readCargo(id, "OFFERED"), readCargo(id, "REQUESTED"), OrderStatus.valueOf(r.getString("state")), r.getLong("created_period"), r.getLong("expires_period"), r.getLong("last_period"), r.getInt("progress"), r.getInt("travel"), r.getLong("completed_period"), r.getString("reason"));
    }
    private Reroute readReroute(ResultSet r)throws SQLException{return new Reroute(uuid(r,"id"),uuid(r,"order_id"),uuid(r,"previous_route"),uuid(r,"proposed_route"),uuid(r,"proposer_city"),r.getLong("created_period"),r.getLong("expires_period"),RerouteStatus.valueOf(r.getString("state")),r.getLong("applied_period"));}
    private static UUID uuid(ResultSet r, String column) throws SQLException { return UUID.fromString(r.getString(column)); }
    private record Metrics(double distance, int periods, long capacity) {}
    private Metrics metrics(List<Node> nodes, Mode mode) {
        return metrics(nodes,mode,true);
    }
    private Metrics metrics(List<Node> nodes, Mode mode,boolean enforceLimit) {
        if (nodes.size() < 2) throw new IllegalArgumentException("商路至少需要两个节点");
        if (mode == Mode.SEA && nodes.stream().anyMatch(n -> n.type() != NodeType.PORT)) throw new IllegalArgumentException("海运路径必须由港口节点组成");
        double distance = 0, travel = 0; long capacity = Long.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i); var spec = definitions.nodes().get(n.type()); capacity = Math.min(capacity, Math.multiplyExact(spec.capacity(), n.level()));
            if (i == 0) continue; Node a = nodes.get(i - 1);
            if (!a.site().worldId().equals(n.site().worldId())) throw new IllegalArgumentException("商路不能跨维度传送");
            double segment = Math.hypot((double) a.site().x() - n.site().x(), (double) a.site().z() - n.site().z()); distance += segment;
            double terrain = mode == Mode.SEA ? 1 : (definitions.terrainTime().getOrDefault(a.terrain(), 1.0) + definitions.terrainTime().getOrDefault(n.terrain(), 1.0)) / 2;
            double speed = (definitions.nodes().get(a.type()).timeFactor() / (1 + .15 * (a.level() - 1)) + spec.timeFactor() / (1 + .15 * (n.level() - 1))) / 2;
            travel += segment * terrain * speed / definitions.blocksPerPeriod();
        }
        double rounded=Math.ceil(travel);
        if(enforceLimit&&(!Double.isFinite(rounded)||rounded>definitions.maxTravelPeriods()))throw new IllegalArgumentException("商路超过配置的最大运输时长");
        // Persisted paths must stay queryable for cancellation/recovery even after stricter configuration.
        // Integer.MAX_VALUE is an unavailable display sentinel, never accepted by current maxTravelPeriods<=100000.
        int periods=!Double.isFinite(rounded)||rounded>=Integer.MAX_VALUE?Integer.MAX_VALUE:Math.max(1,(int)rounded);
        return new Metrics(distance,periods,capacity);
    }
}

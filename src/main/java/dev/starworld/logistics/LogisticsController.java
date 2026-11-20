package dev.starworld.logistics;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.integration.CoreExtensionPort.NationSnapshot;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.runtime.WorldRuntime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper command/thread boundary; storage code and the core bridge never share mutable Bukkit state. */
public final class LogisticsController {
    private static final UUID SYSTEM = new UUID(0, 0);
    private static final Set<String> OPEN_RELATIONS = Set.of("NEUTRAL", "FRIENDLY", "ALLIED", "CEASE_FIRE", "VASSAL");
    private final JavaPlugin plugin;
    private final WorldRuntime runtime;
    private final CoreBridge core;
    private final CoreExtensionPort extensions;
    private final LogisticsService service;
    private final AtomicBoolean ticking = new AtomicBoolean();

    public LogisticsController(JavaPlugin plugin, WorldRuntime runtime, CoreBridge core, CoreExtensionPort extensions, LogisticsService service) {
        this.plugin = Objects.requireNonNull(plugin); this.runtime = Objects.requireNonNull(runtime); this.core = Objects.requireNonNull(core);
        this.extensions = Objects.requireNonNull(extensions); this.service = Objects.requireNonNull(service);
    }
    public LogisticsService service() { return service; }

    public record Captured(AuthoritySnapshot authority, List<CoreCity> cities) { public Captured { cities = List.copyOf(cities); } }
    private record Context(List<Node> nodes, List<Route> routes, List<Order> orders) {
        Context { nodes = List.copyOf(nodes); routes = List.copyOf(routes); orders = List.copyOf(orders); }
    }
    @FunctionalInterface private interface Action<T> { T run(AuthoritySnapshot auth) throws Exception; }

    /** Main thread only. Every route pair is checked against both current nations' war and relation data. */
    public Captured capture(UUID actor, Collection<Node> nodes, Collection<Route> routes, Set<UUID> extraCities, Player manager) {
        if (!plugin.getServer().isPrimaryThread()) throw new IllegalStateException("交通授权必须在 Paper 主线程捕获");
        Set<UUID> cityIds = new HashSet<>(extraCities);
        for (Node n : nodes) cityIds.add(n.cityId());
        for (Route r : routes) { cityIds.add(r.originCity()); cityIds.add(r.targetCity()); }
        Map<UUID, CoreCity> cities = new HashMap<>(); Map<UUID, UUID> nations = new HashMap<>(); Set<UUID> managed = new HashSet<>();
        for (UUID id : cityIds) core.city(id).ifPresent(c -> { cities.put(id, c); nations.put(id, c.nationId()); if (manager != null && manager.isOnline() && runtime.manage(manager, id)) managed.add(id); });
        Set<UUID> valid = new HashSet<>(); Set<String> anchors = new HashSet<>();
        for (Node n : nodes) {
            CoreCity city = cities.get(n.cityId());
            if (city == null || !city.nationId().equals(n.nationId()) || !core.ownsSite(n.cityId(), n.site())) continue;
            World world = plugin.getServer().getWorld(n.site().worldId());
            if (world == null || !world.getName().equals(n.site().worldName()) || !world.isChunkLoaded(n.site().x() >> 4, n.site().z() >> 4)) continue;
            Material anchor = Material.matchMaterial(service.definitions().nodes().get(n.type()).anchor());
            if (anchor != null && world.getBlockAt(n.site().x(), n.site().y(), n.site().z()).getType() == anchor) { valid.add(n.id()); anchors.add(n.site().anchorKey()); }
        }
        Map<UUID, Set<UUID>> peers = new HashMap<>();
        for (Route r : routes) {
            UUID a = nations.get(r.originCity()), b = nations.get(r.targetCity());
            if (a != null && b != null && !a.equals(b)) { peers.computeIfAbsent(a, key -> new HashSet<>()).add(b); peers.computeIfAbsent(b, key -> new HashSet<>()).add(a); }
        }
        Map<UUID, NationSnapshot> snapshots = new HashMap<>();
        for (var e : peers.entrySet()) {
            if (e.getValue().size() > 100) throw new IllegalArgumentException("单次交通授权外交对端过多");
            extensions.snapshot(e.getKey(), Set.copyOf(e.getValue())).ifPresent(n -> snapshots.put(e.getKey(), n));
        }
        Set<TradePair> permitted = new HashSet<>();
        for (Route r : routes) {
            UUID a = nations.get(r.originCity()), b = nations.get(r.targetCity());
            if (a != null && b != null && (a.equals(b) || permitsTrade(snapshots.get(a), snapshots.get(b)))) permitted.add(TradePair.of(r.originCity(), r.targetCity()));
        }
        return new Captured(new AuthoritySnapshot(actor, runtime.currentPeriod(), nations, managed, anchors, valid, permitted), new ArrayList<>(cities.values()));
    }

    /** Pure permission predicate; a missing war/diplomacy capability never means peace. */
    public static boolean permitsTrade(NationSnapshot a, NationSnapshot b) {
        if (a == null || b == null || !"available".equals(a.capabilities().get("war")) || !"available".equals(b.capabilities().get("war"))
                || !"available".equals(a.capabilities().get("diplomacy")) || !"available".equals(b.capabilities().get("diplomacy"))) return false;
        String left = a.diplomacy().get(b.nationId()), right = b.diplomacy().get(a.nationId());
        return left != null && right != null && !a.warOpponents().contains(b.nationId()) && !b.warOpponents().contains(a.nationId())
                && OPEN_RELATIONS.contains(left) && OPEN_RELATIONS.contains(right);
    }

    /** args[0] is node/route/trade/demand. Returns false for an unrelated WorldCommand subcommand. */
    public boolean execute(Player p, String[] args) {
        if (args.length == 0 || !Set.of("node", "route", "trade", "demand").contains(args[0].toLowerCase(Locale.ROOT))) return false;
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "node" -> nodeCommand(p, args);
                case "route" -> routeCommand(p, args);
                case "trade" -> tradeCommand(p, args);
                case "demand" -> demandCommand(p, args);
                default -> throw new IllegalArgumentException("未知交通命令");
            }
        } catch (IllegalArgumentException e) { p.sendMessage("§c" + e.getMessage()); }
        return true;
    }

    private void nodeCommand(Player p, String[] args) {
        String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        switch (op) {
            case "register" -> {
                require(args.length == 3, "/sw node register <road|bridge|port|pass>");
                NodeType type = NodeType.valueOf(args[2].toUpperCase(Locale.ROOT)); CoreCity city = current(p);
                Block block = p.getTargetBlockExact(6); Material material = Material.matchMaterial(service.definitions().nodes().get(type).anchor());
                require(block != null && material != null && block.getType() == material, "请看向六格内的 " + service.definitions().nodes().get(type).anchor());
                Site site = new Site(block.getWorld().getUID(), block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
                require(core.ownsSite(city.cityId(), site), "交通节点必须位于本城领地及本国领土");
                String terrain = TerrainClassifier.classify(block.getBiome().getKey().getKey());
                Captured captured = capture(p.getUniqueId(), List.of(), List.of(), Set.of(city.cityId()), p);
                Set<String> anchors = new HashSet<>(captured.authority().controlledAnchors()); anchors.add(site.anchorKey());
                AuthoritySnapshot a = captured.authority();
                Captured registration = new Captured(new AuthoritySnapshot(a.actor(), a.period(), a.cityNations(), a.managedCities(), anchors, a.validNodes(), a.permittedTrades()), captured.cities());
                run(p, registration, auth -> service.registerNode(UUID.randomUUID(), city.cityId(), type, site, terrain, auth), node -> p.sendMessage("§a已登记 " + type + " · " + node.id()));
            }
            case "list" -> {
                require(args.length <= 3, "/sw node list [页码]"); CoreCity city = current(p); int offset = offset(args, 2);
                runtime.reply(p, runtime.async(() -> service.cityNodes(city.cityId())), nodes -> {
                    p.sendMessage("§e交通节点（每页十项）"); nodes.stream().skip(offset).limit(10).forEach(n -> p.sendMessage("§f" + n.id() + " " + n.type() + " Lv" + n.level() + " " + (n.enabled() ? "启用" : "停用") + " @ " + n.site().x() + "," + n.site().y() + "," + n.site().z()));
                });
            }
            case "upgrade", "disable", "enable" -> {
                require(args.length == 3, "/sw node " + op + " <节点UUID>"); UUID id = UUID.fromString(args[2]);
                load(p, () -> new Context(List.of(service.node(id).orElseThrow(() -> new IllegalArgumentException("未知交通节点"))), List.of(), List.of()), context ->
                        act(p, context, Set.of(), auth -> op.equals("upgrade") ? service.upgradeNode(id, auth) : service.setNodeEnabled(id, op.equals("enable"), auth), n -> p.sendMessage("§a节点已更新 " + n.id() + " Lv" + n.level())));
            }
            default -> { p.sendMessage("§e/sw node register <road|bridge|port|pass> · list [页码]"); p.sendMessage("§e/sw node upgrade|enable|disable <节点UUID>"); }
        }
    }

    private void routeCommand(Player p, String[] args) {
        String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        switch (op) {
            case "create" -> {
                require(args.length == 5, "/sw route create <目标城市UUID> <land|sea> <起点节点UUID,终点节点UUID[,途经节点...]>");
                CoreCity city = current(p); UUID target = UUID.fromString(args[2]); Mode mode = Mode.valueOf(args[3].toUpperCase(Locale.ROOT));
                List<UUID> ids = Arrays.stream(args[4].split(",")).map(UUID::fromString).toList();
                require(ids.size() >= 2 && ids.size() <= service.definitions().maxWaypoints(), "商路节点数量超出范围；按起点、途经点、终点顺序填写");
                UUID id = UUID.randomUUID(); Route proposed = new Route(id, city.cityId(), target, ids, mode, true, 0, 1, 1);
                load(p, () -> { List<Node> nodes = new ArrayList<>(); for (UUID node : ids) nodes.add(service.node(node).orElseThrow(() -> new IllegalArgumentException("未知路径节点"))); return new Context(nodes, List.of(proposed), List.of()); }, context ->
                        act(p, context, Set.of(city.cityId(), target), auth -> service.createRoute(id, city.cityId(), target, ids, mode, auth), r -> p.sendMessage("§a商路 " + r.id() + " · " + r.travelPeriods() + "周期 · 容量" + r.capacity() + "；交易由目标城市接受货单后生效")));
            }
            case "list" -> {
                require(args.length <= 3, "/sw route list [页码]"); CoreCity city = current(p); int offset = offset(args, 2);
                runtime.reply(p, runtime.async(() -> service.cityRoutes(city.cityId(), 10, offset)), routes -> routes.forEach(r -> p.sendMessage("§e" + r.id() + " " + r.originCity() + " → " + r.targetCity() + " " + r.mode() + " " + r.travelPeriods() + "周期/容量" + r.capacity() + " " + (r.enabled() ? "启用" : "停用"))));
            }
            case "enable", "disable" -> {
                require(args.length == 3, "/sw route " + op + " <商路UUID>"); UUID id = UUID.fromString(args[2]);
                load(p, () -> contextForRoute(id), c -> act(p, c, Set.of(), a -> service.setRouteEnabled(id, op.equals("enable"), a), r -> p.sendMessage("§a商路已" + (r.enabled() ? "启用" : "停用"))));
            }
            case "status" -> {
                require(args.length==3,"/sw route status <商路UUID>");UUID id=UUID.fromString(args[2]);CoreCity city=current(p);long period=runtime.currentPeriod();
                runtime.reply(p,runtime.async(()->{Route route=service.route(id).orElseThrow();require(route.originCity().equals(city.cityId())||route.targetCity().equals(city.cityId()),"仅商路双方城市可查询");return service.transportStatus(id,period);}),status->{
                    p.sendMessage("§e持久运力观察 · 周期"+status.period()+" · 剩余"+status.available()+"/"+status.capacity()+" · 待运"+status.waitingOrders()+" 暂停"+status.pausedOrders());
                    p.sendMessage("§7世界锚点与外交仍以结算现场复核为准；瓶颈 "+status.bottlenecks());
                    for(NodeTraffic node:status.nodes())p.sendMessage("§7"+node.nodeId()+" "+node.type()+" 可用"+node.remaining()+"/"+node.capacity()+" 本期维护"+(node.maintenancePaid()?"已支付":"未支付"));
                    status.pausedReasons().forEach((reason,count)->p.sendMessage("§c"+count+"单 · "+reason));
                });
            }
            default -> { p.sendMessage("§e/sw route create <目标城市UUID> <land|sea> <起点UUID,途经UUID,终点UUID>"); p.sendMessage("§e/sw route list [页码] · enable|disable|status <商路UUID>"); }
        }
    }

    private void tradeCommand(Player p, String[] args) {
        String op = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "help";
        switch (op) {
            case "offer" -> {
                require(args.length == 5, "/sw trade offer <商路UUID> <付出物品=数量[,..]> <索要物品=数量[,..]>");
                UUID route = UUID.fromString(args[2]); Map<String, Long> give = parseCargo(args[3]), take = parseCargo(args[4]); UUID id = UUID.randomUUID();
                load(p, () -> contextForRoute(route), context -> act(p, context, Set.of(), auth -> service.createOffer(id, route, give, take, auth), o -> p.sendMessage("§a已托管报价 " + o.id() + "；请目标城市管理者 /sw trade accept " + o.id())));
            }
            case "accept", "cancel" -> {
                require(args.length == 3, "/sw trade " + op + " <货单UUID>"); UUID id = UUID.fromString(args[2]);
                load(p, () -> contextForOrder(id), context -> act(p, context, Set.of(), auth -> op.equals("accept") ? service.accept(id, auth) : service.cancel(id, auth), o -> p.sendMessage("§a货单 " + o.id() + " · " + o.status())));
            }
            case "list" -> {
                require(args.length <= 3, "/sw trade list [页码]"); CoreCity city = current(p); int offset = offset(args, 2);
                runtime.reply(p, runtime.async(() -> service.cityOrders(city.cityId(), 10, offset)), orders -> orders.forEach(o -> { p.sendMessage("§e" + o.id() + " " + o.status() + " " + o.progressedPeriods() + "/" + o.travelPeriods() + " " + o.reason()); p.sendMessage("§7付出 " + o.offered() + " ⇄ 索要 " + o.requested()); }));
            }
            case "alternatives" -> {
                require(args.length==3,"/sw trade alternatives <货单UUID>");UUID id=UUID.fromString(args[2]);
                load(p,()->contextForAlternatives(id),context->act(p,context,Set.of(),auth->service.alternativeRoutes(id,auth,16),routes->{p.sendMessage("§e已登记的同方向替代商路（最多十六条；改道需双方确认）");for(Route r:routes)p.sendMessage("§f"+r.id()+" "+r.travelPeriods()+"周期 / 容量"+r.capacity());}));
            }
            case "reroute" -> {
                require(args.length==4,"/sw trade reroute <货单UUID> <新商路UUID>");UUID order=UUID.fromString(args[2]),route=UUID.fromString(args[3]),request=UUID.randomUUID();
                load(p,()->merge(contextForOrder(order),contextForRoute(route)),context->act(p,context,Set.of(),auth->service.proposeReroute(request,order,route,auth),proposal->p.sendMessage("§a改道提议 "+proposal.id()+"；另一方管理者 /sw trade reroute-accept "+proposal.id()+" · 确认后完整新行程重计")));
            }
            case "reroute-accept", "reroute-cancel" -> {
                require(args.length==3,"/sw trade "+op+" <改道请求UUID>");UUID id=UUID.fromString(args[2]);
                load(p,()->{Reroute proposal=service.reroute(id).orElseThrow();return merge(contextForOrder(proposal.orderId()),contextForRoute(proposal.proposedRouteId()));},context->act(p,context,Set.of(),auth->op.equals("reroute-accept")?service.acceptReroute(id,auth):service.cancelReroute(id,auth),proposal->p.sendMessage("§a改道请求 "+proposal.id()+" · "+proposal.status())));
            }
            case "reroutes" -> {
                require(args.length<=3,"/sw trade reroutes [页码]");CoreCity city=current(p);int offset=offset(args,2);long period=runtime.currentPeriod();
                runtime.reply(p,runtime.async(()->service.pendingReroutes(city.cityId(),10,offset)),proposals->{for(Reroute proposal:proposals)p.sendMessage("§e"+proposal.id()+" 货单"+proposal.orderId()+" → "+proposal.proposedRouteId()+" "+(period>=proposal.expiresPeriod()?"已到期":"待另一方确认，到期周期"+proposal.expiresPeriod()));});
            }
            default -> { p.sendMessage("§e/sw trade offer <商路UUID> <minecraft:bread=16> <minecraft:iron_ingot=4>"); p.sendMessage("§e/sw trade accept|cancel <货单UUID> · list [页码]；逗号分隔多个物品");p.sendMessage("§e/sw trade alternatives <货单UUID> · reroute <货单UUID> <商路UUID> · reroute-accept|reroute-cancel <请求UUID> · reroutes"); }
        }
    }

    private void demandCommand(Player p, String[] args) {
        CoreCity city = current(p);
        if (args.length == 1 || (args.length >= 2 && args[1].equalsIgnoreCase("help"))) { p.sendMessage("§e/sw demand <物品ID> <目标库存> · /sw demand list [页码]"); return; }
        if (args[1].equalsIgnoreCase("list")) {
            require(args.length <= 3, "/sw demand list [页码]"); int offset = offset(args, 2);
            runtime.reply(p, runtime.async(() -> service.supplyDemand(city.cityId(), 10, offset)), quotes -> quotes.forEach(q -> p.sendMessage("§e" + q.item() + " 可用" + q.available() + " 目标" + q.desired() + " 过剩" + q.surplus() + " 短缺" + q.shortage()))); return;
        }
        require(args.length == 3, "/sw demand <物品ID> <目标库存>"); long desired = Long.parseLong(args[2]);
        Captured auth = capture(p.getUniqueId(), List.of(), List.of(), Set.of(city.cityId()), p);
        run(p, auth, a -> { service.setDemand(city.cityId(), args[1], desired, a); return true; }, done -> p.sendMessage("§a已设置城市目标库存"));
    }

    /** Root may await this future and then feed completedAfter(cursor,limit) to cultural diffusion. */
    public CompletableFuture<BatchResult> tickFuture() {
        if (!ticking.compareAndSet(false, true)) return CompletableFuture.completedFuture(new BatchResult(runtime.currentPeriod(), 0, 0, 0, 0, 0));
        CompletableFuture<BatchResult> result = runtime.async(() -> {
            List<Order> orders = service.dueOrders(runtime.currentPeriod(), Math.min(20, service.definitions().maxBatch()));
            Map<UUID, Route> routes = new LinkedHashMap<>(); Map<UUID, Node> nodes = new LinkedHashMap<>();
            for (Order o : orders) { Context c = contextForRoute(o.routeId()); for (Route r : c.routes()) routes.put(r.id(), r); for (Node n : c.nodes()) nodes.put(n.id(), n); }
            return new Context(new ArrayList<>(nodes.values()), new ArrayList<>(routes.values()), orders);
        }).thenCompose(context -> onMain(() -> capture(SYSTEM, context.nodes(), context.routes(), Set.of(), null))
                .thenCompose(captured -> ensure(captured).thenCompose(ignored -> runtime.async(() -> service.settleCaptured(captured.authority(), context.orders().stream().map(Order::id).toList())))));
        return result.whenComplete((value, error) -> ticking.set(false));
    }
    public void tick() { tickFuture().whenComplete((result, error) -> { if (error != null) plugin.getLogger().warning("交通批处理失败：" + root(error).getMessage()); }); }

    private Context contextForRoute(UUID id) throws Exception {
        Route r = service.route(id).orElseThrow(() -> new IllegalArgumentException("未知商路")); List<Node> nodes = new ArrayList<>();
        for (UUID node : r.nodes()) nodes.add(service.node(node).orElseThrow(() -> new IllegalArgumentException("商路节点缺失")));
        return new Context(nodes, List.of(r), List.of());
    }
    private Context contextForOrder(UUID id) throws Exception { Order order = service.order(id).orElseThrow(() -> new IllegalArgumentException("未知货单")); Context c = contextForRoute(order.routeId()); return new Context(c.nodes(), c.routes(), List.of(order)); }
    private Context contextForAlternatives(UUID id)throws Exception{Context result=contextForOrder(id);for(Route route:service.declaredAlternatives(id,16))result=merge(result,contextForRoute(route.id()));return result;}
    private static Context merge(Context first,Context second){Map<UUID,Node> nodes=new LinkedHashMap<>();Map<UUID,Route> routes=new LinkedHashMap<>();Map<UUID,Order> orders=new LinkedHashMap<>();for(Context context:List.of(first,second)){for(Node n:context.nodes())nodes.put(n.id(),n);for(Route r:context.routes())routes.put(r.id(),r);for(Order o:context.orders())orders.put(o.id(),o);}return new Context(new ArrayList<>(nodes.values()),new ArrayList<>(routes.values()),new ArrayList<>(orders.values()));}
    private void load(Player p, WorldRuntime.Work<Context> work, Consumer<Context> action) { runtime.reply(p, runtime.async(work), c -> { try { action.accept(c); } catch (IllegalArgumentException e) { p.sendMessage("§c" + e.getMessage()); } }); }
    private <T> void act(Player p, Context context, Set<UUID> extra, Action<T> action, Consumer<T> success) { run(p, capture(p.getUniqueId(), context.nodes(), context.routes(), extra, p), action, success); }
    private <T> void run(Player p, Captured captured, Action<T> action, Consumer<T> success) { runtime.reply(p, ensure(captured).thenCompose(ignored -> runtime.async(() -> action.run(captured.authority()))), success); }
    private CompletableFuture<Void> ensure(Captured captured) { List<CompletableFuture<?>> futures = new ArrayList<>(); for (CoreCity city : captured.cities()) futures.add(runtime.open(city)); return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)); }
    private <T> CompletableFuture<T> onMain(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable work = () -> { try { result.complete(task.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } };
        if (!plugin.isEnabled()) result.completeExceptionally(new IllegalStateException("StarWorld stopping"));
        else if (plugin.getServer().isPrimaryThread()) work.run();
        else try { plugin.getServer().getScheduler().runTask(plugin, work); } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }
    private CoreCity current(Player p) { return runtime.playerCity(p).orElseThrow(() -> new IllegalArgumentException("请先在 StarCore 加入城市")); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private static int offset(String[] args, int index) { int page = args.length > index ? Integer.parseInt(args[index]) : 1; if (page < 1 || page > 100000) throw new IllegalArgumentException("页码范围为1至100000"); return (page - 1) * 10; }
    public static Map<String, Long> parseCargo(String argument) {
        if (argument.length() > 4096) throw new IllegalArgumentException("物品报价过长"); Map<String, Long> result = new TreeMap<>();
        for (String entry : argument.split(",", -1)) { String[] pair = entry.split("=", -1); if (pair.length != 2 || result.containsKey(pair[0])) throw new IllegalArgumentException("物品格式为 minecraft:bread=16，物品不得重复"); result.put(pair[0], Long.parseLong(pair[1])); }
        return LogisticsDefinitions.amounts(result);
    }
    private static Throwable root(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return failure; }
}

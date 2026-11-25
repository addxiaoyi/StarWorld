package dev.starworld.market;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.integration.*;
import dev.starworld.logistics.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.market.MarketState.*;
import dev.starworld.runtime.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper authorization and bounded payment dispatch. No player or world objects enter the market tables. */
public final class MarketController implements AutoCloseable {
    private static final UUID SYSTEM=new UUID(0,0);
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;private final LogisticsService logistics;private final MarketService market;private final TreasuryTransferPort treasury;private final LogisticsController authority;private final AtomicBoolean ticking=new AtomicBoolean();private volatile boolean closed;
    private final MarketRefundService refunds;private final TreasuryRefundPort refundTreasury;private final MarketQuoteService quotes;private final MarketTaxPort taxes;
    public MarketController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,CoreExtensionPort extensions,LogisticsService logistics,MarketService market,TreasuryTransferPort treasury){this(plugin,runtime,core,extensions,logistics,market,treasury,null,TreasuryRefundPort.unavailable());}
    public MarketController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,CoreExtensionPort extensions,LogisticsService logistics,MarketService market,TreasuryTransferPort treasury,MarketRefundService refunds,TreasuryRefundPort refundTreasury){this(plugin,runtime,core,extensions,logistics,market,treasury,refunds,refundTreasury,null,null);}
    public MarketController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,CoreExtensionPort extensions,LogisticsService logistics,MarketService market,TreasuryTransferPort treasury,MarketRefundService refunds,TreasuryRefundPort refundTreasury,MarketQuoteService quotes,MarketTaxPort taxes){this.plugin=Objects.requireNonNull(plugin);this.runtime=Objects.requireNonNull(runtime);this.core=Objects.requireNonNull(core);this.logistics=Objects.requireNonNull(logistics);this.market=Objects.requireNonNull(market);this.treasury=Objects.requireNonNull(treasury);this.refunds=refunds;this.refundTreasury=Objects.requireNonNull(refundTreasury);this.quotes=quotes;this.taxes=taxes;if((quotes==null)!=(taxes==null))throw new IllegalArgumentException("Quote service and public tax port must be installed together");authority=new LogisticsController(plugin,runtime,core,extensions,logistics);}
    public String status(){return treasury.status()+(taxes==null?"":"；"+taxes.status())+"；"+refundTreasury.status()+"；实体挂牌、国家金币成交和实际商路交货";}
    private record Context(List<Node> nodes,List<Route> routes,Set<UUID> cities){Context{nodes=List.copyOf(nodes);routes=List.copyOf(routes);cities=Set.copyOf(cities);}}
    private record BuyContext(Listing listing,Context context){}
    private record QuoteContext(MarketQuoteService.Quote quote,Context context){}
    private record QuoteResult(MarketQuoteService.Quote quote,Purchase purchase){}
    private record Pending(Dispatch dispatch,Context context){}
    private record Paid(Purchase purchase,Context context){}
    private record RefundJob(MarketRefundService.Dispatch dispatch,Context context){}
    private record ReturnJob(MarketRefundService.Refund refund,Context context){}
    private record Work(List<Pending> payments,List<Paid> shipments,List<Listing> expiring,List<RefundJob> refunds,List<ReturnJob> returns){}
    @FunctionalInterface private interface Action<T>{T run(AuthoritySnapshot auth)throws Exception;}
    public boolean execute(Player player,String[] args){if(args.length==0||!args[0].equalsIgnoreCase("market"))return false;try{command(player,args);}catch(IllegalArgumentException e){player.sendMessage("§c"+e.getMessage());}return true;}
    private void command(Player p,String[] args){String op=args.length<2?"help":args[1].toLowerCase(Locale.ROOT);switch(op){
        case "list"->{require(args.length<=3,"/sw market list [页码]");int offset=offset(args,2);runtime.reply(p,runtime.async(()->market.listings(null,10,offset)),values->{p.sendMessage("§e实体商品挂牌（可按数量购买，国家创始人确认付款）");for(Listing l:values)p.sendMessage("§f"+l.id()+" · "+l.item()+" 可买 ×"+l.availableUnits()+" · 单价 "+l.unitPrice()+" · 剩余总价 "+l.availableTotal()+" · 卖城 "+l.sellerCity());});}
        case "mine"->{CoreCity expected=current(p,false);int offset=offset(args,2);runtime.reply(p,runtime.async(()->market.listings(expected.cityId(),10,offset)),values->{if(!same(p,expected,false))return;for(Listing l:values)p.sendMessage("§e"+l.id()+" "+l.status()+" "+l.item()+" 原始 ×"+l.units()+" · 可买 "+l.availableUnits()+" · 预留 "+l.reservedUnits()+" · 已发货 "+l.shippedUnits()+" · 已退还 "+l.returnedUnits()+" @ "+l.unitPrice());});}
        case "sell"->{require(args.length==5,"/sw market sell <物品ID> <整批数量> <单件金币价格>");CoreCity expected=current(p,false);String item=args[2];long units=Long.parseLong(args[3]);BigDecimal price=parsePrice(args[4]);UUID id=UUID.randomUUID();Context c=new Context(List.of(),List.of(),Set.of(expected.cityId()));act(p,expected,c,false,a->market.list(id,expected.cityId(),item,units,price,a),l->p.sendMessage("§a挂牌已托管真实商品："+l.id()+" · 整批 "+l.total()+" 国家金币"));}
        case "buy", "quote"->{
            require(args.length==4||args.length==5,"/sw market "+op+" <挂牌UUID> <卖城至本城商路UUID> [数量]");
            Long quantity=args.length==5?parseQuantity(args[4]):null;CoreCity expected=current(p,true);UUID listingId=UUID.fromString(args[2]),routeId=UUID.fromString(args[3]),request=UUID.randomUUID();
            runtime.reply(p,runtime.async(()->{Listing l=market.listing(listingId).orElseThrow(()->new IllegalArgumentException("未知挂牌"));return new BuyContext(l,context(routeId,Set.of(l.sellerCity(),expected.cityId())));}),loaded->{
                if(quotes!=null)quotedBuy(p,expected,loaded,quantity,op.equals("quote"));
                else if(op.equals("quote"))p.sendMessage("§c核心税费报价尚未启用");
                else act(p,expected,loaded.context(),true,a->quantity==null?market.buy(request,listingId,routeId,expected.cityId(),expected.nationId(),a):market.buy(request,listingId,routeId,expected.cityId(),expected.nationId(),quantity,a),purchase->purchaseReply(p,purchase));
            });
        }
        case "buy-quote"->{
            require(quotes!=null,"核心税费报价尚未启用");require(args.length==3,"/sw market buy-quote <报价UUID>");CoreCity expected=current(p,true);UUID id=UUID.fromString(args[2]);
            runtime.reply(p,runtime.async(()->{var q=quotes.quote(id).orElseThrow(()->new IllegalArgumentException("报价不存在或已到期"));Listing l=market.listing(q.listingId()).orElseThrow();return new QuoteContext(q,context(q.routeId(),Set.of(l.sellerCity(),q.buyerCity())));}),loaded->act(p,expected,loaded.context(),true,a->quotes.accept(id,expected.cityId(),expected.nationId(),a,System.currentTimeMillis()),purchase->purchaseReply(p,purchase)));
        }
        case "cancel"->{require(args.length==3,"/sw market cancel <挂牌UUID>");CoreCity expected=current(p,false);UUID id=UUID.fromString(args[2]);runtime.reply(p,runtime.async(()->market.listing(id).orElseThrow()),l->act(p,expected,new Context(List.of(),List.of(),Set.of(l.sellerCity())),false,a->market.cancelListing(id,a),v->p.sendMessage("§a挂牌已关闭，未预留商品已归还本城；已预留份额继续交付")));}
        case "cancel-buy", "retry"->{require(args.length==3,"/sw market "+op+" <购买UUID>");CoreCity expected=current(p,true);UUID id=UUID.fromString(args[2]);runtime.reply(p,runtime.async(()->{Purchase purchase=market.purchase(id).orElseThrow();return context(purchase.routeId(),Set.of(purchase.buyerCity(),purchase.sellerCity()));}),c->act(p,expected,c,true,a->op.equals("retry")?market.retry(id,a,expected.nationId()):market.cancelUnsent(id,a),v->p.sendMessage("§a购买 "+v.id()+" · "+v.status()+"；始终使用同一国家转账凭据")));}
        case "orders"->{require(args.length<=3,"/sw market orders [页码]");CoreCity expected=current(p,false);int offset=offset(args,2);runtime.reply(p,runtime.async(()->market.purchases(expected.cityId(),10,offset)),values->{if(!same(p,expected,false))return;for(Purchase purchase:values){p.sendMessage("§e"+purchase.id()+" "+purchase.status()+" · 数量 "+purchase.units()+" · "+purchase.total()+" · "+purchase.detail());p.sendMessage("§7货款 "+purchase.goodsTotal()+" · 出口国关税 "+purchase.taxAmount()+" · 国家凭据 "+purchase.transactionId()+" · 物流单 "+purchase.shipmentId());}});}
        case "refund"->{requireRefunds();require(args.length==3,"/sw market refund <购买UUID>");CoreCity expected=current(p,true);UUID id=UUID.fromString(args[2]);runtime.reply(p,runtime.async(()->market.purchase(id).orElseThrow()),purchase->act(p,expected,refundContext(purchase),true,a->refunds.request(id,a,expected.nationId()),r->p.sendMessage("§e退款申请 "+r.id()+" · "+r.status()+"；卖方国家创始人用 /sw market refund-accept <申请UUID> 确认后冻结未交付商品")));}
        case "refund-accept", "refund-cancel", "refund-retry"->{requireRefunds();require(args.length==3,"/sw market "+op+" <退款申请UUID>");CoreCity expected=current(p,true);UUID id=UUID.fromString(args[2]);runtime.reply(p,runtime.async(()->market.purchase(refunds.refund(id).orElseThrow().purchaseId()).orElseThrow()),purchase->act(p,expected,refundContext(purchase),true,a->switch(op){case "refund-accept"->refunds.accept(id,a,expected.nationId());case "refund-cancel"->refunds.cancel(id,a,expected.nationId());default->refunds.retry(id,a,expected.nationId());},r->p.sendMessage("§e退款 "+r.id()+" · "+r.status()+"；核实原付款逆向回执后才归还商品")));}
        case "refunds"->{requireRefunds();require(args.length<=3,"/sw market refunds [页码]");CoreCity expected=current(p,false);int offset=offset(args,2);runtime.reply(p,runtime.async(()->refunds.refunds(expected.cityId(),10,offset)),rows->{if(!same(p,expected,false))return;for(var row:rows)p.sendMessage("§e退款 "+row.id()+" · 购买 "+row.purchaseId()+" · "+row.status()+" · "+row.detail());});}
        case "history"->{require(args.length<=3,"/sw market history [物品ID]");String item=args.length==3?args[2]:null;runtime.reply(p,runtime.async(()->market.history(item,10,0)),rows->{p.sendMessage("§e最近已付款发货记录（实际价格，不代表已完成运输）");for(PriceHistory h:rows)p.sendMessage("§7"+h.item()+" ×"+h.units()+" @ "+h.unitPrice()+" · 周期 "+h.period());});}
        case "stats"->{require(args.length<=3,"/sw market stats [物品ID]");CoreCity expected=current(p,false);String item=args.length==3?args[2]:null;runtime.reply(p,runtime.marketDeliveryStats(expected.cityId(),item,100,0),rows->{if(!same(p,expected,false))return;p.sendMessage("§e已完成实体交货统计（仅物流 COMPLETED）");for(DeliveryStats s:rows)p.sendMessage("§7"+s.item()+" · 订单 "+s.orders()+" · 数量 "+s.units()+" · 总额 "+s.gross()+" · 均价 "+s.averageUnitPrice()+" · 周期 "+s.firstCompletedPeriod()+"–"+s.lastCompletedPeriod());});}
        default->{p.sendMessage("§e/sw market list|mine|orders|refunds [页码] · history|stats [物品ID]");p.sendMessage("§e/sw market sell <物品ID> <数量> <单价> · buy <挂牌UUID> <正向商路UUID> [数量]");p.sendMessage("§e/sw market cancel <挂牌UUID> · cancel-buy|retry|refund <购买UUID>；国家付款和退款需要创始人权限");p.sendMessage("§e/sw market refund-accept|refund-cancel|refund-retry <退款申请UUID>");p.sendMessage("§e/sw market quote <挂牌UUID> <商路UUID> [数量] · buy-quote <报价UUID>（含税总额确认）");}
    }}
    private void quotedBuy(Player p,CoreCity expected,BuyContext loaded,Long requested,boolean preview){
        try{
            if(!same(p,expected,true))return;Listing l=loaded.listing();long units=requested==null?l.availableUnits():requested;
            var assessment=taxes.quote(UUID.randomUUID(),l.sellerNation(),p.getUniqueId(),l.unitPrice().multiply(BigDecimal.valueOf(units)));
            act(p,expected,loaded.context(),true,a->{
                var q=quotes.prepare(l.id(),loaded.context().routes().getFirst().id(),expected.cityId(),expected.nationId(),units,assessment,a,System.currentTimeMillis());
                return new QuoteResult(q,preview||q.taxAmount().signum()>0?null:quotes.accept(q.id(),expected.cityId(),expected.nationId(),a,System.currentTimeMillis()));
            },result->{
                if(result.purchase()!=null){purchaseReply(p,result.purchase());return;}
                var q=result.quote();p.sendMessage("§e市场报价 "+q.id()+" · "+l.item()+" ×"+q.units()+" · 货款 "+q.goodsTotal()+" · 出口国关税 "+q.taxAmount()+" · 应付 "+q.total()+" 国家金币");
                p.sendMessage("§7货款及关税均付卖方国库 "+q.sellerNation()+"；60秒内 /sw market buy-quote "+q.id()+" 确认。报价未预留商品，确认时复核余量与商路。");
            });
        }catch(IllegalArgumentException unavailable){p.sendMessage("§c"+unavailable.getMessage());}
    }
    private void purchaseReply(Player p,Purchase purchase){p.sendMessage("§a购买已准备："+purchase.id()+" · 数量 "+purchase.units()+" · 将从本国库原子支付 "+purchase.total()+"，凭据 "+purchase.transactionId()+"；货款 "+purchase.goodsTotal()+" + 出口国关税 "+purchase.taxAmount()+"；付款后通过商路实际交货");}
    private <T>void act(Player p,CoreCity expected,Context context,boolean founder,Action<T> action,Consumer<T> reply){if(!same(p,expected,founder))return;LogisticsController.Captured captured=authority.capture(p.getUniqueId(),context.nodes(),context.routes(),context.cities(),p);if(!same(p,expected,founder))return;runtime.reply(p,runtime.async(()->{for(CoreCity city:captured.cities())runtimeCity(city);return action.run(captured.authority());}),reply);}
    // ensureCity goes through the same storage executor; no StarCore mutation occurs here.
    private void runtimeCity(CoreCity city)throws Exception{market.ensureCity(city);}
    private Context context(UUID routeId,Set<UUID> cities)throws Exception{Route r=logistics.route(routeId).orElseThrow(()->new IllegalArgumentException("未知商路"));List<Node> nodes=new ArrayList<>();for(UUID id:r.nodes())nodes.add(logistics.node(id).orElseThrow());return new Context(nodes,List.of(r),cities);}
    private static Context refundContext(Purchase purchase){return new Context(List.of(),List.of(),Set.of(purchase.buyerCity(),purchase.sellerCity()));}
    private void requireRefunds(){require(refunds!=null,"退款账本尚未启用");}
    private CoreCity current(Player p,boolean founder){CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入StarCore城市"));if(!runtime.manage(p,city.cityId()))throw new IllegalArgumentException("需要城市管理权限");if(founder&&!p.hasPermission("starworld.admin")&&!core.canGovern(p.getUniqueId(),city.cityId()))throw new IllegalArgumentException("国家金币付款需要当前国家创始人权限");return city;}
    private boolean same(Player p,CoreCity expected,boolean founder){try{if(!p.isOnline()||!p.hasPermission("starworld.use"))throw new IllegalArgumentException("玩家已离线或市场使用权限已失效");CoreCity actual=current(p,founder);if(!actual.cityId().equals(expected.cityId())||!actual.nationId().equals(expected.nationId()))throw new IllegalArgumentException("城市/国家归属已变化");return true;}catch(RuntimeException failure){p.sendMessage("§c"+failure.getMessage());return false;}}
    public CompletableFuture<Void> tickFuture(){if(closed||!ticking.compareAndSet(false,true))return CompletableFuture.completedFuture(null);long now=System.currentTimeMillis();CompletableFuture<Void> result=runtime.async(()->{if(quotes!=null)quotes.cleanup(now,market.definitions().dispatchBatch());market.reconcileDeliveries(market.definitions().dispatchBatch());List<Pending> pending=new ArrayList<>();for(Dispatch d:market.leaseDue(market.definitions().dispatchBatch(),now))pending.add(new Pending(d,context(d.purchase().routeId(),Set.of(d.purchase().buyerCity(),d.purchase().sellerCity()))));List<Paid> paid=new ArrayList<>();for(Purchase p:market.leasePaid(market.definitions().dispatchBatch(),now))paid.add(new Paid(p,context(p.routeId(),Set.of(p.buyerCity(),p.sellerCity()))));List<RefundJob> refundJobs=new ArrayList<>();List<ReturnJob> returns=new ArrayList<>();if(refunds!=null){for(var d:refunds.leaseDue(market.definitions().dispatchBatch(),now))refundJobs.add(new RefundJob(d,refundContext(d.purchase())));for(var r:refunds.leaseReturns(market.definitions().dispatchBatch(),now))returns.add(new ReturnJob(r,refundContext(market.purchase(r.purchaseId()).orElseThrow())));}return new Work(List.copyOf(pending),List.copyOf(paid),market.expiring(runtime.currentPeriod(),market.definitions().dispatchBatch()),List.copyOf(refundJobs),List.copyOf(returns));}).thenCompose(work->runtime.globalFuture(()->{if(closed)return CompletableFuture.<Void>completedFuture(null);List<CompletableFuture<?>> jobs=new ArrayList<>();CorridorCapture corridorBudget=new CorridorCapture(plugin.getServer(),core);for(Pending pending:work.payments()){
                Purchase p=pending.dispatch().purchase();LogisticsController.Captured captured=capture(pending.context(),corridorBudget);AuthoritySnapshot a=captured.authority();boolean currentOwners=p.buyerNation().equals(a.cityNations().get(p.buyerCity()))&&p.sellerNation().equals(a.cityNations().get(p.sellerCity()));boolean open=currentOwners&&pending.context().routes().stream().allMatch(r->r.enabled()&&logistics.routeLimitation(r).isEmpty()&&Corridor.limitation(r,a.corridors().get(r.id())).isEmpty())&&a.permittedTrades().contains(TradePair.of(p.buyerCity(),p.sellerCity()))&&pending.context().nodes().stream().allMatch(n->n.enabled()&&n.nationId().equals(a.cityNations().get(n.cityId()))&&a.validNodes().contains(n.id()));
                TreasuryTransferPort.Delivery delivery;
                if(open)delivery=treasury.transfer(p.request());else{Optional<TreasuryTransferPort.Receipt> receipt=treasury.receipt(p.transactionId());delivery=receipt.map(r->new TreasuryTransferPort.Delivery(p.transactionId(),r.matches(p.request())?TreasuryTransferPort.Status.CONFIRMED:TreasuryTransferPort.Status.CONFLICT,r,"归属/商路或运输配置变化；仅核对历史付款回执")).orElseGet(()->new TreasuryTransferPort.Delivery(p.transactionId(),TreasuryTransferPort.Status.RETRY,null,"归属、战争、节点或运输配置失效；未发起新付款，原凭据待恢复"));}
                TreasuryTransferPort.Delivery finished=delivery;jobs.add(runtime.async(()->{for(CoreCity c:captured.cities())market.ensureCity(c);return market.finish(pending.dispatch(),finished,System.currentTimeMillis());}));
            }
            for(Paid paid:work.shipments()){LogisticsController.Captured captured=capture(paid.context(),corridorBudget);jobs.add(runtime.async(()->{for(CoreCity c:captured.cities())market.ensureCity(c);try{return market.ship(paid.purchase().id(),captured.authority());}catch(java.sql.SQLException blocked){plugin.getLogger().fine("已付款市场货物仍托管："+blocked.getMessage());return null;}}));}
            if(!work.expiring().isEmpty()){Set<UUID> cities=new HashSet<>();for(Listing l:work.expiring())cities.add(l.sellerCity());LogisticsController.Captured captured=authority.capture(SYSTEM,List.of(),List.of(),cities,null);jobs.add(runtime.async(()->{for(CoreCity c:captured.cities())market.ensureCity(c);return market.expire(captured.authority(),market.definitions().dispatchBatch());}));}
            for(RefundJob job:work.refunds()){
                var captured=capture(job.context(),corridorBudget);Purchase p=job.dispatch().purchase();var a=captured.authority();boolean owners=p.buyerNation().equals(a.cityNations().get(p.buyerCity()))&&p.sellerNation().equals(a.cityNations().get(p.sellerCity()));TreasuryRefundPort.Delivery delivery;
                try{if(owners)delivery=refundTreasury.refund(job.dispatch().request());else{var proof=refundTreasury.receipt(job.dispatch().refund().transactionId());delivery=proof.map(r->new TreasuryRefundPort.Delivery(job.dispatch().refund().transactionId(),r.matches(job.dispatch().request())?TreasuryTransferPort.Status.CONFIRMED:TreasuryTransferPort.Status.CONFLICT,r,"归属变化，仅核对原退款回执")).orElseGet(()->new TreasuryRefundPort.Delivery(job.dispatch().refund().transactionId(),TreasuryTransferPort.Status.RETRY,null,"原城市归属变化，退款与商品继续冻结"));}}catch(RuntimeException unavailable){delivery=new TreasuryRefundPort.Delivery(job.dispatch().refund().transactionId(),TreasuryTransferPort.Status.RETRY,null,"退款回执暂不可查询，原凭据和货物继续冻结");}
                var resultDelivery=delivery;jobs.add(runtime.async(()->{for(CoreCity c:captured.cities())market.ensureCity(c);return refunds.finish(job.dispatch(),resultDelivery,System.currentTimeMillis());}));
            }
            for(ReturnJob job:work.returns()){
                var captured=capture(job.context(),corridorBudget);jobs.add(runtime.async(()->{for(CoreCity c:captured.cities())market.ensureCity(c);try{return refunds.complete(job.refund().id(),captured.authority());}catch(java.sql.SQLException blocked){plugin.getLogger().fine("已退款商品继续托管："+blocked.getMessage());return null;}}));
            }
            return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new));
        }).thenCompose(future->future));return result.whenComplete((v,e)->ticking.set(false));}
    private LogisticsController.Captured capture(Context context,CorridorCapture budget){return authority.capture(SYSTEM,context.nodes(),context.routes(),context.cities(),null,budget);}
    @Override public void close(){closed=true;}
    public static long parseQuantity(String text){
        if(text==null||!text.matches("[1-9][0-9]{0,6}"))throw new IllegalArgumentException("购买数量须为正整数");
        long units=Long.parseLong(text);if(units>1_000_000)throw new IllegalArgumentException("购买数量超限");return units;
    }
    public static BigDecimal parsePrice(String text){
        if(text==null||text.length()>24||!text.matches("(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,2})?"))throw new IllegalArgumentException("单价须为普通正十进制数字，最多两位小数");
        return TreasuryTransferPort.money(new BigDecimal(text));
    }
    private static int offset(String[] args,int at){int page=args.length>at?Integer.parseInt(args[at]):1;if(page<1||page>100000)throw new IllegalArgumentException("页码超限");return Math.multiplyExact(page-1,10);}
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}

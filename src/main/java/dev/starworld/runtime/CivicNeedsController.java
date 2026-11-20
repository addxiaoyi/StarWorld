package dev.starworld.runtime;

import dev.starworld.civic.CivicNeedsService;
import dev.starworld.civic.CivicNeedsState.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.CoreBridge;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Current Paper authority only; the service selects actual persisted life evidence itself. */
public final class CivicNeedsController {
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;private final CivicNeedsService service;private final Function<String,Optional<String>> unavailable;
    public CivicNeedsController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,CivicNeedsService service){this(plugin,runtime,core,service,type->Optional.empty());}
    public CivicNeedsController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,CivicNeedsService service,Function<String,Optional<String>> unavailable){this.plugin=Objects.requireNonNull(plugin);this.runtime=Objects.requireNonNull(runtime);this.core=Objects.requireNonNull(core);this.service=Objects.requireNonNull(service);this.unavailable=Objects.requireNonNull(unavailable);}
    public Optional<String> unavailable(String type){requireMain();return Objects.requireNonNull(unavailable.apply(type));}
    public record Captured(CoreCity city,long period,Set<UUID> verified,String actor){public Captured{verified=Set.copyOf(verified);}}
    public Captured capture(Player player,CoreCity expected,CityView registered){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("城市需求授权必须在 Paper 主线程捕获");requireUse(player);if(!player.isOnline())throw new IllegalArgumentException("玩家已离线");CoreCity member=current(player),live=core.city(expected.cityId()).orElseThrow(()->new IllegalArgumentException("城市已不存在"));
        if(!same(member,expected)||!same(live,expected)||!registered.id().equals(expected.cityId())||!registered.nationId().equals(expected.nationId()))throw new IllegalArgumentException("城市或国家归属已变化，请刷新");manager(player,live);return new Captured(live,runtime.currentPeriod(),runtime.verifiedBuildings(registered),player.getUniqueId().toString());
    }
    private record RequestRead(CityView city,Optional<Request> previous){}
    public void requestFromPreview(Player player,CoreCity expected,String type,UUID requestId,Consumer<Request> completed){
        requireMain();requireUse(player);manager(player,expected);Objects.requireNonNull(requestId);Objects.requireNonNull(completed);
        runtime.reply(player,runtime.city(expected.cityId()).thenCompose(optional->runtime.async(()->new RequestRead(optional.orElseThrow(()->new IllegalArgumentException("城市尚未登记扩展状态，请先登记设施")),service.request(requestId)))),read->{
            Captured captured=capture(player,expected,read.city());long requested=read.previous().map(Request::requestedPeriod).orElse(captured.period());
            boolean matchingRetry=read.previous().filter(previous->previous.cityId().equals(captured.city().cityId())&&previous.nationId().equals(captured.city().nationId())&&previous.type().equals(type)&&previous.actor().equals(captured.actor())).isPresent();
            if(!matchingRetry){Optional<String> reason=unavailable(type);if(reason.isPresent()){player.sendMessage("§c"+reason.get());return;}}
            runtime.reply(player,runtime.async(()->service.request(requestId,captured.city(),type,requested,captured.actor())),request->{player.sendMessage("§a城市需求回执 "+request.id()+" · "+request.definition().name()+"；请求="+request.requestedPeriod()+"；期限="+request.expiryPeriod()+"；发起不扣料，完成时提交真实材料与生活凭据");completed.accept(request);});
        });
    }
    public void completeFromPreview(Player player,CoreCity expected,UUID requestId,Consumer<Receipt> completed){
        requireMain();requireUse(player);manager(player,expected);Objects.requireNonNull(requestId);Objects.requireNonNull(completed);
        runtime.reply(player,runtime.city(expected.cityId()),optional->{Captured captured=capture(player,expected,optional.orElseThrow(()->new IllegalArgumentException("城市尚未登记扩展状态")));runtime.reply(player,runtime.async(()->service.complete(requestId,captured.city(),captured.period(),captured.actor())),receipt->{player.sendMessage("§a需求完成回执 "+receipt.requestId()+"；新提交="+receipt.applied()+"；实付="+receipt.paidItems()+"；真实生活凭据="+receipt.claimedActionUUIDs()+"；稳定/繁荣="+receipt.actualStability()+"/"+receipt.actualProsperity());completed.accept(receipt);});});
    }
    public boolean execute(Player player,String[] args){
        if(args.length==0||!args[0].equalsIgnoreCase("civic"))return false;
        try{requireUse(player);String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
            switch(action){
                case "options"->{require(args.length==2,"/sw civic options");service.definitions().needs().values().stream().sorted(Comparator.comparing(n->n.id())).forEach(n->player.sendMessage("§e"+n.id()+" "+n.name()+"；完成材料="+n.costs()+"；请求后真实生活贡献="+n.actionKind()+" ×"+n.actionCount()+"；不同来源="+n.distinctSources()+"；期限="+n.lifetimePeriods()+"；冷却="+n.cooldownPeriods()+"；稳定/繁荣上限="+n.stabilityReward()+"/"+n.prosperityReward()+unavailable(n.id()).map(reason->"；暂不可用："+reason).orElse("；当前可发起")));}
                case "request"->{require(args.length==4,"/sw civic request <需求ID> <requestUUID>");requestFromPreview(player,current(player),args[2],UUID.fromString(args[3]),request->{});}
                case "complete"->{require(args.length==3,"/sw civic complete <requestUUID>");completeFromPreview(player,current(player),UUID.fromString(args[2]),receipt->{});}
                case "status"->{require(args.length<=3,"/sw civic status [页]");CoreCity city=current(player);int limit=pageSize(),offset=offset(args,limit);runtime.reply(player,runtime.async(()->service.requests(city.cityId(),limit,offset)),rows->{if(!readable(player,city))return;if(rows.isEmpty())player.sendMessage("§7本页没有城市需求");rows.forEach(r->player.sendMessage("§e"+r.id()+" "+r.definition().name()+"；状态="+(r.expiredAt(runtime.currentPeriod())?"已过期":r.status())+"；原国="+r.nationId()+"；请求/期限="+r.requestedPeriod()+"/"+r.expiryPeriod()+"；完成材料="+r.definition().costs()));});}
                case "history"->{require(args.length==2||args.length==3,"/sw civic history [页]");CoreCity city=current(player);int limit=pageSize(),offset=offset(args,limit);runtime.reply(player,runtime.async(()->service.history(city.cityId(),limit,offset)),rows->{if(!readable(player,city))return;if(rows.isEmpty())player.sendMessage("§7本页没有需求历史");rows.forEach(h->player.sendMessage("§e"+h.period()+" "+h.requestId()+" "+h.kind()+" §f"+h.detail()));});}
                default->throw new IllegalArgumentException("/sw civic options|request <ID> <UUID>|complete <UUID>|status [页]|history [页]");
            }
        }catch(IllegalArgumentException error){player.sendMessage("§c"+error.getMessage());}return true;
    }
    private int pageSize(){return Math.min(8,service.definitions().maximumPage());}
    private int offset(String[] args,int limit){int page=args.length>2?Integer.parseInt(args[2]):1;if(page<1||page>service.definitions().maximumOffset()/limit+1)throw new IllegalArgumentException("城市需求页码超限");return Math.multiplyExact(page-1,limit);}
    private CoreCity current(Player p){return runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));}
    private boolean readable(Player p,CoreCity expected){return p.isOnline()&&p.hasPermission("starworld.use")&&runtime.playerCity(p).filter(c->same(c,expected)).isPresent();}
    private static boolean same(CoreCity a,CoreCity b){return a.cityId().equals(b.cityId())&&a.nationId().equals(b.nationId());}
    private void manager(Player p,CoreCity city){if(!runtime.manage(p,city.cityId()))throw new IllegalArgumentException("需要城市管理权限");}
    private void requireMain(){if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("城市需求授权必须在 Paper 主线程捕获");}
    private static void requireUse(Player p){if(!p.hasPermission("starworld.use"))throw new IllegalArgumentException("需要 starworld.use 权限");}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

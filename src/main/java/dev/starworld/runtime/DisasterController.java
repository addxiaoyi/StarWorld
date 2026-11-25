package dev.starworld.runtime;

import dev.starworld.disaster.DisasterResponseService;
import dev.starworld.disaster.DisasterState.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.CoreBridge;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper-only authorization and immutable facility capture before asynchronous response storage. */
public final class DisasterController {
    private static final int PAGE_SIZE=8;
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;private final DisasterResponseService service;
    public DisasterController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,DisasterResponseService service){this.plugin=Objects.requireNonNull(plugin);this.runtime=Objects.requireNonNull(runtime);this.core=Objects.requireNonNull(core);this.service=Objects.requireNonNull(service);}
    public record Captured(CoreCity city,EventState event,long period,Set<UUID> verified,String actor){public Captured{verified=Set.copyOf(verified);}}

    /** Rechecks membership, nation authority, current permission and physical anchors after the storage read. */
    public Captured capture(Player player,CoreCity expected,CityView registered){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("救灾授权必须在 Paper 主线程捕获");
        if(!player.isOnline())throw new IllegalArgumentException("玩家已离线");
        CoreCity member=current(player);CoreCity current=core.city(expected.cityId()).orElseThrow(()->new IllegalArgumentException("城市已不存在"));
        if(!member.cityId().equals(expected.cityId())||!member.nationId().equals(expected.nationId())||!current.cityId().equals(expected.cityId())||!current.nationId().equals(expected.nationId())||!registered.id().equals(expected.cityId())||!registered.nationId().equals(expected.nationId()))throw new IllegalArgumentException("城市归属已变化，请重新打开救灾状态");
        manager(player,current);
        Set<UUID> actual=runtime.verifiedBuildings(registered);
        return new Captured(current,registered.event(),runtime.currentPeriod(),actual,player.getUniqueId().toString());
    }

    /** GUI confirmation keeps the displayed event and request ID while repeating all live authorization. */
    public void respondFromPreview(Player player,CoreCity expected,String strategy,UUID requestId,EventState previewEvent,Consumer<Response> completed){
        requireUse(player);
        respond(player,expected,strategy,requestId,previewEvent,true,completed);
    }
    private void respond(Player player,CoreCity expected,String strategy,UUID requestId,EventState previewEvent,boolean usePreview,Consumer<Response> completed){
        manager(player,expected);Objects.requireNonNull(requestId);Objects.requireNonNull(completed);
        runtime.reply(player,runtime.open(expected),view->{if(usePreview)requireUse(player);Captured capture=capture(player,expected,view);EventState event=usePreview?previewEvent:capture.event();runtime.reply(player,runtime.async(()->service.request(requestId,capture.city(),strategy,capture.period(),capture.verified(),capture.actor(),event)),response->{player.sendMessage("§a救灾回执 "+response.requestId()+" · "+response.strategyId()+"；成本="+response.costs()+"；生效周期="+response.effectivePeriod()+"；截止="+response.expiresPeriod());completed.accept(response);});});
    }

    public boolean execute(Player player,String[] args){
        if(args.length==0||!args[0].equalsIgnoreCase("disaster"))return false;
        try{
            String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
            switch(action){
                case "help"->player.sendMessage(new String[]{"§6灾害治理：/sw disaster options", "/sw disaster respond <策略ID> <requestUUID>","/sw disaster status [页] /sw disaster history [页]","§7策略消耗真实城市仓储；从下一未结算周期生效；重复同一 UUID 返回原回执"});
                case "options"->{require(args.length==2,"/sw disaster options");service.configuration().strategies().values().stream().sorted(Comparator.comparing(Strategy::id)).forEach(strategy->player.sendMessage("§e"+strategy.id()+" §f事件="+strategy.eventType()+"；设施="+strategy.facilities()+"；成本="+strategy.costs()+"；负面缓解="+strategy.reliefPermille()/10.0+"%；每期恢复="+strategy.recoveryPerStep()+"/稳定"+strategy.stabilityPerStep()+"/繁荣"+strategy.prosperityPerStep()+"；最多"+strategy.maxSteps()+"期；预算="+strategy.budget()+(strategy.importItem()==null?"":"；需近期真实进口回执="+strategy.importItem()+"×"+strategy.importUnits())));}
                case "respond"->{require(args.length==4,"/sw disaster respond <策略ID> <requestUUID>");CoreCity city=current(player);respond(player,city,args[2],UUID.fromString(args[3]),null,false,response->{});}
                case "status"->{require(args.length==2||args.length==3||args.length==1,"/sw disaster status [页]");CoreCity city=current(player);int offset=offset(args),limit=pageSize();runtime.reply(player,runtime.open(city),view->{player.sendMessage("§e当前事件："+Objects.toString(view.event(),"无")+"；当前周期="+runtime.currentPeriod());runtime.reply(player,runtime.async(()->service.responses(city.cityId(),limit,offset)),rows->{if(rows.isEmpty())player.sendMessage("§7本页没有救灾回执；/sw disaster options 查看策略");for(Response response:rows)player.sendMessage("§e"+response.requestId()+" §f"+response.strategyId()+"；生效="+response.effectivePeriod()+"；截止="+response.expiresPeriod()+"；恢复="+response.steps()+"/"+response.maxSteps()+"；剩余预算="+response.remainingBudget()+"；最近="+response.lastAppliedPeriod());});});}
                case "history"->{require(args.length==2||args.length==3,"/sw disaster history [页]");CoreCity city=current(player);int offset=offset(args),limit=pageSize();runtime.reply(player,runtime.async(()->service.history(city.cityId(),limit,offset)),rows->{if(rows.isEmpty())player.sendMessage("§7本页没有救灾历史");for(History history:rows)player.sendMessage("§e"+history.period()+" "+history.requestId()+" "+history.kind()+" §f"+history.detail());});}
                default->throw new IllegalArgumentException("/sw disaster options|respond <策略ID> <requestUUID>|status [页]|history [页]");
            }
        }catch(IllegalArgumentException error){player.sendMessage("§c"+error.getMessage());}
        return true;
    }
    private CoreCity current(Player player){return runtime.playerCity(player).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));}
    private void manager(Player player,CoreCity city){if(!runtime.manage(player,city.cityId()))throw new IllegalArgumentException("需要城市管理权限");}
    private static void requireUse(Player player){if(!player.hasPermission("starworld.use"))throw new IllegalArgumentException("需要 starworld.use 权限");}
    private int pageSize(){return Math.min(PAGE_SIZE,service.configuration().data().maxPage());}
    private int offset(String[] args){int page=args.length>2?Integer.parseInt(args[2]):1;if(page<1)throw new IllegalArgumentException("页码必须大于零");int size=pageSize(),maximum=1_000_000/size+1;if(page>maximum)throw new IllegalArgumentException("页码须为 1–"+maximum);return Math.multiplyExact(page-1,size);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

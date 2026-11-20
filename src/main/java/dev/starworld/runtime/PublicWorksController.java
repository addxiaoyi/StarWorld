package dev.starworld.runtime;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.CoreBridge;
import dev.starworld.works.PublicWorksService;
import dev.starworld.works.PublicWorksState.*;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper authorization boundary. Read-only city lookup never repairs or reactivates storage authority. */
public final class PublicWorksController {
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;private final PublicWorksService service;
    public PublicWorksController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,PublicWorksService service){this.plugin=Objects.requireNonNull(plugin);this.runtime=Objects.requireNonNull(runtime);this.core=Objects.requireNonNull(core);this.service=Objects.requireNonNull(service);}
    public record Captured(CoreCity city,long period,Set<UUID> verified,String actor){public Captured{verified=Set.copyOf(verified);}}
    public Captured capture(Player player,CoreCity expected,CityView registered){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("公共工程授权必须在 Paper 主线程捕获");requireUse(player);if(!player.isOnline())throw new IllegalArgumentException("玩家已离线");
        CoreCity member=current(player),live=core.city(expected.cityId()).orElseThrow(()->new IllegalArgumentException("城市已不存在"));
        if(!same(member,expected)||!same(live,expected)||!registered.id().equals(expected.cityId())||!registered.nationId().equals(expected.nationId()))throw new IllegalArgumentException("城市或国家归属已变化，请刷新");
        manager(player,live);return new Captured(live,runtime.currentPeriod(),runtime.verifiedBuildings(registered),player.getUniqueId().toString());
    }
    /** Preview UUID stays fixed; costs/requirements and current core authority are checked again. */
    public void startFromPreview(Player player,CoreCity expected,String definition,UUID requestId,Consumer<Project> completed){
        requireMain();requireUse(player);manager(player,expected);Objects.requireNonNull(requestId);Objects.requireNonNull(completed);
        runtime.reply(player,runtime.city(expected.cityId()),optional->{CityView view=optional.orElseThrow(()->new IllegalArgumentException("城市尚未登记扩展状态，请先登记设施"));Captured captured=capture(player,expected,view);
            runtime.reply(player,runtime.async(()->service.start(requestId,captured.city(),definition,captured.period(),captured.verified(),captured.actor())),project->{player.sendMessage("§a公共工程回执 "+project.requestId()+" · "+project.definition().name()+"；进度="+project.progress()+"/"+project.definition().buildPeriods()+"；起建周期="+project.effectivePeriod());completed.accept(project);});
        });
    }
    public boolean execute(Player player,String[] args){
        if(args.length==0||!args[0].equalsIgnoreCase("works"))return false;
        try{requireUse(player);String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
            switch(action){
                case "options"->{require(args.length==2,"/sw works options");service.configuration().projects().values().stream().sorted(Comparator.comparing(ProjectDefinition::id)).forEach(d->player.sendMessage("§e"+d.id()+" "+d.name()+"；起建="+d.upfrontCosts()+"；每步="+d.constructionCosts()+"；维护="+d.upkeepCosts()+"；设施="+d.facilities()+"；地形="+d.terrains()+"；规划="+d.plans()+"；文明="+d.civilizations()+"；知识/文化="+d.knowledge()+"/"+d.culture()+"；工期="+d.buildPeriods()));}
                case "start"->{require(args.length==4,"/sw works start <工程ID> <requestUUID>");startFromPreview(player,current(player),args[2],UUID.fromString(args[3]),project->{});}
                case "status"->{require(args.length<=3,"/sw works status [页]");CoreCity city=current(player);int limit=pageSize(),offset=offset(args,limit);runtime.reply(player,runtime.async(()->service.projects(city.cityId(),limit,offset)),rows->{if(!readable(player,city))return;if(rows.isEmpty())player.sendMessage("§7本页没有工程；/sw works options 查看选项");for(Project p:rows)player.sendMessage("§e"+p.requestId()+" "+p.definition().name()+"；原国家="+p.nationId()+"；进度="+p.progress()+"/"+p.definition().buildPeriods()+"；完成="+p.completedPeriod()+"；最近维护周期="+p.lastOperationPeriod()+"；维护已付="+p.lastOperationPaid());});}
                case "history"->{require(args.length==2||args.length==3,"/sw works history [页]");CoreCity city=current(player);int limit=pageSize(),offset=offset(args,limit);runtime.reply(player,runtime.async(()->service.history(city.cityId(),limit,offset)),rows->{if(!readable(player,city))return;if(rows.isEmpty())player.sendMessage("§7本页没有工程历史");rows.forEach(h->player.sendMessage("§e"+h.period()+" "+h.requestId()+" "+h.kind()+" §f"+h.detail()));});}
                default->throw new IllegalArgumentException("/sw works options|start <ID> <UUID>|status [页]|history [页]");
            }
        }catch(IllegalArgumentException error){player.sendMessage("§c"+error.getMessage());}return true;
    }
    private int pageSize(){return Math.min(8,service.configuration().data().maxPage());}
    private static int offset(String[] args,int limit){int page=args.length>2?Integer.parseInt(args[2]):1;if(page<1||page>1_000_000/limit+1)throw new IllegalArgumentException("公共工程页码超限");return Math.multiplyExact(page-1,limit);}
    private CoreCity current(Player player){return runtime.playerCity(player).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));}
    private boolean readable(Player p,CoreCity expected){return p.isOnline()&&p.hasPermission("starworld.use")&&runtime.playerCity(p).filter(c->same(c,expected)).isPresent();}
    private static boolean same(CoreCity a,CoreCity b){return a.cityId().equals(b.cityId())&&a.nationId().equals(b.nationId());}
    private void manager(Player player,CoreCity city){if(!runtime.manage(player,city.cityId()))throw new IllegalArgumentException("需要城市管理权限");}
    private void requireMain(){if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("公共工程授权必须在 Paper 主线程捕获");}
    private static void requireUse(Player p){if(!p.hasPermission("starworld.use"))throw new IllegalArgumentException("需要 starworld.use 权限");}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

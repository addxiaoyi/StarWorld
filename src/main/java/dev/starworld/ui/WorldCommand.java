package dev.starworld.ui;

import dev.starworld.runtime.WorldRuntime;
import java.util.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class WorldCommand implements CommandExecutor,TabCompleter {
    private final WorldRuntime runtime;private final CityMenu menu;private final GovernanceMenu governance;
    public WorldCommand(WorldRuntime runtime,CityMenu menu){this(runtime,menu,new GovernanceMenu(runtime));}
    public WorldCommand(WorldRuntime runtime,CityMenu menu,GovernanceMenu governance){this.runtime=runtime;this.menu=menu;this.governance=governance;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!sender.hasPermission("starworld.use")){sender.sendMessage("§c需要 starworld.use 权限");return true;}
        String sub=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);
        try{
            switch(sub){
                case "help" -> sender.sendMessage(new String[]{"§6StarWorld · 动态文明世界", "/sw menu|develop|atlas|regions|climate|market-menu|disaster-menu|status|stock|buildings", "/sw register <设施ID> （看向配置锚点） /sw survey （勘探脚下区块）", "/sw deposit|withdraw <命名空间:物品> <数量>", "/sw civilization <文明ID> /sw upgrade|remove <设施UUID>", "/sw development|history|culture|nation /sw plan|specialty <ID>","/sw nation-development /sw national-specialty <ID>","/sw node|route|trade|demand help （交通、改道、运力）","/sw market help （实物金币市场，购买需国家创始人）","/sw disaster options|respond <策略ID> <requestUUID>|status|history","/sw governance|works-menu|civic-menu","/sw works options|start <ID> <UUID>|status|history","/sw civic options|request <ID> <UUID>|complete <UUID>|status|history","/sw profession <职业> /sw feast <宴会> /sw decoration /sw concert","/sw export <契约ID> <批数> /sw outbox","/sw event <事件ID> /sw pending /sw resolve <UUID> <credit|discard> /sw maintenance [backup <文件名>] （管理员）"});
                case "status" -> {sender.sendMessage("§6StarWorld "+runtime.version());runtime.integrationStatus().forEach((k,v)->sender.sendMessage("§e"+k+"§f: "+v));}
                case "menu" -> menu.open(player(sender));
                case "develop" -> menu.openDevelopment(player(sender));
                case "atlas" -> menu.openAtlas(player(sender),"");
                case "market-menu" -> menu.openMarket(player(sender),0);
                case "disaster-menu" -> {require(args,1);menu.openDisaster(player(sender));}
                case "governance" -> {require(args,1);governance.open(player(sender));}
                case "works-menu" -> {require(args,1);governance.openWorks(player(sender),0);}
                case "civic-menu" -> {require(args,1);governance.openCivic(player(sender),0);}
                case "register" -> {require(args,2);runtime.register(player(sender),args[1]);}
                case "survey" -> {require(args,1);runtime.survey(player(sender));}
                case "civilization" -> {require(args,2);runtime.civilization(player(sender),args[1]);}
                case "upgrade","remove" -> {require(args,2);UUID id=UUID.fromString(args[1]);if(sub.equals("upgrade"))runtime.upgrade(player(sender),id);else runtime.remove(player(sender),id);}
                case "stock","buildings" -> {Player p=player(sender);var c=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));runtime.reply(p,runtime.open(c),v->{if(sub.equals("stock"))p.sendMessage("§e城市仓储："+v.stock());else {p.sendMessage("§e设施数量："+v.buildings().size());v.buildings().forEach(b->p.sendMessage(b.id()+" "+b.type()+" Lv"+b.level()));}});}
                case "deposit","withdraw" -> {require(args,3);runtime.transfer(player(sender),args[1],Integer.parseInt(args[2]),sub.equals("deposit"));}
                case "event" -> {admin(sender);require(args,2);runtime.trigger(player(sender),args[1]);}
                case "pending" -> {admin(sender);Player p=player(sender);runtime.reply(p,runtime.pending(),list->{p.sendMessage("§e待核对凭据："+list.size());list.forEach(t->p.sendMessage(t.toString()));});}
                case "resolve" -> {admin(sender);require(args,3);if(!Set.of("credit","discard").contains(args[2]))throw new IllegalArgumentException("使用 credit 或 discard");Player p=player(sender);runtime.reply(p,runtime.resolve(UUID.fromString(args[1]),args[2].equals("credit"),p.getUniqueId().toString()),ok->p.sendMessage("§a凭据已人工核对并记入审计"));}
                default -> {if(!(sender instanceof Player p)||!runtime.expansionCommand(p,args))throw new IllegalArgumentException("未知命令，请使用 /sw help");}
            }
        }catch(Exception e){sender.sendMessage("§cStarWorld: "+e.getMessage());}
        return true;
    }
    private static Player player(CommandSender sender){if(!(sender instanceof Player p))throw new IllegalArgumentException("此命令需要玩家执行");return p;}
    private static void admin(CommandSender sender){if(!sender.hasPermission("starworld.admin"))throw new IllegalArgumentException("需要管理员权限");}
    private static void require(String[] args,int n){if(args.length!=n)throw new IllegalArgumentException("参数数量不正确，请使用 /sw help");}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(!sender.hasPermission("starworld.use"))return List.of();
        List<String> values=List.of();
        if(args.length==1){values=new ArrayList<>(List.of("menu","develop","atlas","regions","climate","market","market-menu","disaster","disaster-menu","governance","works-menu","civic-menu","works","civic","help","status","stock","buildings","register","survey","civilization","deposit","withdraw","upgrade","remove","development","plan","specialty","history","culture","nation","nation-development","national-specialty","export","outbox","node","route","trade","demand","profession","feast","decoration","concert"));if(sender.hasPermission("starworld.admin"))values.addAll(List.of("event","pending","resolve","maintenance"));}
        else if(args.length==2)values=switch(args[0]){case "register"->runtime.definitions().buildings().keySet().stream().sorted().toList();case "civilization"->runtime.definitions().civilizations().keySet().stream().sorted().toList();case "event"->sender.hasPermission("starworld.admin")?runtime.definitions().events().keySet().stream().sorted().toList():List.of();case "deposit","withdraw"->dev.starworld.config.ConfigLoader.items(runtime.definitions()).stream().sorted().toList();case "disaster"->List.of("options","respond","status","history","help");case "works"->List.of("options","start","status","history");case "civic"->List.of("options","request","complete","status","history");case "maintenance"->sender.hasPermission("starworld.admin")?List.of("backup"):List.of();default->List.of();};
        else if(args.length==3&&args[0].equalsIgnoreCase("disaster")&&args[1].equalsIgnoreCase("respond"))values=runtime.expansion().map(x->x.disasters().configuration().strategies().keySet().stream().sorted().toList()).orElse(List.of());
        else if(args.length==3&&args[0].equalsIgnoreCase("works")&&args[1].equalsIgnoreCase("start"))values=runtime.expansion().map(x->x.works().configuration().projects().keySet().stream().sorted().toList()).orElse(List.of());
        else if(args.length==3&&args[0].equalsIgnoreCase("civic")&&args[1].equalsIgnoreCase("request"))values=runtime.expansion().map(x->x.civic().definitions().needs().keySet().stream().sorted().toList()).orElse(List.of());
        else if(args.length==3 && args[0].equals("resolve") && sender.hasPermission("starworld.admin"))values=List.of("credit","discard");
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);return values.stream().filter(v->v.startsWith(prefix)).sorted().toList();
    }
}

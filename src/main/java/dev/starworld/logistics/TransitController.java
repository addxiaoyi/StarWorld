package dev.starworld.logistics;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.integration.*;
import dev.starworld.integration.TransitPermitPort.*;
import dev.starworld.logistics.LogisticsState.*;
import dev.starworld.runtime.WorldRuntime;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Two explicit founder actions: preview immutable corridor terms, then approve the saved draft. */
public final class TransitController {
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;private final LogisticsService logistics;private final TransitPermitPort permits;
    public TransitController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,LogisticsService logistics,TransitPermitPort permits){this.plugin=plugin;this.runtime=runtime;this.core=core;this.logistics=logistics;this.permits=permits;}
    private record Context(Route route,Map<UUID,Node> nodes){Context{nodes=Map.copyOf(nodes);}}
    private record ApprovalContext(TransitProposalService.Proposal proposal,Context corridor){}
    public boolean execute(Player p,String[] args){
        if(args.length<2||!args[0].equalsIgnoreCase("route"))return false;String op=args[1].toLowerCase(Locale.ROOT);
        if(!Set.of("transit","transit-propose","transit-approve","transit-revoke").contains(op))return false;
        try{switch(op){
            case "transit"->{
                require(args.length==3,"/sw route transit <商路UUID>");UUID id=UUID.fromString(args[2]);
                load(p,()->context(id),c->{CoreCity city=current(p);require(c.route().originCity().equals(city.cityId())||c.route().targetCity().equals(city.cityId())||c.nodes().values().stream().anyMatch(n->n.nationId().equals(city.nationId())),"仅路线参与国家可查询过境许可");
                    var footprint=describe(c);p.sendMessage("§e过境商路 "+id+" · 涉及 "+footprint.parcels().size()+" 国 · 第三国节点维护由起点城承担");
                    for(UUID nation:new TreeSet<>(footprint.parcels().keySet())){var permit=permits.permit(c.route().transitKey(),nation);p.sendMessage("§7国家 "+nation+" · 区块 "+footprint.parcels().get(nation).size()+" · "+permit.map(v->"版本 "+v.revision()+" · 每件 "+v.terms().tollPerUnit()+" · "+(v.revoked()?"已撤销":footprint.matches(v.terms())&&permits.allowed(v)?"当前有效":"当前不能通行")).orElse("尚未批准"));}
                    p.sendMessage("§7过境国创始人可用 /sw route transit-propose "+id+" <每件金币> <有效小时1–720> 预览许可");
                });
            }
            case "transit-propose"->{
                require(args.length==5,"/sw route transit-propose <商路UUID> <每件金币> <有效小时1–720>");UUID id=UUID.fromString(args[2]);require(args[3].matches("[0-9]{1,7}(\\.[0-9]{1,2})?"),"过境费使用最多两位小数");BigDecimal toll=TransitPermitPort.toll(new BigDecimal(args[3]));int hours=Integer.parseInt(args[4]);require(hours>=1&&hours<=720,"许可有效期为1至720小时");UUID request=UUID.randomUUID();
                load(p,()->context(id),c->{CoreCity city=founder(p,null);var footprint=describe(c);long now=System.currentTimeMillis();Terms terms=footprint.terms(city.nationId(),toll,Math.addExact(now,hours*3_600_000L));long revision=permits.permit(terms.routeKey(),city.nationId()).map(Permit::revision).orElse(0L);
                    UUID actor=p.getUniqueId();runtime.reply(p,runtime.async(()->logistics.transitProposals().prepare(request,id,actor,terms,revision,now)),draft->{
                        p.sendMessage("§e许可草案 "+draft.id()+" · 商路 "+id+" · 本国 "+terms.parcels().size()+" 区块 · 每件 "+toll+" 国家金币 · 有效至 "+Instant.ofEpochMilli(terms.expiresAt()));
                        p.sendMessage("§7起点国 "+terms.originNation()+" → 终点国 "+terms.targetNation()+"；第三国节点维护由起点城承担。");
                        p.sendMessage("§7批准即同意：买方付款时收取本国过境费；未交付且买卖双方确认退款时，本国原收款全额逆向退还。任一收款国余额不足则整笔退款等待。");
                        p.sendMessage("§e60秒内 /sw route transit-approve "+draft.id()+" 批准；本次预览未授予通行权。");
                    });
                });
            }
            case "transit-approve"->{
                require(args.length==3,"/sw route transit-approve <许可草案UUID>");UUID id=UUID.fromString(args[2]);
                load(p,()->{var draft=logistics.transitProposals().proposal(id).orElseThrow(()->new IllegalArgumentException("许可草案不存在"));return new ApprovalContext(draft,context(draft.route()));},loaded->{
                    var draft=loaded.proposal();founder(p,draft.terms().grantingNation());require(p.getUniqueId().equals(draft.actor()),"请由原预览的当前创始人确认，或重新预览");
                    var previous=permits.receipt(id);
                    if(previous.isEmpty()){long now=System.currentTimeMillis();require(now>=draft.createdAt()&&now<draft.expiresAt(),"许可草案已到期，请重新预览");require(describe(loaded.corridor()).matches(draft.terms()),"过境区块或路线已变化，请重新预览");}
                    Receipt receipt=permits.approve(id,p.getUniqueId(),draft.expectedRevision(),draft.terms());String status=currentStatus(draft.terms());
                    runtime.reply(p,runtime.async(()->logistics.transitProposals().acknowledge(id,receipt)),saved->p.sendMessage("§a已核对批准回执 "+id+" · 原操作版本 "+receipt.permit().revision()+" · "+status));
                });
            }
            case "transit-revoke"->{
                require(args.length==4,"/sw route transit-revoke <商路UUID> <当前许可版本>");UUID id=UUID.fromString(args[2]);long revision=Long.parseLong(args[3]);require(revision>=1,"许可版本必须大于0");
                load(p,()->logistics.route(id).orElseThrow(()->new IllegalArgumentException("商路不存在")),route->{CoreCity city=founder(p,null);require(route.requiresTransit(),"商路未声明第三国过境");UUID request=UUID.nameUUIDFromBytes((route.transitKey()+":revoke:"+city.nationId()+":"+p.getUniqueId()+":"+revision).getBytes(StandardCharsets.UTF_8));
                    Receipt receipt=permits.revoke(request,p.getUniqueId(),revision,route.transitKey(),city.nationId());p.sendMessage("§a已核对撤销回执 "+request+" · 原操作版本 "+receipt.permit().revision()+" · "+currentStatus(receipt.permit().terms()));
                });
            }
            default->throw new IllegalArgumentException("未知过境命令");
        }}catch(IllegalArgumentException failure){p.sendMessage("§c"+failure.getMessage());}return true;
    }
    private String currentStatus(Terms terms){var now=permits.permit(terms.routeKey(),terms.grantingNation()).orElseThrow();return "当前版本 "+now.revision()+" · "+(now.revoked()?"已撤销":permits.allowed(now)?"核心当前允许通行，仍需现场道路验证":"当前不能通行");}
    private CoreCity current(Player p){require(p.isOnline()&&p.hasPermission("starworld.use"),"需要在线且拥有 starworld.use 权限");return runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));}
    private CoreCity founder(Player p,UUID expected){CoreCity city=current(p);require((expected==null||expected.equals(city.nationId()))&&core.canGovern(p.getUniqueId(),city.cityId()),"仅许可所属国家的当前创始人可批准或撤销");return city;}
    private Context context(UUID id)throws Exception{Route route=logistics.route(id).orElseThrow(()->new IllegalArgumentException("商路不存在"));Map<UUID,Node> nodes=new HashMap<>();for(UUID node:route.nodes())nodes.put(node,logistics.node(node).orElseThrow());return new Context(route,nodes);}
    private TransitCorridor.Footprint describe(Context c){return TransitCorridor.describe(plugin.getServer(),core,c.route(),c.nodes());}
    private <T>void load(Player p,WorldRuntime.Work<T> work,Consumer<T> action){runtime.reply(p,runtime.async(work),result->{try{action.accept(result);}catch(IllegalArgumentException failure){p.sendMessage("§c"+failure.getMessage());}});}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}

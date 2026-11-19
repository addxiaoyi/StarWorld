package dev.starworld.life;

import dev.starworld.domain.WorldState.*;
import dev.starworld.integration.*;
import dev.starworld.life.LifeState.*;
import dev.starworld.runtime.WorldRuntime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Small command adapter; storage callbacks always return to the player's scheduler before world checks. */
public final class LifeController {
    private final JavaPlugin plugin;private final WorldRuntime runtime;private final CoreBridge core;
    private final LifeService life;private final LifePlatform platform;
    private final Map<UUID,Long> pending=new ConcurrentHashMap<>();
    public LifeController(JavaPlugin plugin,WorldRuntime runtime,CoreBridge core,CustomItems items,LifeService life,LifePlatform platform) {
        this.plugin=Objects.requireNonNull(plugin);this.runtime=Objects.requireNonNull(runtime);this.core=Objects.requireNonNull(core);
        Objects.requireNonNull(items);this.life=Objects.requireNonNull(life);this.platform=Objects.requireNonNull(platform);
    }
    /** Receives full command arguments, with profession/feast/decoration/concert at index zero. */
    public boolean execute(Player player,String[] args) {
        if(args.length==0)return false;
        return switch(args[0].toLowerCase(Locale.ROOT)) {
            case "profession"->{profession(player,args);yield true;}
            case "feast"->{feast(player,args);yield true;}
            case "decoration"->{decoration(player,args);yield true;}
            case "concert"->{concert(player,args);yield true;}
            default->false;
        };
    }
    private void profession(Player p,String[] args) {
        if(args.length!=2){p.sendMessage("§e/sw profession <"+String.join("|",life.definitions().professions().keySet())+">");return;}
        UUID actor=p.getUniqueId();long period=runtime.currentPeriod();
        runtime.reply(p,runtime.async(()->life.chooseProfession(actor,args[1],period)),v->p.sendMessage("§a生活职业："+life.definitions().professions().get(v.profession()).name()+" · 累计文化贡献 "+v.cultureContributed()));
    }
    private void feast(Player p,String[] args) {
        if(args.length!=2){p.sendMessage("§e/sw feast <"+String.join("|",life.definitions().feasts().keySet())+"> · 实际消耗城市仓库物资");return;}
        CoreCity expected=requireCity(p,true);UUID actor=p.getUniqueId(),request=UUID.randomUUID();
        runtime.reply(p,runtime.async(()->life.profession(actor)),profile->{try {if(!sameCity(p,expected,true))return;
            CoreCity city=requireCity(p,true);Set<String> available=platform.availableItems();long period=runtime.currentPeriod();
            runtime.reply(p,runtime.async(()->life.feast(request,city,actor,args[1],period,available)),v->p.sendMessage("§a宴会完成：文化 +"+v.culture()+"；产业临时加成 +"+Math.round(v.productionBonus()*100)+"% · "+v.id()));}catch(RuntimeException e){p.sendMessage("§cStarWorld: "+e.getMessage());}});
    }
    private void decoration(Player p,String[] args) {
        CoreCity city=requireCity(p,args.length>1 && !args[1].equalsIgnoreCase("list"));
        if(args.length==2 && args[1].equalsIgnoreCase("list")) {
            runtime.reply(p,runtime.async(()->life.decorations(city.cityId())),values->{if(!sameCity(p,city,false))return;
                p.sendMessage("§e已登记装饰："+values.size()+"（仅已加载、归属有效并付维护费的家具结算）");
                for(Decoration d:values)p.sendMessage("§7"+d.furnitureId()+" · "+d.itemId()+" · "+d.site().x()+","+d.site().y()+","+d.site().z());});return;
        }
        if(args.length==3 && args[1].equalsIgnoreCase("remove")) {
            UUID furniture=UUID.fromString(args[2]);runtime.reply(p,runtime.async(()->{life.removeDecoration(city.cityId(),furniture);return furniture;}),v->p.sendMessage("§a装饰已注销；不退材料、不重置贡献额度"));return;
        }
        if(args.length==2 && args[1].equalsIgnoreCase("register")) {
            UUID actor=p.getUniqueId();runtime.reply(p,runtime.async(()->life.profession(actor)),profile->{
                try {if(!sameCity(p,city,true))return;CoreCity fresh=requireCity(p,true);
                    DecorationEvidence evidence=platform.inspectTargetDecoration(p,fresh.cityId()).orElseThrow(()->new IllegalArgumentException("请看向六格内、领土内且在 life.yml 允许列表的已加载 CE 家具实体"));long period=runtime.currentPeriod();
                    runtime.reply(p,runtime.async(()->life.registerDecoration(fresh,actor,evidence,period)),v->p.sendMessage("§a装饰已登记："+v.furnitureId()+"；每期现场复核并消耗维护品"));
                }catch(RuntimeException e){p.sendMessage("§cStarWorld: "+e.getMessage());}});return;
        }
        p.sendMessage("§e/sw decoration <register|list|remove UUID>");
    }
    private void concert(Player p,String[] args) {
        if(args.length<2){p.sendMessage("§e/sw concert <start|list|note 会话UUID 音高0–24|finish 会话UUID|cancel 会话UUID>");return;}
        CoreCity city=requireCity(p,false);UUID actor=p.getUniqueId();String operation=args[1].toLowerCase(Locale.ROOT);
        if(operation.equals("list") && args.length==2) {
            runtime.reply(p,runtime.async(()->life.concerts(city.cityId())),sessions->{if(!sameCity(p,city,false))return;p.sendMessage("§e进行中演奏会："+sessions.size());for(ConcertView c:sessions)p.sendMessage("§7"+c.id()+" · 音符 "+c.notes()+" · 听众 "+c.audience());});return;
        }
        if(operation.equals("start") && args.length==2) {
            requireCity(p,true);UUID request=UUID.randomUUID();runtime.reply(p,runtime.async(()->life.profession(actor)),profile->{try {
                if(!sameCity(p,city,true))return;CoreCity fresh=requireCity(p,true);Site site=LifePlatform.site(p.getLocation());if(!core.ownsSite(fresh.cityId(),site))throw new IllegalArgumentException("演奏会必须在本城市领土内开始");
                Set<String> available=platform.availableItems();long period=runtime.currentPeriod(),now=System.currentTimeMillis();
                runtime.reply(p,runtime.async(()->life.startConcert(request,fresh,actor,site,period,now,available)),v->p.sendMessage("§a已消耗演奏补给。会话："+v.id()+"；/sw concert note "+v.id()+" <0–24>，需现场同城听众；结束使用 finish"));
            }catch(RuntimeException e){p.sendMessage("§cStarWorld: "+e.getMessage());}});return;
        }
        if(args.length<3){p.sendMessage("§e请提供演奏会 UUID");return;}
        UUID session=UUID.fromString(args[2]);
        long nowPending=System.currentTimeMillis();Long deadline=pending.get(actor);if(deadline!=null && deadline>nowPending)throw new IllegalArgumentException("上一音符或演奏操作正在结算");pending.put(actor,nowPending+15000);
        var lookup=runtime.async(()->life.concert(session));
        lookup.whenComplete((value,error)->{if(error!=null)pending.remove(actor);});
        runtime.reply(p,lookup,c->{
            try {
                if(!sameCity(p,city,false))throw new IllegalArgumentException("城市归属或权限已改变");
                if(!c.performer().equals(actor))throw new IllegalArgumentException("仅演奏者本人可操作此会话");
                platform.validatePerformer(p,c);
                if(operation.equals("note") && args.length==4) {
                    int pitch=Integer.parseInt(args[3]);NoteReceipt note=platform.playNote(p,c,pitch,c.notes()+1);
                    var work=runtime.async(()->life.note(note));work.whenComplete((v,e)->pending.remove(actor));
                    runtime.reply(p,work,v->p.sendMessage("§a有效音符 "+v.notes()+" · 音高种类 "+Long.bitCount(v.pitchMask())+" · 听众 "+v.audience()));
                }else if(operation.equals("finish") && args.length==3) {
                    CoreCity fresh=requireCity(p,false);long period=runtime.currentPeriod(),now=System.currentTimeMillis();
                    var work=runtime.async(()->life.finishConcert(session,fresh,actor,period,now));work.whenComplete((v,e)->pending.remove(actor));
                    runtime.reply(p,work,v->p.sendMessage("§a演奏结算：文化 +"+v.culture()+"；重复请求不会重复奖励 · "+v.id()));
                }else if(operation.equals("cancel") && args.length==3) {
                    var work=runtime.async(()->{life.cancelConcert(session,actor);return session;});work.whenComplete((v,e)->pending.remove(actor));runtime.reply(p,work,v->p.sendMessage("§e演奏已取消；已消耗补给不自动退款"));
                }else{pending.remove(actor);p.sendMessage("§e/sw concert note <会话UUID> <音高0–24>，或 finish/cancel <会话UUID>");}
            }catch(RuntimeException e){pending.remove(actor);p.sendMessage("§cStarWorld: "+e.getMessage());}
        });
    }
    private CoreCity requireCity(Player p,boolean manager) {CoreCity city=runtime.playerCity(p).orElseThrow(()->new IllegalArgumentException("请先加入 StarCore 城市"));if(manager && !runtime.manage(p,city.cityId()))throw new IllegalArgumentException("需要城市管理权限以消耗公共仓库物资");return city;}
    private boolean sameCity(Player p,CoreCity expected,boolean manager) {
        CoreCity actual=runtime.playerCity(p).orElse(null);
        if(actual==null || !actual.cityId().equals(expected.cityId()) || !actual.nationId().equals(expected.nationId()) || (manager && !runtime.manage(p,actual.cityId()))) {p.sendMessage("§c城市归属或权限已变化，操作终止");return false;}return true;
    }
}

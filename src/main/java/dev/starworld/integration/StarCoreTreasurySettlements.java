package dev.starworld.integration;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** All beneficiaries and the receipt commit together through public NationSettlementService. */
public final class StarCoreTreasurySettlements implements TreasuryTransferPort {
    private static final String BASE="dev.starcore.starcore.";
    private final JavaPlugin owner;
    public StarCoreTreasurySettlements(JavaPlugin owner){this.owner=Objects.requireNonNull(owner);}
    @Override public Delivery transfer(Request request){
        Objects.requireNonNull(request);
        if(request.credits().size()<2)throw new IllegalArgumentException("单收款方应使用原国家转账契约");
        try{
            Access a=access();Optional<Receipt> existing=receipt(a,request.transactionId());if(existing.isPresent())return checked(request,existing.get());
            Class<?> nationsType=a.type("module.nation.NationService");Object nations=optional(a.apiType().getMethod("service",Class.class).invoke(a.api(),nationsType)).orElseThrow();
            Set<UUID> participants=new HashSet<>(request.credits().keySet());participants.add(request.payer());
            for(UUID id:participants)if(optional(nationsType.getMethod("nationById",a.idType()).invoke(nations,a.id(id))).isEmpty())return new Delivery(request.transactionId(),Status.REJECTED,null,"结算国家已解散，拒绝新付款");
            Map<Object,BigDecimal> credits=new HashMap<>();for(var entry:request.credits().entrySet())credits.put(a.id(entry.getKey()),entry.getValue());
            Class<?> payloadType=a.type("module.treasury.NationSettlementService$SettlementRequest");Object payload=payloadType.getConstructor(String.class,a.idType(),Map.class,String.class).newInstance(request.transactionId(),a.id(request.payer()),credits,request.description());
            Object result=a.contract().getMethod("settle",payloadType).invoke(a.service(),payload);
            if(!request.transactionId().equals(result.getClass().getMethod("transactionId").invoke(result)))return new Delivery(request.transactionId(),Status.CONFLICT,null,"多方结算返回了其他凭据");
            Optional<Receipt> after=receipt(a,request.transactionId());if(after.isPresent())return checked(request,after.get());
            String status=String.valueOf(result.getClass().getMethod("status").invoke(result));
            return new Delivery(request.transactionId(),Set.of("REJECTED","INSUFFICIENT_FUNDS").contains(status)?Status.REJECTED:Status.RETRY,null,"核心多方结算 "+status+"；尚无匹配持久回执");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Delivery(request.transactionId(),Status.RETRY,null,"核心原子多方结算暂不可用："+cause(failure).getClass().getSimpleName());}
    }
    @Override public Optional<Receipt> receipt(String id){try{return receipt(access(),id);}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw new IllegalStateException("核心多方结算回执不可查询，不能证明未付款",cause(failure));}}
    @Override public Optional<Receipt> receipt(Request request){return receipt(request.transactionId());}
    @Override public String status(){try{access();return "StarCore 原子多方结算可用";}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return "第三国收费等待核心 NationSettlementService";}}
    private Delivery checked(Request request,Receipt receipt){return new Delivery(request.transactionId(),receipt.matches(request)?Status.CONFIRMED:Status.CONFLICT,receipt,"核心全部收款明细和持久回执已核对");}
    private Optional<Receipt> receipt(Access a,String id)throws ReflectiveOperationException{
        Object value=optional(a.contract().getMethod("settlementReceipt",String.class).invoke(a.service(),id)).orElse(null);if(value==null)return Optional.empty();
        Class<?> type=a.type("module.treasury.NationSettlementService$SettlementReceipt");Object raw=type.getMethod("credits").invoke(value);
        if(!(raw instanceof Map<?,?> map)||map.size()<2||map.size()>16)throw new IllegalStateException("核心收款明细不符");
        Map<UUID,BigDecimal> credits=new HashMap<>();for(var entry:map.entrySet())if(credits.put(a.uuid(entry.getKey()),(BigDecimal)entry.getValue())!=null)throw new IllegalStateException("重复核心收款国家");
        return Optional.of(new Receipt((String)type.getMethod("transactionId").invoke(value),a.uuid(type.getMethod("payerId").invoke(value)),credits,"SETTLEMENT",(Instant)type.getMethod("appliedAt").invoke(value),(String)type.getMethod("description").invoke(value)));
    }
    private Access access()throws ReflectiveOperationException{
        if(!owner.getServer().isPrimaryThread())throw new IllegalStateException("多方结算必须在 Paper 主线程调用");Plugin core=owner.getServer().getPluginManager().getPlugin("StarCore");if(core==null||!core.isEnabled())throw new IllegalStateException("StarCore未启用");
        ClassLoader loader=core.getClass().getClassLoader();Class<?> apiType=Class.forName(BASE+"api.StarCoreApi",true,loader),contract=Class.forName(BASE+"module.treasury.NationSettlementService",true,loader),idType=Class.forName(BASE+"module.nation.model.NationId",true,loader);
        Object api=owner.getServer().getServicesManager().load(apiType);if(api==null)throw new IllegalStateException("StarCore API未注册");Object service=optional(apiType.getMethod("service",Class.class).invoke(api,contract)).orElseThrow(()->new IllegalStateException("NationSettlementService未注册"));return new Access(loader,api,apiType,service,contract,idType);
    }
    private record Access(ClassLoader loader,Object api,Class<?> apiType,Object service,Class<?> contract,Class<?> idType){
        Class<?> type(String name)throws ClassNotFoundException{return Class.forName(BASE+name,true,loader);}
        Object id(UUID id)throws ReflectiveOperationException{return idType.getMethod("of",UUID.class).invoke(null,id);}
        UUID uuid(Object id)throws ReflectiveOperationException{return (UUID)idType.getMethod("value").invoke(id);}
    }
    private static Optional<?> optional(Object value){if(!(value instanceof Optional<?> result))throw new IllegalStateException("Expected public Optional");return result;}
    private static Throwable cause(Throwable failure){while(failure.getCause()!=null)failure=failure.getCause();return failure;}
}

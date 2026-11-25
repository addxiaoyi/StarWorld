package dev.starworld.integration;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import static dev.starworld.integration.TreasuryTransferPort.Status;

/** Public NationSettlementService only; both original transfer and reverse receipt must match. */
public final class StarCoreTreasuryRefunds implements TreasuryRefundPort {
    private static final String BASE="dev.starcore.starcore.";
    private final JavaPlugin owner;private final TreasuryTransferPort transfers;
    public StarCoreTreasuryRefunds(JavaPlugin owner){this(owner,new StarCoreTreasuryTransfers(owner));}
    public StarCoreTreasuryRefunds(JavaPlugin owner,TreasuryTransferPort transfers){this.owner=Objects.requireNonNull(owner);this.transfers=Objects.requireNonNull(transfers);}
    @Override public Delivery refund(Request request){
        Objects.requireNonNull(request);
        try{
            Access access=access();Optional<Receipt> before=receipt(access,request.transactionId());if(before.isPresent())return checked(request,before.get());
            var original=request.original().credits().size()>1?transfers.receipt(request.original()):transfers.receipt(request.original().transactionId());
            if(original.isEmpty()||!original.get().matches(request.original()))return new Delivery(request.transactionId(),Status.CONFLICT,null,"原国家付款回执缺失或明细不符，保留原货物");
            Object previous=optional(access.contract().getMethod("refundForPayment",String.class).invoke(access.service(),request.original().transactionId())).orElse(null);
            if(previous!=null)return checked(request,readReceipt(access,previous));
            Object nations=access.required("module.nation.NationService");Class<?> nationType=access.type("module.nation.NationService"),idType=access.type("module.nation.model.NationId");
            Set<UUID> participants=new HashSet<>(request.original().credits().keySet());participants.add(request.original().payer());
            for(UUID id:participants){
                Object nation=idType.getMethod("of",UUID.class).invoke(null,id);
                if(optional(nationType.getMethod("nationById",idType).invoke(nations,nation)).isEmpty())return new Delivery(request.transactionId(),Status.RETRY,null,"原国家已解散，退款继续待核对");
            }
            Class<?> requestType=access.type("module.treasury.NationSettlementService$SettlementRefundRequest");
            Object payload=requestType.getConstructor(String.class,String.class).newInstance(request.transactionId(),request.original().transactionId());
            Object result=access.contract().getMethod("refundSettlement",requestType).invoke(access.service(),payload);
            if(!request.transactionId().equals(result.getClass().getMethod("transactionId").invoke(result)))return new Delivery(request.transactionId(),Status.CONFLICT,null,"核心退款返回了其他凭据");
            Optional<Receipt> after=receipt(access,request.transactionId());if(after.isPresent())return checked(request,after.get());
            return new Delivery(request.transactionId(),Status.RETRY,null,"核心退款 "+result.getClass().getMethod("status").invoke(result)+"；尚无匹配回执，保留货物待重试");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Delivery(request.transactionId(),Status.RETRY,null,"国库退款接口暂不可用："+cause(failure).getClass().getSimpleName());}
    }
    @Override public Optional<Receipt> receipt(String id){try{return receipt(access(),id);}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw new IllegalStateException("退款回执不可查询",cause(failure));}}
    @Override public String status(){try{access();return "StarCore 原子逆向付款与持久退款回执可用";}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return "国家退款等待核心 NationSettlementService";}}
    private Delivery checked(Request request,Receipt receipt){return new Delivery(request.transactionId(),receipt.matches(request)?Status.CONFIRMED:Status.CONFLICT,receipt,"核心持久退款回执已核对");}
    private Optional<Receipt> receipt(Access access,String id)throws ReflectiveOperationException{Object value=optional(access.contract().getMethod("settlementRefundReceipt",String.class).invoke(access.service(),id)).orElse(null);return value==null?Optional.empty():Optional.of(readReceipt(access,value));}
    private Receipt readReceipt(Access access,Object value)throws ReflectiveOperationException{
        Class<?> type=access.type("module.treasury.NationSettlementService$SettlementRefundReceipt"),nation=access.type("module.nation.model.NationId");
        Object raw=type.getMethod("credits").invoke(value);if(!(raw instanceof Map<?,?> map)||map.isEmpty()||map.size()>16)throw new IllegalStateException("核心退款明细无效");
        Map<UUID,BigDecimal> credits=new HashMap<>();for(var entry:map.entrySet())credits.put((UUID)nation.getMethod("value").invoke(entry.getKey()),(BigDecimal)entry.getValue());
        return new Receipt((String)type.getMethod("transactionId").invoke(value),(String)type.getMethod("originalTransactionId").invoke(value),(UUID)nation.getMethod("value").invoke(type.getMethod("payerId").invoke(value)),credits,(Instant)type.getMethod("appliedAt").invoke(value));
    }
    private Access access()throws ReflectiveOperationException{
        if(!owner.getServer().isPrimaryThread())throw new IllegalStateException("国库退款必须在Paper主线程调用");Plugin core=owner.getServer().getPluginManager().getPlugin("StarCore");if(core==null||!core.isEnabled())throw new IllegalStateException("StarCore未启用");
        ClassLoader loader=core.getClass().getClassLoader();Class<?> apiType=Class.forName(BASE+"api.StarCoreApi",true,loader),contract=Class.forName(BASE+"module.treasury.NationSettlementService",true,loader);
        Object api=owner.getServer().getServicesManager().load(apiType);if(api==null)throw new IllegalStateException("StarCore API未注册");
        Object service=optional(apiType.getMethod("service",Class.class).invoke(api,contract)).orElseThrow(()->new IllegalStateException("核心退款服务未启用"));return new Access(loader,api,apiType,service,contract);
    }
    private record Access(ClassLoader loader,Object api,Class<?> apiType,Object service,Class<?> contract){Class<?> type(String suffix)throws ClassNotFoundException{return Class.forName(BASE+suffix,true,loader);}Object required(String suffix)throws ReflectiveOperationException{return optional(apiType.getMethod("service",Class.class).invoke(api,type(suffix))).orElseThrow(()->new IllegalStateException("Missing "+suffix));}}
    private static Optional<?> optional(Object value){if(!(value instanceof Optional<?> result))throw new IllegalStateException("Expected Optional");return result;}
    private static Throwable cause(Throwable failure){while(failure.getCause()!=null)failure=failure.getCause();return failure;}
}

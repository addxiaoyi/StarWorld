package dev.starworld.integration;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Selects one atomic public payment contract; never composes separate debit and credit operations. */
public final class StarCoreTreasuryTransfers implements TreasuryTransferPort {
    private static final String BASE="dev.starcore.starcore.";
    private final JavaPlugin owner;
    public StarCoreTreasuryTransfers(JavaPlugin owner){this.owner=Objects.requireNonNull(owner);}
    @Override public Delivery transfer(Request request){
        Objects.requireNonNull(request);
        if(request.credits().size()>1)return new StarCoreTreasurySettlements(owner).transfer(request);
        try {
            Access a=access();Optional<Receipt> before=receipt(a,request.transactionId());if(before.isPresent())return checked(request,before.get());
            Object nations=a.required("module.nation.NationService");Class<?> nationContract=a.type("module.nation.NationService");
            for(UUID id:List.of(request.payer(),request.receiver()))if(optional(nationContract.getMethod("nationById",a.idType()).invoke(nations,a.id(id))).isEmpty())return new Delivery(request.transactionId(),Status.REJECTED,null,"国家已解散，拒绝新付款");
            Class<?> req=a.type("module.treasury.NationTransferService$TransferRequest"),category=a.type("module.treasury.NationTransferService$TransferCategory");
            Object other=Arrays.stream(category.getEnumConstants()).filter(e->((Enum<?>)e).name().equals("OTHER")).findFirst().orElseThrow();
            Object payload=req.getConstructor(String.class,a.idType(),a.idType(),BigDecimal.class,category,String.class).newInstance(request.transactionId(),a.id(request.payer()),a.id(request.receiver()),request.amount(),other,request.description());
            Object result=a.contract().getMethod("transfer",req).invoke(a.service(),payload);
            if(!request.transactionId().equals(result.getClass().getMethod("transactionId").invoke(result)))return new Delivery(request.transactionId(),Status.CONFLICT,null,"核心转账结果ID不符");
            Optional<Receipt> after=receipt(a,request.transactionId());if(after.isPresent())return checked(request,after.get());
            String state=String.valueOf(result.getClass().getMethod("status").invoke(result));
            return new Delivery(request.transactionId(),Set.of("REJECTED","INSUFFICIENT_FUNDS").contains(state)?Status.REJECTED:Status.RETRY,null,"核心结果 "+state+"；尚无持久回执");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Delivery(request.transactionId(),Status.RETRY,null,"国库接口暂不可用："+cause(failure).getClass().getSimpleName());}
    }
    @Override public Optional<Receipt> receipt(String transactionId){if(transactionId==null||transactionId.isBlank())throw new IllegalArgumentException("Empty transaction id");try{return receipt(access(),transactionId);}catch(ReflectiveOperationException|RuntimeException|LinkageError e){throw new IllegalStateException("国库回执不可查询，不能证明未付款",cause(e));}}
    @Override public Optional<Receipt> receipt(Request request){return request.credits().size()>1?new StarCoreTreasurySettlements(owner).receipt(request.transactionId()):receipt(request.transactionId());}
    @Override public String status(){try{access();return "StarCore 原子幂等国库转账与持久回执可用";}catch(ReflectiveOperationException|RuntimeException|LinkageError e){return "国家金币市场停用/待恢复："+cause(e).getClass().getSimpleName();}}
    private Delivery checked(Request r,Receipt receipt){return new Delivery(r.transactionId(),receipt.matches(r)?Status.CONFIRMED:Status.CONFLICT,receipt,"核心持久国库回执已核实");}
    private Optional<Receipt> receipt(Access a,String id)throws ReflectiveOperationException{
        Object value=optional(a.contract().getMethod("transferReceipt",String.class).invoke(a.service(),id)).orElse(null);if(value==null)return Optional.empty();
        Class<?> type=a.type("module.treasury.NationTransferService$TransferReceipt");
        return Optional.of(new Receipt((String)type.getMethod("transactionId").invoke(value),a.uuid(type.getMethod("payerId").invoke(value)),a.uuid(type.getMethod("receiverId").invoke(value)),(BigDecimal)type.getMethod("amount").invoke(value),String.valueOf(type.getMethod("category").invoke(value)),(Instant)type.getMethod("appliedAt").invoke(value)));
    }
    private Access access()throws ReflectiveOperationException{
        if(!owner.getServer().isPrimaryThread())throw new IllegalStateException("国库API必须在Paper主线程调用");Plugin plugin=owner.getServer().getPluginManager().getPlugin("StarCore");if(plugin==null||!plugin.isEnabled())throw new IllegalStateException("StarCore未启用");
        ClassLoader loader=plugin.getClass().getClassLoader();Class<?> apiType=Class.forName(BASE+"api.StarCoreApi",true,loader);Object api=owner.getServer().getServicesManager().load(apiType);if(api==null)throw new IllegalStateException("StarCore API未注册");
        Class<?> id=Class.forName(BASE+"module.nation.model.NationId",true,loader),contract=Class.forName(BASE+"module.treasury.NationTransferService",true,loader);Object service=optional(apiType.getMethod("service",Class.class).invoke(api,contract)).orElseThrow(()->new IllegalStateException("NationTransferService未启用"));return new Access(loader,api,apiType,id,service,contract);
    }
    private record Access(ClassLoader loader,Object api,Class<?> apiType,Class<?> idType,Object service,Class<?> contract){Class<?> type(String suffix)throws ClassNotFoundException{return Class.forName(BASE+suffix,true,loader);}Object id(UUID id)throws ReflectiveOperationException{return idType.getMethod("of",UUID.class).invoke(null,id);}UUID uuid(Object id)throws ReflectiveOperationException{return (UUID)idType.getMethod("value").invoke(id);}Object required(String suffix)throws ReflectiveOperationException{return optional(apiType.getMethod("service",Class.class).invoke(api,type(suffix))).orElseThrow(()->new IllegalStateException("Missing service "+suffix));}}
    private static Optional<?> optional(Object value){if(!(value instanceof Optional<?> o))throw new IllegalStateException("Expected public Optional result");return o;}
    private static Throwable cause(Throwable e){while(e.getCause()!=null)e=e.getCause();return e;}
}

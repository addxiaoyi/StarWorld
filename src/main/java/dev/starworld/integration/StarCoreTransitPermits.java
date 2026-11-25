package dev.starworld.integration;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Public TransitPermitService only: no reflective private state and no world or treasury mutation. */
public final class StarCoreTransitPermits implements TransitPermitPort {
    private static final String BASE="dev.starcore.starcore.",CONTRACT="module.diplomacy.TransitPermitService";
    private final JavaPlugin owner;
    public StarCoreTransitPermits(JavaPlugin owner){this.owner=Objects.requireNonNull(owner);}
    @Override public Optional<Permit> permit(String route,UUID nation){try{Access a=access();return optional(a.contract().getMethod("permit",String.class,a.idType()).invoke(a.service(),route,a.id(nation))).map(v->readPermit(a,v));}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw unavailable(failure);}}
    @Override public boolean allowed(Permit expected){
        Objects.requireNonNull(expected);
        try{Access a=access();Object result=a.contract().getMethod("evaluate",a.type(CONTRACT+"$Terms"),long.class).invoke(a.service(),terms(a,expected.terms()),expected.revision());Class<?> type=a.type(CONTRACT+"$Decision");
            Optional<?> permit=optional(type.getMethod("permit").invoke(result));return Boolean.TRUE.equals(type.getMethod("allowed").invoke(result))&&permit.isPresent()&&readPermit(a,permit.get()).equals(expected)&&!expected.revoked();
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw unavailable(failure);}
    }
    @Override public Receipt approve(UUID request,UUID actor,long expectedRevision,Terms terms){
        try{Access a=access();Class<?> payloadType=a.type(CONTRACT+"$Approval");Object payload=payloadType.getConstructor(UUID.class,UUID.class,long.class,a.type(CONTRACT+"$Terms")).newInstance(request,actor,expectedRevision,terms(a,terms));
            Object returned=a.contract().getMethod("approve",payloadType).invoke(a.service(),payload);Receipt receipt=verified(a,request,returned);
            if(receipt.action()!=Action.APPROVE||receipt.expectedRevision()!=expectedRevision||!receipt.permit().actor().equals(actor)||!receipt.permit().terms().equals(terms))throw new IllegalStateException("核心许可回执与确认明细不符");return receipt;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw unavailable(failure);}
    }
    @Override public Receipt revoke(UUID request,UUID actor,long expectedRevision,String route,UUID nation){
        try{Access a=access();Class<?> payloadType=a.type(CONTRACT+"$Revocation");Object payload=payloadType.getConstructor(UUID.class,UUID.class,long.class,String.class,a.idType()).newInstance(request,actor,expectedRevision,route,a.id(nation));
            Object returned=a.contract().getMethod("revoke",payloadType).invoke(a.service(),payload);Receipt receipt=verified(a,request,returned);
            if(receipt.action()!=Action.REVOKE||receipt.expectedRevision()!=expectedRevision||!receipt.permit().actor().equals(actor)||!receipt.permit().terms().routeKey().equals(route)||!receipt.permit().terms().grantingNation().equals(nation))throw new IllegalStateException("核心撤销回执与请求不符");return receipt;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw unavailable(failure);}
    }
    @Override public Optional<Receipt> receipt(UUID request){try{Access a=access();return receipt(a,request);}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw unavailable(failure);}}
    @Override public String status(){try{access();return "核心带版本过境许可及撤销回执可用";}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return "第三国过境等待核心 TransitPermitService";}}
    private Receipt verified(Access a,UUID id,Object returned)throws ReflectiveOperationException{
        Receipt result=readReceipt(a,returned),saved=receipt(a,id).orElseThrow(()->new IllegalStateException("核心过境操作缺少持久回执"));
        if(!id.equals(result.requestId())||!result.equals(saved))throw new IllegalStateException("核心过境操作回执不符");return saved;
    }
    private Optional<Receipt> receipt(Access a,UUID id)throws ReflectiveOperationException{return optional(a.contract().getMethod("receipt",UUID.class).invoke(a.service(),id)).map(v->readReceipt(a,v));}
    private Object terms(Access a,Terms terms)throws ReflectiveOperationException{
        Class<?> parcelType=a.type(CONTRACT+"$Parcel");Set<Object> parcels=new HashSet<>();for(Parcel p:terms.parcels())parcels.add(parcelType.getConstructor(UUID.class,String.class,UUID.class,int.class,int.class).newInstance(p.worldId(),p.worldName(),p.cityId(),p.chunkX(),p.chunkZ()));
        return a.type(CONTRACT+"$Terms").getConstructor(String.class,String.class,a.idType(),a.idType(),a.idType(),Set.class,BigDecimal.class,long.class).newInstance(terms.routeKey(),terms.fingerprint(),a.id(terms.grantingNation()),a.id(terms.originNation()),a.id(terms.targetNation()),parcels,terms.tollPerUnit(),terms.expiresAt());
    }
    private Permit readPermit(Access a,Object value){try{
        Class<?> permit=a.type(CONTRACT+"$Permit"),t=a.type(CONTRACT+"$Terms"),p=a.type(CONTRACT+"$Parcel");Object terms=permit.getMethod("terms").invoke(value),raw=t.getMethod("parcels").invoke(terms);
        if(!(raw instanceof Set<?> set)||set.isEmpty()||set.size()>MAX_PARCELS)throw new IllegalStateException("核心过境区块数量无效");Set<Parcel> parcels=new HashSet<>();for(Object item:set)if(!parcels.add(new Parcel((UUID)p.getMethod("worldId").invoke(item),(String)p.getMethod("worldName").invoke(item),(UUID)p.getMethod("cityId").invoke(item),(int)p.getMethod("chunkX").invoke(item),(int)p.getMethod("chunkZ").invoke(item))))throw new IllegalStateException("核心过境区块重复");
        Terms snapshot=new Terms((String)t.getMethod("routeKey").invoke(terms),(String)t.getMethod("fingerprint").invoke(terms),a.uuid(t.getMethod("grantingNation").invoke(terms)),a.uuid(t.getMethod("originNation").invoke(terms)),a.uuid(t.getMethod("targetNation").invoke(terms)),parcels,(BigDecimal)t.getMethod("tollPerUnit").invoke(terms),(long)t.getMethod("expiresAt").invoke(terms));
        return new Permit(snapshot,(long)permit.getMethod("revision").invoke(value),(boolean)permit.getMethod("revoked").invoke(value),(UUID)permit.getMethod("actor").invoke(value),(long)permit.getMethod("updatedAt").invoke(value));
    }catch(ReflectiveOperationException failure){throw unavailable(failure);}}
    private Receipt readReceipt(Access a,Object value){try{Class<?> t=a.type(CONTRACT+"$Receipt");return new Receipt((UUID)t.getMethod("requestId").invoke(value),Action.valueOf(String.valueOf(t.getMethod("action").invoke(value))),(long)t.getMethod("expectedRevision").invoke(value),readPermit(a,t.getMethod("permit").invoke(value)));}catch(ReflectiveOperationException failure){throw unavailable(failure);}}
    private Access access()throws ReflectiveOperationException{
        if(!owner.getServer().isPrimaryThread())throw new IllegalStateException("过境权限必须在 Paper 主线程读取");Plugin core=owner.getServer().getPluginManager().getPlugin("StarCore");if(core==null||!core.isEnabled())throw new IllegalStateException("StarCore未启用");
        ClassLoader loader=core.getClass().getClassLoader();Class<?> apiType=Class.forName(BASE+"api.StarCoreApi",true,loader),contract=Class.forName(BASE+CONTRACT,true,loader),idType=Class.forName(BASE+"module.nation.model.NationId",true,loader);Object api=owner.getServer().getServicesManager().load(apiType);if(api==null)throw new IllegalStateException("StarCore API未注册");Object service=optional(apiType.getMethod("service",Class.class).invoke(api,contract)).orElseThrow(()->new IllegalStateException("TransitPermitService未注册"));return new Access(loader,service,contract,idType);
    }
    private record Access(ClassLoader loader,Object service,Class<?> contract,Class<?> idType){Class<?> type(String name)throws ClassNotFoundException{return Class.forName(BASE+name,true,loader);}Object id(UUID id)throws ReflectiveOperationException{return idType.getMethod("of",UUID.class).invoke(null,id);}UUID uuid(Object id)throws ReflectiveOperationException{return (UUID)idType.getMethod("value").invoke(id);}}
    private static Optional<?> optional(Object value){if(!(value instanceof Optional<?> result))throw new IllegalStateException("Expected public Optional");return result;}
    private static IllegalArgumentException unavailable(Throwable failure){while(failure.getCause()!=null)failure=failure.getCause();return new IllegalArgumentException("核心过境权限暂不可确认："+failure.getMessage(),failure);}
}

package dev.starworld.integration;

import dev.starworld.integration.CoreExtensionPort.*;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Public StarCore API only. No mutable core model or service escapes a main-thread call. */
public final class StarCoreExtensions implements CoreExtensionPort {
    private static final String BASE="dev.starcore.starcore.";
    private static final Set<String> TECH_KEYS=Set.of("production_speed","resource_yield","crop_growth","trade_bonus","military_strength","transport_speed");
    private final JavaPlugin owner;
    public StarCoreExtensions(JavaPlugin owner){this.owner=Objects.requireNonNull(owner);}
    @Override public Optional<NationSnapshot> snapshot(UUID nationId,Set<UUID> diplomacyPeers) {
        Objects.requireNonNull(nationId);Objects.requireNonNull(diplomacyPeers);
        if(diplomacyPeers.size()>100)throw new IllegalArgumentException("Too many diplomacy peers");
        try {
            Access access=access();Object id=access.id(nationId);Object nations=access.service("module.nation.NationService");
            Object nation=optional(call(nations,"nationById",new Class<?>[]{access.idType},id));if(nation==null)return Optional.empty();
            if(!nationId.equals(uuid(call(nation,"id"))))return Optional.empty();
            String name=(String)call(nation,"name");Map<String,String> capabilities=new TreeMap<>();
            Map<String,Long> stock=optionalPart(capabilities,"resources",Map.of(),()->longs(call(access.required("module.resource.ResourceService"),"stockpile",new Class<?>[]{access.idType},id)));
            Set<String> technologies=optionalPart(capabilities,"technology",Set.of(),()->strings(call(access.required("module.technology.TechnologyService"),"unlockedTechnologies",new Class<?>[]{access.idType},id)));
            Map<String,Double> modifiers=optionalPart(capabilities,"technology-effects",Map.of(),()->{
                Object service=access.required("module.technology.TechnologyService");Map<String,Double> values=new TreeMap<>();
                for(String key:TECH_KEYS)values.put(key,finite(call(service,"getTotalModifier",new Class<?>[]{access.idType,String.class},id,key)));
                return Map.copyOf(values);
            });
            String policy=optionalPart(capabilities,"policy", "",()->Objects.toString(optional(call(access.required("module.policy.PolicyService"),"activePolicy",new Class<?>[]{access.idType},id)),""));
            Map<String,Double> policyModifiers=optionalPart(capabilities,"policy-effects",Map.of(),()->{
                Object service=access.required("module.policy.PolicyService");Class<?> scope=access.type("module.policy.model.PolicyEffectScope");Map<String,Double> result=new TreeMap<>();
                for(String key:List.of("production_bonus","trade_bonus","military_bonus")){
                    String domain=key.equals("production_bonus")?"PRODUCTION":key.equals("trade_bonus")?"TRADE":"MILITARY";
                    double value=0;for(String group:List.of("GLOBAL",domain))value+=finite(call(service,"activePolicyModifier",new Class<?>[]{access.idType,String.class,scope},id,key,enumValue(scope,group)));
                    result.put(key,value);
                }return Map.copyOf(result);
            });
            double prosperity=optionalPart(capabilities,"prosperity",0.0,()->finite(call(access.required("module.prosperity.ProsperityService"),"getProsperityValue",new Class<?>[]{access.idType},id)));
            Weather weather=optionalPart(capabilities,"weather",new Weather("unknown",Map.of()),()->{
                Object service=access.required("module.weather.WeatherControlService");Object type=call(service,"getNationWeather",new Class<?>[]{access.idType},id);
                return new Weather(String.valueOf(call(type,"getId")),doubles(call(service,"getResourceModifiers",new Class<?>[]{access.type("module.weather.model.WeatherType")},type)));
            });
            Epoch epoch=optionalPart(capabilities,"epoch",null,()->{
                Object value=call(access.required("foundation.epoch.EpochService"),"snapshot");
                return new Epoch((boolean)call(value,"enabled"),((Number)call(value,"epochNumber")).longValue(),(Instant)call(value,"epochStart"),(Instant)call(value,"nextEpochStart"));
            });
            Set<UUID> opponents=optionalPart(capabilities,"war",Set.of(),()->{
                Collection<?> wars=collection(call(access.required("module.war.WarService"),"activeWarsOf",new Class<?>[]{access.idType},id));Set<UUID> result=new HashSet<>();
                for(Object war:wars){if(Boolean.TRUE.equals(call(war,"ended")))continue;UUID left=uuid(call(war,"left")),right=uuid(call(war,"right"));if(left.equals(nationId))result.add(right);else if(right.equals(nationId))result.add(left);}
                return Set.copyOf(result);
            });
            Map<UUID,String> relations=optionalPart(capabilities,"diplomacy",Map.of(),()->{
                Object service=access.required("module.diplomacy.DiplomacyRelationService");Map<UUID,String> result=new HashMap<>();
                for(UUID peer:diplomacyPeers)if(!nationId.equals(peer))result.put(peer,String.valueOf(call(service,"relationBetween",new Class<?>[]{access.idType,access.idType},id,access.id(peer))));
                return Map.copyOf(result);
            });
            List<ProcessingRecipe> recipes=optionalPart(capabilities,"processing",List.of(),()->{
                Collection<?> values=collection(call(access.required("module.resource.ProcessingService"),"getAllRecipes"));if(values.size()>1000)throw new IllegalStateException("Core recipe count exceeds snapshot bound");
                List<ProcessingRecipe> result=new ArrayList<>();for(Object recipe:values)result.add(new ProcessingRecipe((String)call(recipe,"recipeId"),longs(call(recipe,"inputs")),longs(call(recipe,"outputs")),((Number)call(recipe,"processingTime")).longValue()));
                return List.copyOf(result);
            });
            int factories=optionalPart(capabilities,"factories",0,()->collection(call(access.required("module.resource.ProcessingService"),"getFactories",new Class<?>[]{access.idType},id)).size());
            optionalPart(capabilities,"resource-transactions",false,()->{access.required("module.resource.NationResourceTransactionService");return true;});
            capabilities.put("prosperity-submit","公开modifyProsperity/addChunkContribution无幂等回执；不自动重放跨库贡献");
            capabilities.put("folia","核心可变模型无跨区域快照契约；仅Paper主线程捕获");
            return Optional.of(new NationSnapshot(nationId,name,Instant.now(),weather.type,weather.multipliers,epoch,technologies,modifiers,policy,policyModifiers,stock,prosperity,opponents,relations,recipes,factories,capabilities));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return Optional.empty();}
    }
    @Override public Delivery deliver(ResourceRequest request) {
        Objects.requireNonNull(request);
        try {
            Access access=access();Object service=access.required("module.resource.NationResourceTransactionService");
            Optional<ResourceReceipt> existing=receipt(access,service,request.transactionId());
            if(existing.isPresent())return checked(request,existing.get(),"Existing durable core receipt");
            Object nations=access.required("module.nation.NationService");if(optional(call(nations,"nationById",new Class<?>[]{access.idType},access.id(request.nationId())))==null)return new Delivery(request.transactionId(),DeliveryStatus.REJECTED,null,"Target core nation no longer exists");
            Set<String> types=strings(call(access.required("module.resource.ResourceService"),"availableResourceTypes"));
            if(!types.containsAll(request.resources().keySet()))return new Delivery(request.transactionId(),DeliveryStatus.REJECTED,null,"Unknown core resource type");
            Class<?> requestType=access.type("module.resource.NationResourceTransactionService$TransactionRequest"),category=access.type("module.resource.NationResourceTransactionService$Category");
            Object coreRequest=requestType.getConstructor(String.class,access.idType,Map.class,category,String.class).newInstance(request.transactionId(),access.id(request.nationId()),request.resources(),enumValue(category,"OTHER"),request.description());
            Object result=call(service,"grant",new Class<?>[]{requestType},coreRequest);
            if(!request.transactionId().equals(call(result,"transactionId")))return new Delivery(request.transactionId(),DeliveryStatus.CONFLICT,null,"Core result transaction id differs");
            Optional<ResourceReceipt> after=receipt(access,service,request.transactionId());
            if(after.isPresent())return checked(request,after.get(),"Core grant receipt verified");
            String status=String.valueOf(call(result,"status"));
            return new Delivery(request.transactionId(),Set.of("REJECTED","INSUFFICIENT_RESOURCES").contains(status)?DeliveryStatus.REJECTED:DeliveryStatus.RETRY,null,"Core result "+status+" without durable receipt");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){return new Delivery(request.transactionId(),DeliveryStatus.RETRY,null,failure.getClass().getSimpleName()+": "+failure.getMessage());}
    }
    @Override public Optional<ResourceReceipt> receipt(String transactionId) {
        if(transactionId==null||transactionId.isBlank())throw new IllegalArgumentException("Empty transaction id");
        try{Access access=access();return receipt(access,access.required("module.resource.NationResourceTransactionService"),transactionId);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){throw new IllegalStateException("Core receipt unavailable; absence is unproven",failure);}
    }
    private Delivery checked(ResourceRequest request,ResourceReceipt receipt,String detail){return new Delivery(request.transactionId(),receipt.matches(request)?DeliveryStatus.CONFIRMED:DeliveryStatus.CONFLICT,receipt,detail);}
    private Optional<ResourceReceipt> receipt(Access access,Object service,String id)throws ReflectiveOperationException {
        Object value=optional(call(service,"transactionReceipt",new Class<?>[]{String.class},id));if(value==null)return Optional.empty();
        return Optional.of(new ResourceReceipt((String)call(value,"transactionId"),uuid(call(value,"nationId")),longs(call(value,"resources")),String.valueOf(call(value,"operation")),String.valueOf(call(value,"category")),(Instant)call(value,"appliedAt")));
    }
    private Access access()throws ReflectiveOperationException {
        if(!owner.getServer().isPrimaryThread())throw new IllegalStateException("Core extensions must run on the Paper main thread");
        Plugin core=owner.getServer().getPluginManager().getPlugin("StarCore");if(core==null||!core.isEnabled())throw new IllegalStateException("StarCore unavailable");
        ClassLoader loader=core.getClass().getClassLoader();Class<?> type=Class.forName(BASE+"api.StarCoreApi",true,loader);Object api=owner.getServer().getServicesManager().load(type);if(api==null)throw new IllegalStateException("Core API not registered");
        return new Access(api,type,loader,Class.forName(BASE+"module.nation.model.NationId",true,loader));
    }
    private record Access(Object api,Class<?> apiType,ClassLoader loader,Class<?> idType) {
        Class<?> type(String name)throws ClassNotFoundException{return Class.forName(BASE+name,true,loader);}
        Object id(UUID id)throws ReflectiveOperationException{return idType.getMethod("of",UUID.class).invoke(null,id);}
        Object service(String type)throws ReflectiveOperationException{return optional(apiType.getMethod("service",Class.class).invoke(api,type(type)));}
        Object required(String type)throws ReflectiveOperationException{return Objects.requireNonNull(service(type),"Missing core service "+type);}
    }
    private record Weather(String type,Map<String,Double> multipliers){}
    @FunctionalInterface private interface Read<T>{T get()throws ReflectiveOperationException;}
    private static <T>T optionalPart(Map<String,String> status,String key,T fallback,Read<T> read){try{T value=read.get();status.put(key,"available");return value;}catch(ReflectiveOperationException|RuntimeException|LinkageError e){status.put(key,"unavailable: "+e.getClass().getSimpleName());return fallback;}}
    private static Object call(Object target,String method)throws ReflectiveOperationException{return call(target,method,new Class<?>[0]);}
    private static Object call(Object target,String method,Class<?>[] types,Object... args)throws ReflectiveOperationException{return target.getClass().getMethod(method,types).invoke(target,args);}
    private static Object optional(Object value){if(!(value instanceof Optional<?> o))throw new IllegalStateException("Expected Optional");return o.orElse(null);}
    private static UUID uuid(Object id)throws ReflectiveOperationException{return (UUID)call(id,"value");}
    private static Object enumValue(Class<?> type,String name){return Arrays.stream(type.getEnumConstants()).filter(e->((Enum<?>)e).name().equals(name)).findFirst().orElseThrow();}
    private static Collection<?> collection(Object value){if(!(value instanceof Collection<?> c))throw new IllegalStateException("Expected collection");return c;}
    private static Set<String> strings(Object value){Set<String> copy=new HashSet<>();for(Object item:collection(value))copy.add((String)item);return Set.copyOf(copy);}
    private static double finite(Object value){if(!(value instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalStateException("Invalid core number");return n.doubleValue();}
    private static Map<String,Double> doubles(Object value){if(!(value instanceof Map<?,?> map))throw new IllegalStateException("Expected map");Map<String,Double> copy=new TreeMap<>();map.forEach((k,v)->copy.put((String)k,finite(v)));return Map.copyOf(copy);}
    private static Map<String,Long> longs(Object value){if(!(value instanceof Map<?,?> map))throw new IllegalStateException("Expected map");Map<String,Long> copy=new TreeMap<>();map.forEach((k,v)->{if(!(v instanceof Number n)||n.longValue()<0||n.doubleValue()!=n.longValue())throw new IllegalStateException("Invalid core amount");copy.put((String)k,n.longValue());});return Map.copyOf(copy);}
}

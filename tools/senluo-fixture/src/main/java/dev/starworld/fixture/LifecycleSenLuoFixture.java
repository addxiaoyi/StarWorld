package dev.starworld.fixture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only, console-dispatched lifecycle observer shared by the actual 1.0 and 1.1 runtimes.
 * Reflection resolves public APIs from the installed product; no private fields, SQL, or drains.
 */
final class LifecycleSenLuoFixture {
    private static final List<String> ITEMS=List.of("minecraft:bread","kaleidoscopecookery:baozi","kaleidoscopecookery:oil");
    private final JavaPlugin owner;
    private final Object runtime,items;
    private final Gson json=new GsonBuilder().serializeNulls().create();

    LifecycleSenLuoFixture(JavaPlugin owner,Object runtime,Object items) {
        this.owner=Objects.requireNonNull(owner);
        this.runtime=Objects.requireNonNull(runtime);
        this.items=Objects.requireNonNull(items);
    }

    void execute(String[] args,Player player)throws Exception {
        switch(args[0]) {
            case "lifecycle-seed" -> {
                if(args.length!=3)throw new IllegalArgumentException("lifecycle-seed <player> <cookery|community>");
                seed(player,args[2]);
            }
            case "lifecycle-state" -> state(player,Arrays.stream(args).skip(2).map(UUID::fromString).toList());
            default -> throw new IllegalArgumentException("Unknown lifecycle fixture action");
        }
    }

    private void seed(Player player,String bundle)throws Exception {
        Map<String,Integer> goods=switch(bundle) {
            case "cookery" -> Map.of("kaleidoscopecookery:baozi",4,"kaleidoscopecookery:oil",1);
            case "community" -> Map.of("minecraft:bread",8);
            default -> throw new IllegalArgumentException("Only cookery or community test seeds are supported");
        };
        Map<String,Integer> before=inventory(player);
        for(var entry:goods.entrySet()) {
            ItemStack item=(ItemStack)items.getClass().getMethod("create",String.class,int.class).invoke(items,entry.getKey(),entry.getValue());
            if(item==null||!player.getInventory().addItem(item).isEmpty())throw new IllegalStateException("Actual lifecycle inventory seed failed");
        }
        player.saveData();
        report("lifecycle-seed",Map.of("player",player.getName(),"bundle",bundle,"fixtureSeededPlayerInventory",goods,
                "inventoryBefore",before,"inventoryAfter",inventory(player),"productionEvidence",false));
    }

    private Map<String,Integer> inventory(Player player)throws Exception {
        Map<String,Integer> result=new TreeMap<>();
        for(String id:ITEMS) {
            int total=0;
            for(ItemStack stack:player.getInventory().getStorageContents())
                if((boolean)items.getClass().getMethod("matches",ItemStack.class,String.class).invoke(items,stack,id))total+=stack.getAmount();
            result.put(id,total);
        }
        return result;
    }

    private void state(Player player,List<UUID> actionIds)throws Exception {
        if(actionIds.size()>8||new HashSet<>(actionIds).size()!=actionIds.size())throw new IllegalArgumentException("At most eight different action IDs");
        Object city=((Optional<?>)runtime.getClass().getMethod("playerCity",Player.class).invoke(runtime,player)).orElseThrow();
        UUID cityId=(UUID)call(city,"cityId"),nationId=(UUID)call(city,"nationId"),actor=player.getUniqueId();
        long period=((Number)call(runtime,"currentPeriod")).longValue();
        Map<String,Integer> inventory=inventory(player);
        Class<?> contract=Class.forName("dev.starworld.api.StarWorldApi",true,Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("StarWorld")).getClass().getClassLoader());
        Object api=Objects.requireNonNull(Bukkit.getServicesManager().load(contract));
        String version=(String)call(api,"version");
        CompletableFuture<Object> town=future(api,"city",new Class<?>[]{UUID.class},cityId);
        CompletableFuture<Object> life=future(api,"life",new Class<?>[]{UUID.class},cityId);
        CompletableFuture<Object> profile=future(api,"development",new Class<?>[]{UUID.class},cityId);
        CompletableFuture<Object> profession=async(()->{
            Object expansion=((Optional<?>)call(runtime,"expansion")).orElseThrow();
            return call(call(expansion,"life"),"profession",new Class<?>[]{UUID.class},actor);
        });
        boolean available;
        try {api.getClass().getMethod("lifeEvolutionCredit",UUID.class);available=true;}
        catch(NoSuchMethodException absent) {available=false;}
        final boolean creditApiAvailable=available;
        if(!available&&!actionIds.isEmpty())throw new IllegalStateException("Installed 1.0 has no credit diagnostics; use the stopped read-only probe");
        Map<UUID,CompletableFuture<Object>> credits=new LinkedHashMap<>();
        for(UUID id:actionIds)credits.put(id,future(api,"lifeEvolutionCredit",new Class<?>[]{UUID.class},id));
        List<CompletableFuture<?>> reads=new ArrayList<>(List.of(town,life,profile,profession));
        reads.addAll(credits.values());
        CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).whenComplete((nothing,error)->Bukkit.getScheduler().runTask(owner,()->{
            try {
                if(error!=null)throw new IllegalStateException("Lifecycle immutable API read failed",error);
                Object cityView=((Optional<?>)town.join()).orElseThrow();
                Map<String,Object> progress=new LinkedHashMap<>();
                for(var entry:credits.entrySet())progress.put(entry.getKey().toString(),((Optional<?>)entry.getValue().join()).orElse(null));
                Map<String,Object> result=new LinkedHashMap<>();
                result.put("player",player.getName());result.put("actorId",actor);result.put("cityId",cityId);result.put("nationId",nationId);
                result.put("period",period);result.put("observedPeriod",((Number)call(runtime,"currentPeriod")).longValue());
                result.put("version",version);result.put("city",cityView);result.put("stock",call(cityView,"stock"));
                result.put("life",((Optional<?>)life.join()).orElseThrow());result.put("profile",((Optional<?>)profile.join()).orElseThrow());
                result.put("profession",profession.join());result.put("inventory",inventory);result.put("credits",progress);
                result.put("creditApiAvailable",creditApiAvailable);result.put("gameplayWrites",false);
                report("lifecycle-state",result);
            }catch(Throwable failure){failed(failure);}
        }));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> async(Callable<Object> read)throws Exception {
        Class<?> work=Class.forName("dev.starworld.runtime.WorldRuntime$Work",true,runtime.getClass().getClassLoader());
        Object proxy=Proxy.newProxyInstance(work.getClassLoader(),new Class<?>[]{work},(ignored,method,args)->{
            if(method.getName().equals("run"))return read.call();
            if(method.getName().equals("toString"))return "Read-only lifecycle acceptance snapshot";
            throw new UnsupportedOperationException(method.getName());
        });
        return (CompletableFuture<Object>)runtime.getClass().getMethod("async",work).invoke(runtime,proxy);
    }
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> future(Object target,String method,Class<?>[] types,Object...args)throws Exception {
        return (CompletableFuture<Object>)call(target,method,types,args);
    }
    private static Object call(Object target,String method)throws Exception{return call(target,method,new Class<?>[0]);}
    private static Object call(Object target,String method,Class<?>[] types,Object...args)throws Exception {
        try{return target.getClass().getMethod(method,types).invoke(target,args);}
        catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof Exception exception)throw exception;
            if(cause instanceof Error error)throw error;
            throw failure;
        }
    }
    private void report(String action,Map<String,?> values) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("action",action);result.putAll(values);
        owner.getLogger().info("SW_SENLUO_JSON "+json.toJson(result));
    }
    private void failed(Throwable failure){owner.getLogger().log(java.util.logging.Level.SEVERE,"SW_SENLUO_FAILED",failure);}
}


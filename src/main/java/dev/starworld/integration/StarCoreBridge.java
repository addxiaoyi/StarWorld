package dev.starworld.integration;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.domain.WorldState.Site;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Read-only adapter for the audited StarCore public API. No StarCore classes are bundled.
 * Calls belong on the Paper main thread; StarCore's mutable models are not Folia safe.
 */
public final class StarCoreBridge implements CoreBridge {
    private static final String BASE = "dev.starcore.starcore.";
    private final JavaPlugin owner;
    private volatile String problem = "StarCore 尚未连接";

    public StarCoreBridge(JavaPlugin owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    @Override
    public Optional<CoreCity> playerCity(UUID player) {
        if (player == null) return Optional.empty();
        try {
            Access access = access();
            Object city = optional(call(access.cities(), "getPlayerCity", new Class<?>[]{UUID.class}, player));
            if (city == null || !Boolean.TRUE.equals(call(city, "isResident", new Class<?>[]{UUID.class}, player)))
                return Optional.empty();
            Object nation = nation(access, city);
            if (nation == null || !belongsToCityNation(access, city, player))
                return Optional.empty();
            return Optional.of(snapshot(access, city, nation));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
            return Optional.empty();
        }
    }

    @Override
    public Optional<CoreCity> city(UUID cityId) {
        if (cityId == null) return Optional.empty();
        try {
            Access access = access();
            Object city = city(access, cityId);
            if (city == null) return Optional.empty();
            Object nation = nation(access, city);
            return nation == null ? Optional.empty() : Optional.of(snapshot(access, city, nation));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
            return Optional.empty();
        }
    }

    @Override
    public boolean canManage(UUID player, UUID cityId) {
        if (player == null || cityId == null) return false;
        try {
            Access access = access();
            Object city = city(access, cityId);
            if (city == null) return false;
            Object nation = nation(access, city);
            if (nation == null || !belongsToCityNation(access, city, player))
                return false;
            return Boolean.TRUE.equals(call(city, "canManageCity", new Class<?>[]{UUID.class}, player))
                    || Boolean.TRUE.equals(call(nation, "isFounder", new Class<?>[]{UUID.class}, player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
            return false;
        }
    }

    @Override
    public boolean canGovern(UUID player, UUID cityId) {
        if (player == null || cityId == null) return false;
        try {
            Access access = access();
            Object city = city(access, cityId);
            if (city == null) return false;
            Object nation = nation(access, city);
            return nation != null
                    && belongsToCityNation(access, city, player)
                    && Boolean.TRUE.equals(call(nation, "isFounder", new Class<?>[]{UUID.class}, player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
            return false;
        }
    }

    @Override
    public boolean ownsSite(UUID cityId, Site site) {
        if (cityId == null || site == null) return false;
        try {
            Access access = access();
            World world = owner.getServer().getWorld(site.worldId());
            if (world == null || !world.getName().equals(site.worldName())) return false;
            Object city = city(access, cityId);
            if (city == null || nation(access, city) == null) return false;
            int chunkX = site.x() >> 4;
            int chunkZ = site.z() >> 4;
            if (!Boolean.TRUE.equals(call(city, "hasClaim", new Class<?>[]{String.class, int.class, int.class},
                    site.worldName(), chunkX, chunkZ))) return false;
            Object claim = optional(call(access.nations(), "claimAt", new Class<?>[]{String.class, int.class, int.class},
                    site.worldName(), chunkX, chunkZ));
            Object nationId = call(city, "nationId");
            return claim != null && nationId.toString().equals(call(claim, "ownerId"));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
            return false;
        }
    }

    @Override
    public List<BorderNeighbor> foreignNeighbors(CoreCity current,Site site) {
        if(current==null||site==null)return List.of();
        try {
            Access access=access();
            Object city=city(access,current.cityId());
            if(city==null||nation(access,city)==null||!current.nationId().equals(call(call(city,"nationId"),"value"))||!ownsSite(current.cityId(),site))return List.of();
            int x=site.x()>>4,z=site.z()>>4;List<BorderNeighbor> result=new ArrayList<>();
            for(int[] offset:new int[][]{{-1,0},{0,-1},{0,1},{1,0}}){
                int nextX=Math.addExact(x,offset[0]),nextZ=Math.addExact(z,offset[1]);
                Object claim=optional(call(access.nations(),"claimAt",new Class<?>[]{String.class,int.class,int.class},site.worldName(),nextX,nextZ));
                if(claim==null)continue;UUID peer=UUID.fromString((String)call(claim,"ownerId"));if(peer.equals(current.nationId()))continue;
                Object peerId=access.idType().getMethod("of",UUID.class).invoke(null,peer);
                Object peerNation=optional(call(access.nations(),"nationById",new Class<?>[]{access.idType()},peerId));
                if(peerNation!=null&&peerId.equals(call(peerNation,"id")))result.add(new BorderNeighbor(site,nextX,nextZ,peer));
            }
            return List.copyOf(result);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){failed(failure);return List.of();}
    }

    @Override
    public String status() {
        try {
            Access access = access();
            return "StarCore " + call(access.api(), access.apiType(), "version", new Class<?>[0])
                    + " 公共 API 已连接；国家/城市只读联动；Paper 主线程";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failed(failure);
            return problem;
        }
    }

    private Access access() throws ReflectiveOperationException {
        if (!owner.getServer().isPrimaryThread()) throw new IllegalStateException("StarCore 读取必须在 Paper 主线程执行");
        Plugin core = owner.getServer().getPluginManager().getPlugin("StarCore");
        if (core == null || !core.isEnabled()) throw new IllegalStateException("StarCore 未安装或未启用");
        ClassLoader loader = core.getClass().getClassLoader();
        Class<?> apiType = Class.forName(BASE + "api.StarCoreApi", true, loader);
        Object api = owner.getServer().getServicesManager().load(apiType);
        if (api == null) throw new IllegalStateException("StarCore 公共 API 未注册");
        Class<?> idType = Class.forName(BASE + "module.nation.model.NationId", true, loader);
        Object nations = optional(call(api, apiType, "nationService", new Class<?>[0]));
        Object cities = service(api, apiType, loader, "module.city.CityService");
        if (nations == null || cities == null) throw new IllegalStateException("StarCore 国家/城市模块未启用");
        return new Access(api, apiType, loader, idType, nations, cities);
    }

    private Object city(Access access, UUID cityId) throws ReflectiveOperationException {
        return optional(call(access.cities(), "getCity", new Class<?>[]{UUID.class}, cityId));
    }

    private Object nation(Access access, Object city) throws ReflectiveOperationException {
        Object id = call(city, "nationId");
        Object nation = optional(call(access.nations(), "nationById", new Class<?>[]{access.idType()}, id));
        return nation != null && id.equals(call(nation, "id")) ? nation : null;
    }

    private boolean belongsToCityNation(Access access, Object city, UUID player) throws ReflectiveOperationException {
        Object playerNation = optional(call(access.nations(), "nationOf", new Class<?>[]{UUID.class}, player));
        return playerNation != null && Objects.equals(call(city, "nationId"), call(playerNation, "id"));
    }

    private CoreCity snapshot(Access access, Object city, Object nation) throws ReflectiveOperationException {
        Object id = call(nation, "id");
        List<String> detail = new ArrayList<>();
        double speed = number(access, "module.technology.TechnologyService", "getTotalModifier",
                new Class<?>[]{access.idType(), String.class}, new Object[]{id, "production_speed"}, 0, detail, "科技生产速度");
        double policy = policy(access, id, detail);
        double prosperity = number(access, "module.prosperity.ProsperityService", "getResourceBonus",
                new Class<?>[]{access.idType()}, new Object[]{id}, 1, detail, "繁荣资源倍率");
        // Resource stock is authoritative context, not a fabricated production bonus.
        try {
            Object resources = service(access, "module.resource.ResourceService");
            if (resources == null) detail.add("国家资源模块缺失；不应用库存加成");
            else {
                Object stock = call(resources, "stockpile", new Class<?>[]{access.idType()}, id);
                if (!(stock instanceof Map<?, ?>)) throw new IllegalStateException("stockpile 返回类型不兼容");
                detail.add("国家资源=" + stock);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            detail.add("国家资源读取不可用；不应用库存加成");
        }
        double raw = Math.max(0, 1 + speed) * Math.max(0, 1 + policy) * Math.max(0, prosperity);
        double multiplier = Math.min(10, raw);
        if (!Double.isFinite(multiplier)) throw new IllegalStateException("国家生产倍率超出数值范围");
        if (raw > 10) detail.add("最终倍率按 StarWorld 上限裁剪到 10");
        return new CoreCity((UUID) call(city, "id"), (UUID) call(id, "value"),
                (String) call(city, "name"), (String) call(nation, "name"), multiplier, String.join("；", detail));
    }

    private double policy(Access access, Object id, List<String> detail) {
        try {
            Object service = service(access, "module.policy.PolicyService");
            if (service == null) throw new IllegalStateException("政策模块缺失");
            Class<?> scope = Class.forName(BASE + "module.policy.model.PolicyEffectScope", true, access.loader());
            Object global = enumConstant(scope, "GLOBAL");
            Object production = enumConstant(scope, "PRODUCTION");
            Class<?>[] signature = {access.idType(), String.class, scope};
            double bonus = finite(call(service, "activePolicyModifier", signature, id, "production_bonus", global))
                    + finite(call(service, "activePolicyModifier", signature, id, "production_bonus", production));
            if (!Double.isFinite(bonus)) throw new IllegalStateException("政策加成无效");
            detail.add("政策生产加成=" + bonus);
            return bonus;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            detail.add("政策读取不可用；生产加成按 0");
            return 0;
        }
    }

    private double number(Access access, String type, String method, Class<?>[] signature, Object[] arguments,
                          double fallback, List<String> detail, String label) {
        try {
            Object service = service(access, type);
            if (service == null) throw new IllegalStateException("模块缺失");
            double value = finite(call(service, method, signature, arguments));
            detail.add(label + "=" + value);
            return value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            detail.add(label + "读取不可用；按 " + fallback);
            return fallback;
        }
    }

    private static double finite(Object result) {
        if (!(result instanceof Number number) || !Double.isFinite(number.doubleValue()))
            throw new IllegalStateException("API 返回了无效数值");
        return number.doubleValue();
    }

    private static Object enumConstant(Class<?> type, String name) {
        for (Object value : type.getEnumConstants()) if (((Enum<?>) value).name().equals(name)) return value;
        throw new IllegalStateException("缺少公开枚举值 " + name);
    }

    private static Object service(Access access, String type) throws ReflectiveOperationException {
        return service(access.api(), access.apiType(), access.loader(), type);
    }

    private static Object service(Object api, Class<?> apiType, ClassLoader loader, String name)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(BASE + name, true, loader);
        return optional(call(api, apiType, "service", new Class<?>[]{Class.class}, type));
    }

    private static Object optional(Object value) {
        if (!(value instanceof Optional<?> optional)) throw new IllegalStateException("API 应返回 Optional");
        return optional.orElse(null);
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return call(target, name, new Class<?>[0]);
    }

    private static Object call(Object target, String name, Class<?>[] signature, Object... arguments)
            throws ReflectiveOperationException {
        return call(target, target.getClass(), name, signature, arguments);
    }

    private static Object call(Object target, Class<?> contract, String name, Class<?>[] signature, Object... arguments)
            throws ReflectiveOperationException {
        return contract.getMethod(name, signature).invoke(target, arguments);
    }

    private void failed(Throwable failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
        problem = "StarCore 联动不可用：" + cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private record Access(Object api, Class<?> apiType, ClassLoader loader, Class<?> idType,
                          Object nations, Object cities) {}
}

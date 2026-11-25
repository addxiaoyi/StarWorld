package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.integration.CoreBridge;
import dev.starworld.integration.TransitPermitPort;
import dev.starworld.integration.TransitPermitPort.*;
import dev.starworld.logistics.LogisticsState.*;
import java.math.BigDecimal;
import java.util.*;
import org.bukkit.Server;

/** Bounded claimed footprint of an explicit path. This describes a proposal, never grants passage. */
public final class TransitCorridor {
    private TransitCorridor(){}
    public record Footprint(String routeKey,String fingerprint,UUID originNation,UUID targetNation,Map<UUID,Set<Parcel>> parcels){
        public Footprint {Map<UUID,Set<Parcel>> copy=new HashMap<>();parcels.forEach((k,v)->copy.put(k,Set.copyOf(v)));parcels=Map.copyOf(copy);if(parcels.isEmpty()||parcels.size()>TransitPermitPort.MAX_NATIONS)throw new IllegalArgumentException("过境国家数量超限");}
        public Terms terms(UUID nation,BigDecimal toll,long expiry){Set<Parcel> grant=parcels.get(nation);if(grant==null)throw new IllegalArgumentException("本国不在已声明过境路线中");return new Terms(routeKey,fingerprint,nation,originNation,targetNation,grant,toll,expiry);}
        public boolean matches(Terms expected){return parcels.containsKey(expected.grantingNation())&&terms(expected.grantingNation(),expected.tollPerUnit(),expected.expiresAt()).equals(expected);}
    }
    public static Footprint describe(Server server,CoreBridge core,Route route,Map<UUID,Node> nodes){
        if(!server.isPrimaryThread())throw new IllegalStateException("过境取证必须在 Paper 主线程执行");
        if(!route.requiresTransit())throw new IllegalArgumentException("该商路没有第三国节点");Corridor.Plan plan=Corridor.plan(route,nodes);
        Map<UUID,UUID> cities=new TreeMap<>();for(UUID id:route.nodes()){Node node=Objects.requireNonNull(nodes.get(id));cities.put(node.cityId(),node.nationId());}
        UUID origin=Objects.requireNonNull(cities.get(route.originCity())),target=Objects.requireNonNull(cities.get(route.targetCity()));
        Set<UUID> grantors=new HashSet<>();cities.forEach((city,nation)->{if(!city.equals(route.originCity())&&!city.equals(route.targetCity())){if(nation.equals(origin)||nation.equals(target))throw new IllegalArgumentException("额外城市节点须属于独立第三国");grantors.add(nation);}});
        if(grantors.size()>TransitPermitPort.MAX_NATIONS)throw new IllegalArgumentException("过境路线最多15个第三国");
        Map<String,Site> chunks=new LinkedHashMap<>();
        for(Site at:plan.columns()){
            if(route.mode()==Mode.SEA&&!plan.berths().contains(at)){for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){Site edge=new Site(at.worldId(),at.worldName(),at.x()+x,at.y(),at.z()+z);chunks.putIfAbsent(edge.regionKey(),edge);}}
            else chunks.putIfAbsent(at.regionKey(),at);
        }
        Map<UUID,Set<Parcel>> result=new HashMap<>();
        for(Site at:chunks.values()){
            if(core.ownsSite(route.originCity(),at)||core.ownsSite(route.targetCity(),at))continue;
            UUID claimedCity=null;for(var entry:cities.entrySet())if(grantors.contains(entry.getValue())&&core.ownsSite(entry.getKey(),at)){if(claimedCity!=null)throw new IllegalArgumentException("过境区块存在重复城市归属");claimedCity=entry.getKey();}
            if(claimedCity==null)throw new IllegalArgumentException("沿途双层领土未被路线声明城市覆盖");
            Set<Parcel> parcels=result.computeIfAbsent(cities.get(claimedCity),ignored->new HashSet<>());parcels.add(new Parcel(at.worldId(),at.worldName(),claimedCity,at.x()>>4,at.z()>>4));if(parcels.size()>TransitPermitPort.MAX_PARCELS)throw new IllegalArgumentException("每国过境最多128区块");
        }
        if(!result.keySet().equals(grantors))throw new IllegalArgumentException("第三国节点与沿途领土不一致");return new Footprint(route.transitKey(),plan.fingerprint(),origin,target,result);
    }
    public static Map<UUID,Permit> authorize(Footprint footprint,TransitPermitPort port){
        if(port==null)throw new IllegalArgumentException("核心过境许可服务尚未启用");Map<UUID,Permit> permits=new HashMap<>();
        for(UUID nation:new TreeSet<>(footprint.parcels().keySet())){
            Permit permit=port.permit(footprint.routeKey(),nation).orElseThrow(()->new IllegalArgumentException("第三国尚未批准此商路："+nation));
            if(!footprint.matches(permit.terms())||permit.revoked()||!port.allowed(permit))throw new IllegalArgumentException("第三国许可已撤销、到期，或领土/战争状态变化："+nation);permits.put(nation,permit);
        }
        return Map.copyOf(permits);
    }
}

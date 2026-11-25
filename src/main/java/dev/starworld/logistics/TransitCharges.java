package dev.starworld.logistics;

import dev.starworld.integration.TransitPermitPort;
import dev.starworld.integration.TransitPermitPort.Permit;
import dev.starworld.integration.TreasuryTransferPort;
import dev.starworld.logistics.LogisticsState.*;
import java.math.BigDecimal;
import java.util.*;

/** Frozen economic consent is separate from the current revocable corridor authority. */
public final class TransitCharges {
    private TransitCharges(){}
    public static Map<UUID,Permit> snapshot(Route route,AuthoritySnapshot auth){
        if(!route.requiresTransit())return Map.of();
        Corridor.Proof proof=auth.corridors().get(route.id());Corridor.limitation(route,proof).ifPresent(reason->{throw new IllegalArgumentException(reason);});
        return proof.transit();
    }
    public static Map<UUID,BigDecimal> tolls(Map<UUID,Permit> permits,long units){
        if(units<1||permits.size()>TransitPermitPort.MAX_NATIONS)throw new IllegalArgumentException("过境计费数量无效");Map<UUID,BigDecimal> result=new HashMap<>();
        for(var entry:permits.entrySet()){
            Permit p=entry.getValue();if(!entry.getKey().equals(p.terms().grantingNation())||p.revoked())throw new IllegalArgumentException("过境计费许可无效");
            BigDecimal value=p.terms().tollPerUnit().multiply(BigDecimal.valueOf(units));if(value.signum()>0)result.put(entry.getKey(),TreasuryTransferPort.money(value));
        }
        if(!result.isEmpty())TreasuryTransferPort.money(TreasuryTransferPort.total(result));return Map.copyOf(result);
    }
    /** Renewed/reapproved rights may resume the same price and route; neither side silently reprices. */
    public static boolean sameEconomics(Map<UUID,Permit> frozen,Map<UUID,Permit> current){
        if(!frozen.keySet().equals(current.keySet()))return false;
        for(UUID nation:frozen.keySet()){
            var old=frozen.get(nation).terms();var live=current.get(nation).terms();
            if(!old.routeKey().equals(live.routeKey())||!old.fingerprint().equals(live.fingerprint())||!old.originNation().equals(live.originNation())||!old.targetNation().equals(live.targetNation())||!old.grantingNation().equals(live.grantingNation())||!old.parcels().equals(live.parcels())||old.tollPerUnit().compareTo(live.tollPerUnit())!=0)return false;
        }return true;
    }
    public static void requireFree(Route route,AuthoritySnapshot auth){if(!tolls(snapshot(route,auth),1).isEmpty())throw new IllegalArgumentException("该商路收取过境费，请通过市场报价确认国家付款；实物易货仅能使用免收费许可");}
    /** A bilateral reroute may use a free alternative, or return to its already paid original terms. */
    public static void requireCovered(Route route,AuthoritySnapshot auth,Map<UUID,Permit> paid){
        var current=snapshot(route,auth);if(!tolls(current,1).isEmpty()&&!sameEconomics(paid,current))throw new IllegalArgumentException("当前过境收费与原付款不符，货物继续托管；可改走免费商路或退款后重新报价");
    }
}

package dev.starworld.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Immutable public core authority; every implementation call is confined to the Paper main thread. */
public interface TransitPermitPort {
    int MAX_NATIONS=15, MAX_PARCELS=128;
    record Parcel(UUID worldId,String worldName,UUID cityId,int chunkX,int chunkZ){
        public Parcel {Objects.requireNonNull(worldId);Objects.requireNonNull(cityId);if(worldName==null||worldName.isBlank()||worldName.length()>128||Math.abs((long)chunkX)>1_875_000||Math.abs((long)chunkZ)>1_875_000)throw new IllegalArgumentException("过境区块无效");}
    }
    record Terms(String routeKey,String fingerprint,UUID grantingNation,UUID originNation,UUID targetNation,Set<Parcel> parcels,BigDecimal tollPerUnit,long expiresAt){
        public Terms {
            key(routeKey);if(fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("过境路线指纹无效");
            Objects.requireNonNull(grantingNation);Objects.requireNonNull(originNation);Objects.requireNonNull(targetNation);
            if(grantingNation.equals(originNation)||grantingNation.equals(targetNation))throw new IllegalArgumentException("过境许可必须来自第三国");
            if(parcels==null||parcels.isEmpty()||parcels.size()>MAX_PARCELS)throw new IllegalArgumentException("过境区块须为1至128个");parcels=Set.copyOf(parcels);
            Parcel first=parcels.iterator().next();
            if(parcels.stream().anyMatch(p->!first.worldId().equals(p.worldId())||!first.worldName().equals(p.worldName()))||parcels.stream().map(p->p.chunkX()+":"+p.chunkZ()).distinct().count()!=parcels.size())throw new IllegalArgumentException("过境区块必须同世界且不重复");
            tollPerUnit=toll(tollPerUnit);if(expiresAt<0)throw new IllegalArgumentException("过境有效期无效");
        }
    }
    record Permit(Terms terms,long revision,boolean revoked,UUID actor,long updatedAt){
        public Permit {Objects.requireNonNull(terms);Objects.requireNonNull(actor);if(revision<1||updatedAt<0)throw new IllegalArgumentException("过境许可版本无效");}
    }
    enum Action { APPROVE, REVOKE }
    record Receipt(UUID requestId,Action action,long expectedRevision,Permit permit){
        public Receipt {Objects.requireNonNull(requestId);Objects.requireNonNull(action);Objects.requireNonNull(permit);if(expectedRevision<0||permit.revision()!=Math.addExact(expectedRevision,1)||(action==Action.REVOKE)!=permit.revoked())throw new IllegalArgumentException("过境操作回执无效");}
    }
    Optional<Permit> permit(String routeKey,UUID grantingNation);
    boolean allowed(Permit expected);
    Receipt approve(UUID request,UUID actor,long expectedRevision,Terms terms);
    Receipt revoke(UUID request,UUID actor,long expectedRevision,String routeKey,UUID grantingNation);
    Optional<Receipt> receipt(UUID request);
    String status();
    static BigDecimal toll(BigDecimal value){
        Objects.requireNonNull(value);if(value.scale()>64||value.precision()>80||value.signum()<0||value.compareTo(new BigDecimal("1000000.00"))>0)throw new IllegalArgumentException("每件过境费应为0至100万金币");
        try{return value.setScale(2,RoundingMode.UNNECESSARY);}catch(ArithmeticException invalid){throw new IllegalArgumentException("过境费最多两位小数",invalid);}
    }
    private static void key(String value){if(value==null||!value.matches("[a-zA-Z0-9:_-]{1,160}"))throw new IllegalArgumentException("过境商路标识无效");}
}

package dev.starworld.integration;

import dev.starworld.integration.TransitPermitPort.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

/** Bounded versioned immutable terms for local quotes and operation drafts, never live authority. */
public final class TransitPermitCodec {
    private static final int MAX_BYTES=512_000;
    private TransitPermitCodec(){}
    public static String encode(Map<UUID,Permit> permits){
        if(permits.isEmpty())return "";if(permits.size()>TransitPermitPort.MAX_NATIONS)throw new IllegalArgumentException("过境快照数量超限");
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(1);out.writeInt(permits.size());
            for(UUID nation:new TreeSet<>(permits.keySet())){Permit p=permits.get(nation);Terms t=p.terms();if(!nation.equals(t.grantingNation()))throw new IllegalArgumentException("过境快照国家不符");
                out.writeUTF(t.routeKey());out.writeUTF(t.fingerprint());uuid(out,t.grantingNation());uuid(out,t.originNation());uuid(out,t.targetNation());out.writeUTF(t.tollPerUnit().toPlainString());out.writeLong(t.expiresAt());out.writeLong(p.revision());out.writeBoolean(p.revoked());uuid(out,p.actor());out.writeLong(p.updatedAt());out.writeInt(t.parcels().size());
                for(Parcel parcel:t.parcels().stream().sorted(Comparator.comparingInt(Parcel::chunkX).thenComparingInt(Parcel::chunkZ)).toList()){uuid(out,parcel.worldId());out.writeUTF(parcel.worldName());uuid(out,parcel.cityId());out.writeInt(parcel.chunkX());out.writeInt(parcel.chunkZ());}
            }out.flush();if(bytes.size()>MAX_BYTES)throw new IllegalArgumentException("过境快照过大");return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
    public static Map<UUID,Permit> decode(String value){
        Objects.requireNonNull(value);if(value.isEmpty())return Map.of();if(value.length()>MAX_BYTES*4/3+4)throw new IllegalArgumentException("过境快照过大");
        try{byte[] bytes=Base64.getDecoder().decode(value);if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("过境快照过大");var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)throw new IllegalArgumentException("不支持的过境快照版本");int count=in.readInt();if(count<1||count>TransitPermitPort.MAX_NATIONS)throw new IllegalArgumentException("过境快照数量超限");Map<UUID,Permit> result=new HashMap<>();
            for(int i=0;i<count;i++){
                String route=in.readUTF(),fingerprint=in.readUTF();UUID nation=uuid(in),origin=uuid(in),target=uuid(in);BigDecimal toll=new BigDecimal(in.readUTF());long expiry=in.readLong(),revision=in.readLong();boolean revoked=in.readBoolean();UUID actor=uuid(in);long updated=in.readLong();int size=in.readInt();if(size<1||size>TransitPermitPort.MAX_PARCELS)throw new IllegalArgumentException("过境快照区块数量超限");Set<Parcel> parcels=new HashSet<>();
                for(int j=0;j<size;j++)if(!parcels.add(new Parcel(uuid(in),in.readUTF(),uuid(in),in.readInt(),in.readInt())))throw new IllegalArgumentException("过境快照区块重复");
                Permit permit=new Permit(new Terms(route,fingerprint,nation,origin,target,parcels,toll,expiry),revision,revoked,actor,updated);if(result.put(nation,permit)!=null)throw new IllegalArgumentException("过境快照国家重复");
            }
            if(in.read()!=-1||!encode(result).equals(value))throw new IllegalArgumentException("过境快照包含非规范或未知字段");return Map.copyOf(result);
        }catch(IOException|NumberFormatException invalid){throw new IllegalArgumentException("过境快照已损坏",invalid);}
    }
    private static void uuid(DataOutputStream out,UUID value)throws IOException{out.writeLong(value.getMostSignificantBits());out.writeLong(value.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
}

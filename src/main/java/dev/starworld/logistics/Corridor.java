package dev.starworld.logistics;

import dev.starworld.domain.WorldState.Site;
import dev.starworld.logistics.LogisticsState.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Finite, explicit centerlines. Planning reads no world and never searches for a path. */
public final class Corridor {
    public static final int MAX_COLUMNS=1024, MAX_BLOCK_READS=16384, MAX_ROUTES=32;
    private Corridor() {}
    public record Plan(String fingerprint,List<Site> columns,Set<Site> berths) {
        public Plan {columns=List.copyOf(columns);berths=Set.copyOf(berths);}
    }
    public record Proof(UUID routeId,String fingerprint,boolean pass,int checkedColumns,int blockReads,String detail) {
        public Proof {Objects.requireNonNull(routeId);Objects.requireNonNull(fingerprint);Objects.requireNonNull(detail);
            if(checkedColumns<0||checkedColumns>MAX_COLUMNS||blockReads<0||blockReads>MAX_BLOCK_READS||detail.length()>512
                    ||(pass&&(!fingerprint.matches("[a-f0-9]{64}")||checkedColumns<2||blockReads<1)))throw new IllegalArgumentException("Invalid corridor proof");}
    }
    public static Plan plan(Route route,Map<UUID,Node> nodes){
        if(route.nodes().size()<2||route.nodes().size()>64)throw new IllegalArgumentException("通道节点数量超限");
        LinkedHashSet<Site> columns=new LinkedHashSet<>();Set<Site> berths=new HashSet<>();StringBuilder identity=new StringBuilder(route.id()+"|"+route.originCity()+"|"+route.targetCity()+"|"+route.mode());
        Node previous=null;long steps=0;
        for(UUID id:route.nodes()){
            Node current=nodes.get(id);if(current==null)throw new IllegalArgumentException("通道节点缺失");Site end=current.site();
            if(Math.abs((long)end.x())>29_999_984||Math.abs((long)end.z())>29_999_984||end.y() < -2048||end.y()>2048)throw new IllegalArgumentException("通道坐标超出安全范围");
            identity.append('|').append(id).append('|').append(current.cityId()).append('|').append(current.nationId()).append('|').append(current.type()).append('|').append(end.worldName()).append('|').append(end.anchorKey());
            if(route.mode()==Mode.SEA){if(current.type()!=NodeType.PORT)throw new IllegalArgumentException("水路通道仅连接港口");berths.add(end);}
            if(previous!=null){
                Site start=previous.site();long dx=(long)end.x()-start.x(),dz=(long)end.z()-start.z(),dy=(long)end.y()-start.y();long distance=Math.abs(dx)+Math.abs(dz);
                if(!start.worldId().equals(end.worldId())||!start.worldName().equals(end.worldName()))throw new IllegalArgumentException("通道不能跨世界");
                if((dx!=0&&dz!=0)||distance==0)throw new IllegalArgumentException("通道相邻节点需沿南北或东西直线；转弯处请登记节点");
                if(Math.abs(dy)>distance||(route.mode()==Mode.SEA&&dy!=0))throw new IllegalArgumentException("道路每格最多升降一格；水路港口必须同高");
                if(route.mode()==Mode.SEA&&distance<2)throw new IllegalArgumentException("港口之间至少需要一格水面");
                steps=Math.addExact(steps,distance);if(steps>=MAX_COLUMNS)throw new IllegalArgumentException("通道全程最多1024个中心点，请缩短路线");
                for(int i=0;i<=distance;i++){
                    // floor interpolation is symmetric when the journey is reversed, including negative coordinates.
                    int y=(int)Math.floorDiv((long)start.y()*distance+dy*i,distance);
                    columns.add(new Site(start.worldId(),start.worldName(),start.x()+Integer.signum((int)dx)*i,y,start.z()+Integer.signum((int)dz)*i));
                }
            }
            previous=current;
        }
        try{return new Plan(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toString().getBytes(StandardCharsets.UTF_8))),new ArrayList<>(columns),berths);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static Optional<String> limitation(Route route,Proof proof){
        if(!route.corridorRequired())return Optional.empty();
        if(proof==null||!route.id().equals(proof.routeId()))return Optional.of("商路通道缺少本次现场证据，运输暂停");
        return proof.pass()?Optional.empty():Optional.of("商路通道受阻："+proof.detail());
    }
}

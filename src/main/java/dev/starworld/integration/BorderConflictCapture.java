package dev.starworld.integration;

import dev.starworld.domain.WorldState.*;
import dev.starworld.evolution.EvolutionState.BorderWitness;
import java.util.*;
import org.bukkit.plugin.java.JavaPlugin;

/** Bounded Paper-thread public claim/diplomacy capture. No neighbour world/chunk access occurs. */
public final class BorderConflictCapture {
    public static final int MAX_REGIONS=64,MAX_PEERS=100;
    private final JavaPlugin plugin;
    private final CoreBridge core;
    private final CoreExtensionPort extensions;
    public BorderConflictCapture(JavaPlugin plugin,CoreBridge core,CoreExtensionPort extensions){this.plugin=Objects.requireNonNull(plugin);this.core=Objects.requireNonNull(core);this.extensions=Objects.requireNonNull(extensions);}
    public record Captured(Optional<CoreExtensionPort.NationSnapshot> nation,List<BorderWitness> witnesses) {
        public Captured {nation=Objects.requireNonNull(nation);witnesses=List.copyOf(witnesses);if(witnesses.size()>256)throw new IllegalArgumentException("Too many border witnesses");}
    }
    public Captured capture(CoreCity current,CityView registered,Set<UUID> verified,long period){
        if(!plugin.getServer().isPrimaryThread())throw new IllegalStateException("边境证据必须在Paper主线程读取");
        Objects.requireNonNull(current);Objects.requireNonNull(registered);Objects.requireNonNull(verified);if(period<0)throw new IllegalArgumentException("Invalid border period");
        Map<String,Site> regions=new TreeMap<>();
        if(current.cityId().equals(registered.id())&&current.nationId().equals(registered.nationId()))for(Building b:registered.buildings()){
            if(b.cityId().equals(current.cityId())&&b.nationId().equals(current.nationId())&&verified.contains(b.id()))regions.putIfAbsent(b.site().regionKey(),b.site());
        }
        // Corrupt legacy input cannot turn this into an unbounded authority scan.
        if(regions.size()>MAX_REGIONS)throw new IllegalArgumentException("Too many registered border regions");
        List<CoreBridge.BorderNeighbor> neighbours=new ArrayList<>();
        for(Site site:regions.values()){
            List<CoreBridge.BorderNeighbor> found;try{found=core.foreignNeighbors(current,site);}catch(RuntimeException|LinkageError unavailable){continue;}if(found==null||found.size()>4)continue;
            for(var n:found)if(n.source().equals(site)&&!n.nation().equals(current.nationId()))neighbours.add(n);
        }
        // Select a stable bounded subset. A city bordering >100 distinct nations remains usable;
        // omitted peers produce no authority/evidence rather than overflowing snapshot's contract.
        Set<UUID> peers=new LinkedHashSet<>();neighbours.stream().map(CoreBridge.BorderNeighbor::nation).distinct().sorted().limit(MAX_PEERS).forEach(peers::add);
        Optional<CoreExtensionPort.NationSnapshot> nation;
        try{nation=extensions.snapshot(current.nationId(),Set.copyOf(peers));if(nation==null)nation=Optional.empty();nation=nation.filter(n->n.nationId().equals(current.nationId()));}
        catch(RuntimeException|LinkageError unavailable){nation=Optional.empty();}
        if(nation.isEmpty())return new Captured(nation,List.of());
        var snapshot=nation.get();List<BorderWitness> witnesses=new ArrayList<>();
        for(var n:neighbours){if(!peers.contains(n.nation()))continue;String relation=null;
            if("available".equals(snapshot.capabilities().get("war"))&&snapshot.warOpponents().contains(n.nation()))relation="WAR";
            else if("available".equals(snapshot.capabilities().get("diplomacy"))&&Set.of("HOSTILE","WAR").contains(snapshot.diplomacy().getOrDefault(n.nation(),"UNKNOWN")))relation=snapshot.diplomacy().get(n.nation());
            if(relation!=null){Site site=n.source();witnesses.add(new BorderWitness(current.cityId(),current.nationId(),site.worldId(),site.worldName(),site.x()>>4,site.z()>>4,n.chunkX(),n.chunkZ(),n.nation(),relation,period));}
        }
        return new Captured(nation,witnesses);
    }
}

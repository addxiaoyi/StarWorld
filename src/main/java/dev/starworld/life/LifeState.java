package dev.starworld.life;

import dev.starworld.domain.WorldState.Site;
import java.util.*;

/** Values crossing from owning server threads to the storage worker; never contains Bukkit objects. */
public final class LifeState {
    private LifeState() {}
    public record ProfessionView(UUID player, String profession, long changedPeriod, long cultureContributed) {}
    public record ContributionReceipt(UUID id, UUID city, UUID actor, String kind, String source, long period,
                                      int culture, double productionBonus, long bonusUntilPeriod, boolean applied) {}
    public record Decoration(UUID furnitureId, UUID metaEntityId, UUID city, UUID nation, UUID artisan,
                             String itemId, Site site, long registeredPeriod) {}
    public record ConcertView(UUID id, UUID city, UUID nation, UUID performer, Site site, long startPeriod, long startedMillis,
                              long lastNoteMillis, int notes, long pitchMask, int audience, String state) {}
    public record LifeView(UUID city, UUID nation, long culture, double productionMultiplier, List<Decoration> decorations,
                           List<ConcertView> concerts, long decorationPeriod) {
        public LifeView { decorations = List.copyOf(decorations); concerts = List.copyOf(concerts); }
    }
    /** Constructed only by the verified platform adapter in this package. Registration alone earns nothing. */
    public static final class DecorationEvidence {
        private final UUID furnitureId, metaEntityId, city, nation, actor; private final String itemId; private final Site site;
        DecorationEvidence(UUID furnitureId, UUID metaEntityId, UUID city, UUID nation, UUID actor, String itemId, Site site) {
            this.furnitureId = Objects.requireNonNull(furnitureId); this.metaEntityId = Objects.requireNonNull(metaEntityId); this.city = Objects.requireNonNull(city);
            this.nation = Objects.requireNonNull(nation); this.actor = Objects.requireNonNull(actor); this.itemId = Objects.requireNonNull(itemId); this.site = Objects.requireNonNull(site);
        }
        public UUID furnitureId() { return furnitureId; } public UUID metaEntityId() { return metaEntityId; } public UUID city() { return city; }
        public UUID nation() { return nation; } public UUID actor() { return actor; } public String itemId() { return itemId; } public Site site() { return site; }
    }
    /** Receipt for an actual StarWorld-controlled sound after current ownership/instrument/audience checks. */
    public static final class NoteReceipt {
        private final UUID session, performer, city, nation; private final int sequence, pitch; private final long millis; private final Set<UUID> audience;
        NoteReceipt(UUID session, UUID performer, UUID city, UUID nation, int sequence, int pitch, long millis, Set<UUID> audience) {
            this.session = Objects.requireNonNull(session); this.performer = Objects.requireNonNull(performer); this.city = Objects.requireNonNull(city); this.nation = Objects.requireNonNull(nation);
            this.sequence = sequence; this.pitch = pitch; this.millis = millis; this.audience = Set.copyOf(audience);
        }
        public UUID session() { return session; } public UUID performer() { return performer; } public UUID city() { return city; } public UUID nation() { return nation; }
        public int sequence() { return sequence; } public int pitch() { return pitch; } public long millis() { return millis; } public Set<UUID> audience() { return audience; }
    }
}

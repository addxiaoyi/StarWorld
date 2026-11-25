package dev.starworld.civic;

import java.util.*;
import dev.starworld.civic.CivicNeedsDefinitions.Need;

/** Durable values only; no contribution object supplied by a caller is trusted as evidence. */
public final class CivicNeedsState {
    private CivicNeedsState() {}
    public enum Status { OPEN, COMPLETED, EXPIRED, INVALIDATED }
    public record Request(UUID id,UUID cityId,UUID nationId,Need definition,long requestedPeriod,long expiryPeriod,
                          long cooldownBucket,String actor,Status status,long completedPeriod) {
        public Request {
            Objects.requireNonNull(id);Objects.requireNonNull(cityId);Objects.requireNonNull(nationId);Objects.requireNonNull(definition);Objects.requireNonNull(actor);Objects.requireNonNull(status);
            if(requestedPeriod<0||expiryPeriod!=Math.addExact(requestedPeriod,definition.lifetimePeriods())||cooldownBucket!=requestedPeriod/definition.cooldownPeriods()
                    ||(status==Status.COMPLETED)!=(completedPeriod>=0)||completedPeriod>=0&&(completedPeriod<=requestedPeriod||completedPeriod>expiryPeriod))throw new IllegalArgumentException("Invalid persisted civic request");
        }
        public String type() { return definition.id(); }
        public boolean expiredAt(long currentPeriod) { return status==Status.EXPIRED||status==Status.OPEN&&currentPeriod>expiryPeriod; }
    }
    public record Receipt(UUID requestId,UUID cityId,UUID nationId,long completedPeriod,List<UUID> claimedActionUUIDs,
                          Map<String,Long> paidItems,double actualStability,double actualProsperity,boolean applied) {
        public Receipt {
            Objects.requireNonNull(requestId);Objects.requireNonNull(cityId);Objects.requireNonNull(nationId);
            claimedActionUUIDs=List.copyOf(claimedActionUUIDs);paidItems=Map.copyOf(paidItems);
            if(completedPeriod<0||claimedActionUUIDs.isEmpty()||claimedActionUUIDs.size()>4||new HashSet<>(claimedActionUUIDs).size()!=claimedActionUUIDs.size()
                    ||!Double.isFinite(actualStability)||actualStability<0||actualStability>2||!Double.isFinite(actualProsperity)||actualProsperity<0||actualProsperity>3)throw new IllegalArgumentException("Invalid civic receipt");
        }
    }
    public record History(UUID requestId,long period,String kind,String detail) {
        public History { Objects.requireNonNull(requestId);Objects.requireNonNull(kind);Objects.requireNonNull(detail);if(period<0)throw new IllegalArgumentException("Invalid civic history period"); }
    }
}

package dev.starcore.starcore.module.diplomacy;

import dev.starcore.starcore.module.nation.model.NationId;
import java.math.BigDecimal;
import java.util.*;

/** Test-only public contract shape. The candidate core owns validation and durable authority. */
public interface TransitPermitService {
    record Parcel(UUID worldId,String worldName,UUID cityId,int chunkX,int chunkZ){}
    record Terms(String routeKey,String fingerprint,NationId grantingNation,NationId originNation,NationId targetNation,Set<Parcel> parcels,BigDecimal tollPerUnit,long expiresAt){}
    record Permit(Terms terms,long revision,boolean revoked,UUID actor,long updatedAt){}
    enum Action { APPROVE, REVOKE }
    record Approval(UUID requestId,UUID actor,long expectedRevision,Terms terms){}
    record Revocation(UUID requestId,UUID actor,long expectedRevision,String routeKey,NationId grantingNation){}
    record Receipt(UUID requestId,Action action,long expectedRevision,Permit permit){}
    record Decision(Optional<Permit> permit,boolean allowed,String detail){}
    Receipt approve(Approval request);
    Receipt revoke(Revocation request);
    Optional<Receipt> receipt(UUID request);
    Optional<Permit> permit(String routeKey,NationId grantingNation);
    Decision evaluate(Terms expected,long revision);
}

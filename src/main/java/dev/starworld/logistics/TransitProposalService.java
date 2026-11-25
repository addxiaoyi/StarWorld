package dev.starworld.logistics;

import dev.starworld.integration.TransitPermitCodec;
import dev.starworld.integration.TransitPermitPort.*;
import dev.starworld.persistence.WorldStore;
import java.sql.*;
import java.util.*;

/** Durable approval drafts. Only a matching public core receipt means a permit was granted. */
public final class TransitProposalService {
    public static final long LIFETIME=60_000;
    public record Proposal(UUID id,UUID route,UUID actor,Terms terms,long expectedRevision,long createdAt,long expiresAt,Long approvedRevision){
        public Proposal {Objects.requireNonNull(id);Objects.requireNonNull(route);Objects.requireNonNull(actor);Objects.requireNonNull(terms);if(expectedRevision<0||createdAt<0||expiresAt-createdAt!=LIFETIME||approvedRevision!=null&&approvedRevision!=Math.addExact(expectedRevision,1))throw new IllegalArgumentException("过境许可草案无效");}
    }
    private final WorldStore store;private final LogisticsService logistics;
    TransitProposalService(WorldStore store,LogisticsService logistics)throws SQLException{
        this.store=store;this.logistics=logistics;
        store.tx(s->{s.execute("CREATE TABLE IF NOT EXISTS logistics_transit_proposal(id TEXT PRIMARY KEY,route TEXT NOT NULL REFERENCES logistics_route(id),actor TEXT NOT NULL,grantor TEXT NOT NULL,expected_revision INTEGER NOT NULL,terms TEXT NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,approved_revision INTEGER)");s.execute("CREATE INDEX IF NOT EXISTS logistics_transit_proposal_open ON logistics_transit_proposal(grantor,expires_at) WHERE approved_revision IS NULL");return null;});
    }
    public Optional<Proposal> proposal(UUID id)throws SQLException{return store.query("SELECT * FROM logistics_transit_proposal WHERE id=?",this::read,id).stream().findFirst();}
    public Proposal prepare(UUID id,UUID route,UUID actor,Terms terms,long expectedRevision,long now)throws SQLException{
        Proposal proposed=new Proposal(id,route,actor,terms,expectedRevision,now,Math.addExact(now,LIFETIME),null);
        return store.tx(s->{
            var saved=proposal(id);if(saved.isPresent()){if(!saved.get().equals(new Proposal(id,route,actor,terms,expectedRevision,now,proposed.expiresAt(),saved.get().approvedRevision())))throw new IllegalArgumentException("许可草案ID已用于其他明细");return saved.get();}
            var r=logistics.route(route).orElseThrow();if(!r.requiresTransit()||!r.transitKey().equals(terms.routeKey()))throw new IllegalArgumentException("许可草案与过境商路不符");Map<UUID,LogisticsState.Node> nodes=new HashMap<>();for(UUID node:r.nodes())nodes.put(node,logistics.node(node).orElseThrow());
            if(!Corridor.plan(r,nodes).fingerprint().equals(terms.fingerprint()))throw new IllegalArgumentException("商路已变更，请重新预览许可");
            if(terms.expiresAt()<=now||terms.expiresAt()-now>30L*24*60*60*1000)throw new IllegalArgumentException("许可有效期须为未来30日以内");
            if(s.query("SELECT COUNT(*) FROM (SELECT 1 FROM logistics_transit_proposal WHERE grantor=? AND approved_revision IS NULL AND expires_at>? LIMIT 20)",row->row.getInt(1),terms.grantingNation(),now).getFirst()>=20)throw new IllegalArgumentException("本国待确认过境草案已达20项，请等待到期");
            if(s.query("SELECT COUNT(*) FROM (SELECT 1 FROM logistics_transit_proposal LIMIT 100000)",row->row.getInt(1)).getFirst()>=100000)throw new IllegalArgumentException("过境草案审计容量已满，请保留数据并维护存储");
            String encoded=TransitPermitCodec.encode(Map.of(terms.grantingNation(),new Permit(terms,Math.addExact(expectedRevision,1),false,actor,now)));
            s.execute("INSERT INTO logistics_transit_proposal(id,route,actor,grantor,expected_revision,terms,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,route,actor,terms.grantingNation(),expectedRevision,encoded,now,proposed.expiresAt());s.audit(actor.toString(),"TRANSIT PROPOSAL "+id+" "+route+" toll="+terms.tollPerUnit()+" expected="+expectedRevision);return proposed;
        });
    }
    public Proposal acknowledge(UUID id,Receipt receipt)throws SQLException{return store.tx(s->{Proposal p=proposal(id).orElseThrow();
        if(!receipt.requestId().equals(id)||receipt.action()!=Action.APPROVE||receipt.expectedRevision()!=p.expectedRevision()||!receipt.permit().terms().equals(p.terms())||!receipt.permit().actor().equals(p.actor()))throw new IllegalArgumentException("过境许可持久回执与草案不符");
        s.execute("UPDATE logistics_transit_proposal SET approved_revision=? WHERE id=?",receipt.permit().revision(),id);if(p.approvedRevision()==null)s.audit(p.actor().toString(),"TRANSIT APPROVED "+id+" revision="+receipt.permit().revision());return proposal(id).orElseThrow();});}
    private Proposal read(ResultSet r)throws SQLException{
        Map<UUID,Permit> terms=TransitPermitCodec.decode(r.getString("terms"));UUID grantor=UUID.fromString(r.getString("grantor"));if(terms.size()!=1||!terms.containsKey(grantor))throw new SQLException("Invalid transit proposal allocation");Permit permit=terms.get(grantor);UUID actor=UUID.fromString(r.getString("actor"));long revision=r.getLong("expected_revision"),created=r.getLong("created_at");if(!permit.actor().equals(actor)||permit.revoked()||permit.revision()!=Math.addExact(revision,1)||permit.updatedAt()!=created)throw new SQLException("Invalid transit proposal identity");
        return new Proposal(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("route")),actor,permit.terms(),revision,created,r.getLong("expires_at"),r.getObject("approved_revision")==null?null:r.getLong("approved_revision"));
    }
}

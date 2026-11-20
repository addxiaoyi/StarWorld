package dev.starworld.integration;

import dev.starworld.integration.CoreExtensionPort.*;
import dev.starworld.persistence.WorldStore;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** StarWorld-owned durable outbox. Use only on the serialized storage worker; no Bukkit calls. */
public final class ResourceOutbox {
    public static final long LEASE_MILLIS=30_000;
    private final WorldStore store;
    private final String namespace;
    public record Entry(String transactionId, String businessKey, UUID cityId, UUID nationId,
                        Map<String,Long> localCost, Map<String,Long> resources, String description,
                        String state, int attempts, long nextAttempt, String lastError) {
        public Entry { localCost=Map.copyOf(localCost);resources=Map.copyOf(resources); }
        public ResourceRequest request(){return new ResourceRequest(transactionId,nationId,resources,description);}
    }
    public record Dispatch(Entry entry,String leaseToken) {}
    public ResourceOutbox(WorldStore store)throws SQLException {
        this.store=Objects.requireNonNull(store);
        namespace=store.tx(s->{
            s.execute("CREATE TABLE IF NOT EXISTS extension_meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            s.execute("INSERT OR IGNORE INTO extension_meta(key,value) VALUES('resource-outbox-namespace',?)",UUID.randomUUID().toString());
            s.execute("CREATE TABLE IF NOT EXISTS resource_outbox (id TEXT PRIMARY KEY,business_key TEXT NOT NULL UNIQUE,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,local_cost TEXT NOT NULL,resources TEXT NOT NULL,description TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0,lease TEXT,last_error TEXT NOT NULL DEFAULT '',created INTEGER NOT NULL,updated INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS resource_outbox_due ON resource_outbox(state,next_attempt)");
            s.execute("CREATE INDEX IF NOT EXISTS resource_outbox_city ON resource_outbox(city,created)");
            return s.query("SELECT value FROM extension_meta WHERE key='resource-outbox-namespace'",r->r.getString(1)).getFirst();
        });
    }
    /** Authorized main-thread caller supplies the current core nation; local identity is checked again. */
    public Entry enqueue(String businessKey,UUID cityId,UUID nationId,Map<String,Long> localCost,
                         Map<String,Long> resources,String actor)throws SQLException {
        if(businessKey==null || businessKey.isBlank() || businessKey.length()>200)throw new IllegalArgumentException("Invalid business key");
        Objects.requireNonNull(cityId);Objects.requireNonNull(nationId);
        Map<String,Long> cost=cost(localCost),coreResources=CoreExtensionPort.resourceAmounts(resources);
        String id="starworld:"+namespace+":"+UUID.nameUUIDFromBytes(businessKey.getBytes(StandardCharsets.UTF_8));
        String description="StarWorld "+businessKey;
        description=description.substring(0,Math.min(200,description.length()));final String note=description;
        return store.tx(s->{
            Optional<Entry> previous=find(id);
            if(previous.isPresent()) {
                Entry entry=previous.get();
                if(!entry.cityId().equals(cityId)||!entry.nationId().equals(nationId)||!entry.localCost().equals(cost)||!entry.resources().equals(coreResources))throw new IllegalArgumentException("Business key reused with different payload");
                return entry;
            }
            long pending=s.query("SELECT COUNT(*) FROM resource_outbox WHERE state NOT IN ('ACKED','CANCELLED')",r->r.getLong(1)).getFirst();
            if(pending>=10_000)throw new IllegalStateException("Resource outbox capacity reached");
            var city=s.view(cityId).orElseThrow(()->new IllegalArgumentException("Unknown extension city"));
            if(!city.nationId().equals(nationId))throw new IllegalArgumentException("City nation changed before resource export");
            for(var e:cost.entrySet())if(city.stock().getOrDefault(e.getKey(),0L)<e.getValue())throw new IllegalArgumentException("Insufficient local stock: "+e.getKey());
            for(var e:cost.entrySet())s.stock(cityId,e.getKey(),city.stock().getOrDefault(e.getKey(),0L)-e.getValue());
            long now=System.currentTimeMillis();
            s.execute("INSERT INTO resource_outbox(id,business_key,city,nation,local_cost,resources,description,state,created,updated) VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",id,businessKey,cityId,nationId,encode(cost),encode(coreResources),note,now,now);
            s.audit(actor,"CORE_EXPORT_PREPARE "+id+" "+cost+" -> "+coreResources);
            return find(id).orElseThrow();
        });
    }
    /** Atomically claims bounded due work. Expired work uses the SAME core transaction id. */
    public List<Dispatch> leaseDue(int limit,long now)throws SQLException {
        if(limit<1 || limit>100 || now<0)throw new IllegalArgumentException("Invalid outbox page");
        return store.tx(s->{
            List<String> ids=s.query("SELECT id FROM resource_outbox WHERE (state IN ('PENDING','RETRY') OR state='IN_FLIGHT') AND next_attempt<=? ORDER BY created,id LIMIT ?",r->r.getString(1),now,limit);
            List<Dispatch> result=new ArrayList<>();
            for(String id:ids){String token=UUID.randomUUID().toString();s.execute("UPDATE resource_outbox SET state='IN_FLIGHT',lease=?,attempts=attempts+1,next_attempt=?,updated=? WHERE id=?",token,Math.addExact(now,LEASE_MILLIS),now,id);result.add(new Dispatch(find(id).orElseThrow(),token));}
            return List.copyOf(result);
        });
    }
    /** Apply only after a main-thread delivery; stale callbacks cannot ACK or re-open a new lease. */
    public boolean finish(Dispatch dispatch,Delivery delivery,long now)throws SQLException {
        Objects.requireNonNull(dispatch);Objects.requireNonNull(delivery);
        if(now<0 || !dispatch.entry().transactionId().equals(delivery.transactionId()))throw new IllegalArgumentException("Delivery identity mismatch");
        return store.tx(s->{
            Entry current=find(delivery.transactionId()).orElseThrow();
            String lease=s.query("SELECT COALESCE(lease,'') FROM resource_outbox WHERE id=?",r->r.getString(1),current.transactionId()).getFirst();
            if(!current.state().equals("IN_FLIGHT") || !lease.equals(dispatch.leaseToken()))return false;
            String state=switch(delivery.status()){case CONFIRMED->"ACKED";case RETRY->"RETRY";case REJECTED->"REJECTED";case CONFLICT->"REVIEW";};
            if(delivery.status()==DeliveryStatus.CONFIRMED && (delivery.receipt()==null || !delivery.receipt().matches(current.request())))state="REVIEW";
            long backoff=Math.min(300_000L,1000L*(1L<<Math.min(8,current.attempts())));
            s.execute("UPDATE resource_outbox SET state=?,lease=NULL,next_attempt=?,last_error=?,updated=? WHERE id=?",state,Math.addExact(now,backoff),delivery.detail(),now,current.transactionId());
            s.audit("core-outbox","CORE_EXPORT_"+state+" "+current.transactionId()+" "+delivery.detail());return true;
        });
    }
    /** Does not refund: an uncertain external grant might already be durable. Reconcile via same id. */
    public void retry(String transactionId,String actor)throws SQLException {
        store.tx(s->{Entry entry=find(transactionId).orElseThrow();if(!Set.of("RETRY","REJECTED","REVIEW").contains(entry.state()))throw new IllegalStateException("Outbox entry cannot be retried in state "+entry.state());
            s.execute("UPDATE resource_outbox SET state='PENDING',next_attempt=0,last_error='',updated=? WHERE id=?",System.currentTimeMillis(),transactionId);
            s.audit(actor,"CORE_EXPORT_RETRY "+transactionId);return null;});
    }
    /** Only an entry which has NEVER left the local database can safely be cancelled/refunded. */
    public void cancelUnsent(String transactionId,String actor)throws SQLException {
        store.tx(s->{Entry entry=find(transactionId).orElseThrow();if(!entry.state().equals("PENDING") || entry.attempts()!=0)throw new IllegalStateException("Dispatched export must be reconciled, never blindly refunded");
            Map<String,Long> stock=s.stock(entry.cityId());for(var e:entry.localCost().entrySet())s.stock(entry.cityId(),e.getKey(),Math.addExact(stock.getOrDefault(e.getKey(),0L),e.getValue()));
            s.execute("UPDATE resource_outbox SET state='CANCELLED',updated=? WHERE id=?",System.currentTimeMillis(),transactionId);s.audit(actor,"CORE_EXPORT_CANCEL "+transactionId);return null;});
    }
    public Optional<Entry> find(String transactionId)throws SQLException {
        return store.query("SELECT * FROM resource_outbox WHERE id=?",r->new Entry(r.getString("id"),r.getString("business_key"),UUID.fromString(r.getString("city")),UUID.fromString(r.getString("nation")),decode(r.getString("local_cost")),decode(r.getString("resources")),r.getString("description"),r.getString("state"),r.getInt("attempts"),r.getLong("next_attempt"),r.getString("last_error")),transactionId).stream().findFirst();
    }
    public List<Entry> entries(int limit)throws SQLException {if(limit<1||limit>100)throw new IllegalArgumentException("Invalid limit");return store.query("SELECT id FROM resource_outbox ORDER BY created DESC LIMIT ?",r->r.getString(1),limit).stream().map(id->{try{return find(id).orElseThrow();}catch(SQLException e){throw new IllegalStateException(e);}}).toList();}
    public List<Entry> cityEntries(UUID city,int limit)throws SQLException{if(limit<1||limit>100)throw new IllegalArgumentException("Invalid limit");return store.query("SELECT id FROM resource_outbox WHERE city=? ORDER BY created DESC,id LIMIT ?",r->r.getString(1),city,limit).stream().map(id->{try{return find(id).orElseThrow();}catch(SQLException e){throw new IllegalStateException(e);}}).toList();}
    private static Map<String,Long> cost(Map<String,Long> source){Objects.requireNonNull(source);if(source.isEmpty()||source.size()>64)throw new IllegalArgumentException("Local cost must not be empty");Map<String,Long> result=new TreeMap<>();source.forEach((k,v)->{if(k==null||!k.matches("[a-z0-9._-]+:[a-z0-9/._-]+")||v==null||v<1||v>1_000_000_000L)throw new IllegalArgumentException("Invalid local cost");result.put(k,v);});return Map.copyOf(result);}
    private static String encode(Map<String,Long> values){return values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("\n"));}
    private static Map<String,Long> decode(String value){Map<String,Long> result=new TreeMap<>();for(String line:value.split("\n")){int split=line.lastIndexOf('=');if(split<1)throw new IllegalStateException("Corrupt outbox amount");result.put(line.substring(0,split),Long.parseLong(line.substring(split+1)));}return Map.copyOf(result);}
}

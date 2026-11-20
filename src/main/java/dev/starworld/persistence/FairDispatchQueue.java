package dev.starworld.persistence;

import java.sql.SQLException;
import java.util.*;

/** Bounded, durable alternation between immediate admissions and already scheduled retries. */
public final class FairDispatchQueue {
    private FairDispatchQueue() {}
    private enum Lane { FRESH, DUE }

    /** Additive StarWorld metadata only; one row per queue, with no financial-row migration. */
    public static void initialize(WorldStore store,String queue)throws SQLException{
        validate(queue);
        store.tx(s->{
            s.execute("CREATE TABLE IF NOT EXISTS starworld_dispatch_cursor(queue TEXT PRIMARY KEY,next_lane TEXT NOT NULL CHECK(next_lane IN ('FRESH','DUE')))");
            s.execute("INSERT OR IGNORE INTO starworld_dispatch_cursor(queue,next_lane) VALUES(?,'DUE')",queue);
            return null;
        });
    }

    /** Caller fetches at most limit rows per disjoint lane and leases the result in the same transaction. */
    public static <T> List<T> select(WorldStore store,String queue,List<T> fresh,List<T> due,int limit)throws SQLException{
        validate(queue);Objects.requireNonNull(fresh);Objects.requireNonNull(due);
        if(limit<1||limit>100||fresh.size()>limit||due.size()>limit)throw new IllegalArgumentException("Invalid bounded dispatch lanes");
        return store.tx(s->{
            Lane next=Lane.valueOf(s.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue=?",r->r.getString(1),queue).getFirst());
            int admitted=0,scheduled=0;List<T> selected=new ArrayList<>(limit);
            while(selected.size()<limit&&(admitted<fresh.size()||scheduled<due.size())){
                boolean useDue=next==Lane.DUE?scheduled<due.size():admitted>=fresh.size();
                if(useDue){selected.add(due.get(scheduled++));next=Lane.FRESH;}
                else{selected.add(fresh.get(admitted++));next=Lane.DUE;}
            }
            if(!selected.isEmpty())s.execute("UPDATE starworld_dispatch_cursor SET next_lane=? WHERE queue=?",next.name(),queue);
            return List.copyOf(selected);
        });
    }
    private static void validate(String queue){if(queue==null||!queue.matches("starworld:[a-z][a-z0-9-]{1,63}"))throw new IllegalArgumentException("Invalid dispatch queue namespace");}
}

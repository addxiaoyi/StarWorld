package dev.starworld.persistence;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FairDispatchQueueTest {
    @TempDir Path temporary;
    static final String A="starworld:resource-export",B="starworld:market-payment";
    @Test void oddBatchCursorPersistsAcrossRestartAndQueuesStayIndependent()throws Exception{
        Path path=temporary.resolve("dispatch.db");
        try(WorldStore store=new WorldStore(path,1000)){
            FairDispatchQueue.initialize(store,A);FairDispatchQueue.initialize(store,B);
            assertEquals(List.of("d1","f1","d2"),FairDispatchQueue.select(store,A,List.of("f1","f2","f3"),List.of("d1","d2","d3"),3));
            assertEquals("FRESH",store.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue=?",r->r.getString(1),A).getFirst());assertEquals("DUE",store.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue=?",r->r.getString(1),B).getFirst());
        }
        try(WorldStore store=new WorldStore(path,1000)){
            FairDispatchQueue.initialize(store,A);FairDispatchQueue.initialize(store,B);
            assertEquals(List.of("f2"),FairDispatchQueue.select(store,A,List.of("f2"),List.of("d3"),1));assertEquals(List.of("d1"),FairDispatchQueue.select(store,B,List.of("f1"),List.of("d1"),1));assertEquals(2,store.query("SELECT COUNT(*) FROM starworld_dispatch_cursor",r->r.getInt(1)).getFirst());
        }
    }
    @Test void emptyLaneIsSkippedAndCandidateAndDispatchBoundsAreEnforced()throws Exception{
        try(WorldStore store=new WorldStore(temporary.resolve("bounds.db"),1000)){
            FairDispatchQueue.initialize(store,A);assertEquals(List.of("f1","f2","f3"),FairDispatchQueue.select(store,A,List.of("f1","f2","f3"),List.of(),3));assertEquals(List.of("d1","f4","d2"),FairDispatchQueue.select(store,A,List.of("f4","f5","f6"),List.of("d1","d2","d3"),3));
            String before=store.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue=?",r->r.getString(1),A).getFirst();assertTrue(FairDispatchQueue.select(store,A,List.of(),List.of(),1).isEmpty());assertEquals(before,store.query("SELECT next_lane FROM starworld_dispatch_cursor WHERE queue=?",r->r.getString(1),A).getFirst());
            assertThrows(IllegalArgumentException.class,()->FairDispatchQueue.select(store,A,List.of("a","b"),List.of(),1));assertThrows(IllegalArgumentException.class,()->FairDispatchQueue.select(store,A,List.of(),List.of(),101));assertThrows(IllegalArgumentException.class,()->FairDispatchQueue.initialize(store,"external-queue"));
        }
    }
}

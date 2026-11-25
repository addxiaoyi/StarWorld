package dev.starworld.market;

import dev.starworld.domain.WorldState.CoreCity;
import dev.starworld.logistics.LogisticsDefinitions;
import dev.starworld.logistics.LogisticsService;
import dev.starworld.market.MarketState.*;
import dev.starworld.persistence.WorldStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketMigrationTest {
    @TempDir Path temp;
    private final UUID seller=UUID.randomUUID(),sellerNation=UUID.randomUUID(),buyer=UUID.randomUUID(),buyerNation=UUID.randomUUID();

    /** Real pre-1.5 schema, deliberately not derived from the new migration code. */
    private WorldStore legacy()throws Exception{
        WorldStore store=new WorldStore(temp.resolve("market.db"),10000);
        store.ensureCity(new CoreCity(seller,sellerNation,"seller","seller nation",1,"test"));
        store.ensureCity(new CoreCity(buyer,buyerNation,"buyer","buyer nation",1,"test"));
        store.execute("CREATE TABLE market_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL,namespace TEXT NOT NULL)");
        store.execute("INSERT INTO market_schema VALUES(1,1,'original-market-namespace')");
        store.execute("CREATE TABLE market_listing(id TEXT PRIMARY KEY,seller_city TEXT NOT NULL REFERENCES city_state(id),seller_nation TEXT NOT NULL,item TEXT NOT NULL,units INTEGER NOT NULL CHECK(units>0),unit_price TEXT NOT NULL,state TEXT NOT NULL,created_period INTEGER NOT NULL,expires_period INTEGER NOT NULL)");
        store.execute("CREATE TABLE market_purchase(id TEXT PRIMARY KEY,listing TEXT NOT NULL REFERENCES market_listing(id),buyer_city TEXT NOT NULL REFERENCES city_state(id),buyer_nation TEXT NOT NULL,seller_city TEXT NOT NULL,seller_nation TEXT NOT NULL,route TEXT NOT NULL,shipment TEXT NOT NULL UNIQUE,transaction_id TEXT NOT NULL UNIQUE,total TEXT NOT NULL,state TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_attempt INTEGER NOT NULL DEFAULT 0,lease TEXT,last_error TEXT NOT NULL DEFAULT '',created_period INTEGER NOT NULL,paid_at TEXT)");
        store.execute("CREATE UNIQUE INDEX market_listing_purchase ON market_purchase(listing) WHERE state<>'CANCELLED'");
        store.execute("CREATE TABLE market_history(listing TEXT PRIMARY KEY REFERENCES market_listing(id),buyer_city TEXT NOT NULL,period INTEGER NOT NULL)");
        return store;
    }
    private UUID lot(WorldStore store,ListingStatus status)throws SQLException{
        UUID id=UUID.randomUUID();store.execute("INSERT INTO market_listing VALUES(?,?,?,'minecraft:bread',20,'1.25',?,1,49)",id,seller,sellerNation,status.name());return id;
    }
    private UUID purchase(WorldStore store,UUID listing,PurchaseStatus status)throws SQLException{
        UUID id=UUID.randomUUID();store.execute("INSERT INTO market_purchase VALUES(?,?,?,?,?,?,?,?,?,'25.00',?,2,321,'original-lease','kept detail',1,'2026-11-20T00:00:00Z')",
                id,listing,buyer,buyerNation,seller,sellerNation,UUID.randomUUID(),UUID.randomUUID(),"original-payment:"+id,status.name());return id;
    }
    private MarketService open(WorldStore store)throws SQLException{return new MarketService(store,new LogisticsService(store,LogisticsDefinitions.defaults()),MarketDefinitions.defaults());}

    @Test void oldWholeLotsPaymentsHistoryAndNamespaceSurviveUpgradeAndRestart()throws Exception{
        UUID available,locked,shipped,cancelled,expired,pending,paid,abandoned;
        try(WorldStore store=legacy()){
            available=lot(store,ListingStatus.AVAILABLE);locked=lot(store,ListingStatus.LOCKED);shipped=lot(store,ListingStatus.SHIPPED);
            cancelled=lot(store,ListingStatus.CANCELLED);expired=lot(store,ListingStatus.EXPIRED);
            abandoned=purchase(store,available,PurchaseStatus.CANCELLED);pending=purchase(store,locked,PurchaseStatus.IN_FLIGHT);paid=purchase(store,shipped,PurchaseStatus.SHIPPED);
            store.execute("INSERT INTO market_history VALUES(?,?,7)",shipped,buyer);
            List<String> before=store.query("SELECT id||'|'||transaction_id||'|'||shipment||'|'||total||'|'||state||'|'||lease||'|'||attempts||'|'||next_attempt FROM market_purchase ORDER BY id",r->r.getString(1));
            MarketService market=open(store);
            assertEquals(before,store.query("SELECT id||'|'||transaction_id||'|'||shipment||'|'||total||'|'||state||'|'||lease||'|'||attempts||'|'||next_attempt FROM market_purchase ORDER BY id",r->r.getString(1)));
            assertEquals(4,store.query("SELECT version FROM market_schema",r->r.getInt(1)).getFirst());
            assertEquals("original-market-namespace",store.query("SELECT namespace FROM market_schema",r->r.getString(1)).getFirst());
            assertEquals(20,market.listing(available).orElseThrow().availableUnits());assertEquals(20,market.listing(locked).orElseThrow().reservedUnits());
            assertEquals(20,market.listing(shipped).orElseThrow().shippedUnits());assertEquals(20,market.listing(cancelled).orElseThrow().returnedUnits());assertEquals(20,market.listing(expired).orElseThrow().returnedUnits());
            assertEquals(20,market.purchase(pending).orElseThrow().units());assertEquals(20,market.purchase(abandoned).orElseThrow().units());
            for(UUID id:List.of(pending,paid,abandoned)){Purchase migrated=market.purchase(id).orElseThrow();assertEquals(migrated.total(),migrated.goodsTotal());assertEquals(new BigDecimal("0.00"),migrated.taxAmount());assertNull(migrated.taxQuote());}
            assertEquals(0,store.query("SELECT COUNT(*) FROM market_quote",r->r.getInt(1)).getFirst());
            PriceHistory history=market.history(null,10,0).getFirst();assertEquals(paid,history.purchaseId());assertEquals(shipped,history.listingId());assertEquals(20,history.units());assertEquals(new BigDecimal("1.25"),history.unitPrice());
            assertTrue(store.query("PRAGMA foreign_key_check",r->r.getString(1)).isEmpty());
        }
        try(WorldStore store=new WorldStore(temp.resolve("market.db"),10000)){
            MarketService market=open(store);assertEquals(1,market.history(null,10,0).size());assertEquals(20,market.listing(locked).orElseThrow().reservedUnits());
            assertEquals("original-payment:"+pending,market.purchase(pending).orElseThrow().transactionId());
        }
    }
    @Test void failedMigrationRollsBackDdlBalancesHistoryAndVersion()throws Exception{
        try(WorldStore store=legacy()){
            UUID listing=lot(store,ListingStatus.SHIPPED);UUID paid=purchase(store,listing,PurchaseStatus.SHIPPED);store.execute("INSERT INTO market_history VALUES(?,?,7)",listing,buyer);
            store.execute("CREATE TRIGGER reject_market_upgrade BEFORE UPDATE OF version ON market_schema BEGIN SELECT RAISE(ABORT,'migration crash'); END");
            assertThrows(SQLException.class,()->open(store));assertEquals(1,store.query("SELECT version FROM market_schema",r->r.getInt(1)).getFirst());
            assertFalse(store.query("PRAGMA table_info(market_listing)",r->r.getString("name")).contains("available_units"));
            assertFalse(store.query("PRAGMA table_info(market_purchase)",r->r.getString("name")).contains("units"));
            assertEquals(List.of("listing","buyer_city","period"),store.query("PRAGMA table_info(market_history)",r->r.getString("name")));
            assertEquals(1,store.query("SELECT COUNT(*) FROM market_history",r->r.getInt(1)).getFirst());
            store.execute("DROP TRIGGER reject_market_upgrade");assertEquals(paid,open(store).history(null,10,0).getFirst().purchaseId());
        }
    }
    @Test void orphanReservationOrUnmatchedHistoryRejectsUpgradeWithoutChangingOriginalTables()throws Exception{
        try(WorldStore store=legacy()){
            UUID listing=lot(store,ListingStatus.LOCKED);
            assertThrows(SQLException.class,()->open(store));assertEquals(1,store.query("SELECT version FROM market_schema",r->r.getInt(1)).getFirst());
            store.execute("UPDATE market_listing SET state='AVAILABLE' WHERE id=?",listing);store.execute("INSERT INTO market_history VALUES(?,?,1)",listing,buyer);
            assertThrows(SQLException.class,()->open(store));assertFalse(store.query("PRAGMA table_info(market_listing)",r->r.getString("name")).contains("available_units"));
            assertEquals(1,store.query("SELECT COUNT(*) FROM market_history",r->r.getInt(1)).getFirst());
        }
    }
    @Test void unknownLegacyPurchaseStateRejectsMigrationBeforeAnyColumnIsAdded()throws Exception{
        try(WorldStore store=legacy()){
            UUID listing=lot(store,ListingStatus.LOCKED),purchase=purchase(store,listing,PurchaseStatus.PENDING);
            store.execute("UPDATE market_purchase SET state='UNKNOWN' WHERE id=?",purchase);
            assertThrows(SQLException.class,()->open(store));
            assertEquals(1,store.query("SELECT version FROM market_schema",r->r.getInt(1)).getFirst());
            assertFalse(store.query("PRAGMA table_info(market_listing)",r->r.getString("name")).contains("available_units"));
            assertEquals("UNKNOWN",store.query("SELECT state FROM market_purchase WHERE id=?",r->r.getString(1),purchase).getFirst());
        }
    }
}

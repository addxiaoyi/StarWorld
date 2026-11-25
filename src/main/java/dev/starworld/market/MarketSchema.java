package dev.starworld.market;

import dev.starworld.persistence.WorldStore;
import java.sql.SQLException;

/** Transactional upgrade from whole-lot escrow. Called inside the market initialization transaction. */
final class MarketSchema {
    private MarketSchema() {}

    static void upgrade(WorldStore store,int version) throws SQLException {
        if(version==1){
            if(!store.query("SELECT id FROM market_purchase WHERE state NOT IN ('PENDING','IN_FLIGHT','RETRY','REJECTED','REVIEW','PAID','SHIPPED','CANCELLED') LIMIT 1",r->r.getString(1)).isEmpty())
                throw new SQLException("Unknown legacy market purchase state; migration refused");
            // A legacy lot may have cancelled attempts, but exactly one live purchase owns a locked/shipped lot.
            String invalid="SELECT l.id FROM market_listing l WHERE l.state NOT IN ('AVAILABLE','LOCKED','SHIPPED','CANCELLED','EXPIRED') "
                    +"OR (SELECT COUNT(*) FROM market_purchase p WHERE p.listing=l.id AND p.state<>'CANCELLED') "
                    +"<> CASE WHEN l.state IN ('LOCKED','SHIPPED') THEN 1 ELSE 0 END "
                    +"OR EXISTS(SELECT 1 FROM market_purchase p WHERE p.listing=l.id AND p.state<>'CANCELLED' "
                    +"AND ((p.state='SHIPPED')<>(l.state='SHIPPED') OR p.seller_city<>l.seller_city OR p.seller_nation<>l.seller_nation)) LIMIT 1";
            if(!store.query(invalid,r->r.getString(1)).isEmpty())throw new SQLException("Inconsistent legacy market reservation; migration refused");
            for(String column:new String[]{"available_units","reserved_units","shipped_units","returned_units"})
                store.execute("ALTER TABLE market_listing ADD COLUMN "+column+" INTEGER NOT NULL DEFAULT 0 CHECK("+column+">=0)");
            store.execute("UPDATE market_listing SET available_units=CASE WHEN state='AVAILABLE' THEN units ELSE 0 END,"
                    +"reserved_units=CASE WHEN state='LOCKED' THEN units ELSE 0 END,"
                    +"shipped_units=CASE WHEN state='SHIPPED' THEN units ELSE 0 END,"
                    +"returned_units=CASE WHEN state IN ('CANCELLED','EXPIRED') THEN units ELSE 0 END");
            store.execute("ALTER TABLE market_purchase ADD COLUMN units INTEGER NOT NULL DEFAULT 1 CHECK(units>0)");
            store.execute("ALTER TABLE market_purchase ADD COLUMN buy_all INTEGER NOT NULL DEFAULT 1 CHECK(buy_all IN (0,1))");
            store.execute("UPDATE market_purchase SET units=(SELECT units FROM market_listing WHERE id=listing)");
            store.execute("CREATE TABLE market_history_v2(purchase TEXT PRIMARY KEY REFERENCES market_purchase(id),listing TEXT NOT NULL REFERENCES market_listing(id),buyer_city TEXT NOT NULL,period INTEGER NOT NULL)");
            store.execute("INSERT INTO market_history_v2(purchase,listing,buyer_city,period) SELECT p.id,h.listing,h.buyer_city,h.period FROM market_history h JOIN market_purchase p ON p.listing=h.listing AND p.state='SHIPPED' AND p.buyer_city=h.buyer_city");
            long oldCount=store.query("SELECT COUNT(*) FROM market_history",r->r.getLong(1)).getFirst();
            long newCount=store.query("SELECT COUNT(*) FROM market_history_v2",r->r.getLong(1)).getFirst();
            if(oldCount!=newCount)throw new SQLException("Legacy market history does not match shipped purchases");
            store.execute("DROP TABLE market_history");
            store.execute("ALTER TABLE market_history_v2 RENAME TO market_history");
            store.execute("DROP INDEX IF EXISTS market_listing_purchase");
            store.execute("UPDATE market_schema SET version=2 WHERE id=1");
        }
        if(version<3){
            store.execute("DROP INDEX IF EXISTS market_purchase_open_buyer");
            store.execute("DROP INDEX IF EXISTS market_purchase_open_seller");
            store.execute("UPDATE market_schema SET version=3 WHERE id=1");
        }
        store.execute("CREATE TABLE IF NOT EXISTS market_quote(id TEXT PRIMARY KEY,listing TEXT NOT NULL REFERENCES market_listing(id),route TEXT NOT NULL,buyer_city TEXT NOT NULL REFERENCES city_state(id),buyer_nation TEXT NOT NULL,seller_nation TEXT NOT NULL,actor TEXT NOT NULL,units INTEGER NOT NULL CHECK(units>0),goods_total TEXT NOT NULL,tax_amount TEXT NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,purchase TEXT UNIQUE REFERENCES market_purchase(id))");
        store.execute("CREATE INDEX IF NOT EXISTS market_quote_open ON market_quote(buyer_city,expires_at) WHERE purchase IS NULL");
        store.execute("CREATE INDEX IF NOT EXISTS market_quote_expiry ON market_quote(expires_at) WHERE purchase IS NULL");
        if(version<4){
            store.execute("ALTER TABLE market_purchase ADD COLUMN goods_total TEXT NOT NULL DEFAULT '0.00'");
            store.execute("ALTER TABLE market_purchase ADD COLUMN tax_amount TEXT NOT NULL DEFAULT '0.00'");
            store.execute("ALTER TABLE market_purchase ADD COLUMN tax_quote TEXT REFERENCES market_quote(id)");
            store.execute("UPDATE market_purchase SET goods_total=total");
            store.execute("UPDATE market_schema SET version=4 WHERE id=1");
        }
        store.execute("CREATE UNIQUE INDEX IF NOT EXISTS market_purchase_tax_quote ON market_purchase(tax_quote) WHERE tax_quote IS NOT NULL");
        if(version<5){
            store.execute("ALTER TABLE market_quote ADD COLUMN transit_terms TEXT NOT NULL DEFAULT ''");
            store.execute("ALTER TABLE market_purchase ADD COLUMN transit_terms TEXT NOT NULL DEFAULT ''");
            store.execute("UPDATE market_schema SET version=5 WHERE id=1");
        }
        store.execute("CREATE INDEX IF NOT EXISTS market_listing_purchase ON market_purchase(listing,state)");
        store.execute("CREATE INDEX IF NOT EXISTS market_purchase_open_buyer ON market_purchase(buyer_city) WHERE state NOT IN ('SHIPPED','REFUNDED','CANCELLED')");
        store.execute("CREATE INDEX IF NOT EXISTS market_purchase_open_seller ON market_purchase(seller_city) WHERE state NOT IN ('SHIPPED','REFUNDED','CANCELLED')");
        store.execute("CREATE INDEX IF NOT EXISTS market_listing_open ON market_listing(seller_city) WHERE available_units>0 OR reserved_units>0");
        store.execute("CREATE INDEX IF NOT EXISTS market_history_period ON market_history(period,purchase)");
        // Keep conservation enforced even if a future write path forgets to use the balance helper.
        for(String event:new String[]{"INSERT","UPDATE"}){
            store.execute("CREATE TRIGGER IF NOT EXISTS market_escrow_"+event.toLowerCase(java.util.Locale.ROOT)
                    +" BEFORE "+event+" ON market_listing WHEN NEW.available_units+NEW.reserved_units+NEW.shipped_units+NEW.returned_units<>NEW.units "
                    +"OR (NEW.state='AVAILABLE' AND NEW.available_units=0) "
                    +"OR (NEW.state<>'AVAILABLE' AND NEW.available_units<>0) "
                    +"OR (NEW.state='LOCKED' AND NEW.reserved_units=0) "
                    +"OR (NEW.state='SHIPPED' AND (NEW.reserved_units<>0 OR NEW.shipped_units<>NEW.units)) "
                    +"BEGIN SELECT RAISE(ABORT,'market escrow conservation'); END");
        }
    }
}

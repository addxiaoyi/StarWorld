package dev.starworld.persistence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.bukkit.configuration.ConfigurationSection;

/** Bounded storage maintenance. Payment, inventory and gameplay idempotency receipts are never deleted. */
public final class LedgerMaintenance {
    private final WorldStore store;
    private final Settings settings;
    public record Settings(int auditDays,int retainPeriods,int batchRows) {
        public Settings { if(auditDays<1||auditDays>3650||retainPeriods<1000||retainPeriods>1_000_000||batchRows<1||batchRows>1000)throw new IllegalArgumentException("Invalid maintenance settings"); }
        public static Settings load(ConfigurationSection config){return new Settings(config.getInt("maintenance.audit-days",30),config.getInt("maintenance.retain-periods",1000),config.getInt("maintenance.batch-rows",100));}
    }
    public record Result(int auditsArchived,int settlementReportsPruned,int facilityPeriodsPruned,long period) {}
    public record Archive(long id,long firstAudit,long lastAudit,int rows,String sha256,int bytes) {}
    public record Audit(long id,long at,String actor,String action) {}
    public LedgerMaintenance(WorldStore store,Settings settings)throws SQLException {
        this.store=Objects.requireNonNull(store);this.settings=Objects.requireNonNull(settings);
        store.tx(s->{s.execute("CREATE TABLE IF NOT EXISTS audit_archive(id INTEGER PRIMARY KEY AUTOINCREMENT,first_audit INTEGER NOT NULL UNIQUE,last_audit INTEGER NOT NULL,rows INTEGER NOT NULL,sha256 TEXT NOT NULL,payload BLOB NOT NULL,created INTEGER NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS audit_retention ON audit(at,id)");s.execute("CREATE INDEX IF NOT EXISTS settlement_retention ON settlement(period,city)");
            s.execute("CREATE INDEX IF NOT EXISTS evolution_facility_retention ON evolution_facility(period,city)");return null;});
    }
    public Result sweep(long period,long now)throws SQLException {
        if(period<0||now<0)throw new IllegalArgumentException("Invalid maintenance clock");
        long before=Math.max(0,period-settings.retainPeriods()),age=Math.max(0,now-settings.auditDays()*86_400_000L);
        return store.tx(s->{
            List<Audit> audits=s.query("SELECT id,at,actor,action FROM audit WHERE at<? ORDER BY at,id LIMIT ?",r->new Audit(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4)),age,settings.batchRows());
            if(!audits.isEmpty()){
                byte[] payload=encode(audits);long first=audits.stream().mapToLong(Audit::id).min().orElseThrow(),last=audits.stream().mapToLong(Audit::id).max().orElseThrow();
                s.execute("INSERT INTO audit_archive(first_audit,last_audit,rows,sha256,payload,created) VALUES(?,?,?,?,?,?)",first,last,audits.size(),hash(payload),payload,now);
                for(Audit audit:audits)s.execute("DELETE FROM audit WHERE id=?",audit.id());
            }
            record Settlement(String city,long period){}
            var receipts=s.query("SELECT city,period FROM settlement WHERE period<? ORDER BY period,city LIMIT ?",r->new Settlement(r.getString(1),r.getLong(2)),before,settings.batchRows());
            for(var row:receipts)s.execute("DELETE FROM settlement WHERE city=? AND period=?",row.city(),row.period());
            record Facility(String city,String building,long period){}
            var facilities=s.query("SELECT f.city,f.building,f.period FROM evolution_facility f JOIN city_state c ON c.id=f.city WHERE f.period<? AND f.period<c.last_period ORDER BY f.period,f.city,f.building LIMIT ?",r->new Facility(r.getString(1),r.getString(2),r.getLong(3)),before,settings.batchRows());
            for(var row:facilities)s.execute("DELETE FROM evolution_facility WHERE city=? AND building=? AND period=?",row.city(),row.building(),row.period());
            return new Result(audits.size(),receipts.size(),facilities.size(),period);
        });
    }
    public List<Archive> archives(long after,int limit)throws SQLException {
        if(after<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid archive page");
        return store.query("SELECT id,first_audit,last_audit,rows,sha256,LENGTH(payload) FROM audit_archive WHERE id>? ORDER BY id LIMIT ?",r->new Archive(r.getLong(1),r.getLong(2),r.getLong(3),r.getInt(4),r.getString(5),r.getInt(6)),after,limit);
    }
    public List<Audit> readArchive(long id)throws SQLException {
        return store.tx(s->{var row=s.query("SELECT sha256,payload,rows FROM audit_archive WHERE id=?",r->new Object[]{r.getString(1),r.getBytes(2),r.getInt(3)},id);if(row.isEmpty())throw new IllegalArgumentException("Unknown audit archive");
            byte[] payload=(byte[])row.getFirst()[1];if(!hash(payload).equals(row.getFirst()[0]))throw new SQLException("Audit archive hash mismatch");return decode(payload,(int)row.getFirst()[2]);});
    }
    private static byte[] encode(List<Audit> rows)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(bytes))){out.writeInt(1);out.writeInt(rows.size());for(Audit row:rows){out.writeLong(row.id());out.writeLong(row.at());write(out,row.actor());write(out,row.action());}}return bytes.toByteArray();
    }
    private static void write(DataOutputStream out,String text)throws IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static List<Audit> decode(byte[] payload,int expected)throws IOException {
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))){if(in.readInt()!=1||in.readInt()!=expected||expected<1||expected>1000)throw new IOException("Invalid audit archive format");List<Audit> rows=new ArrayList<>();for(int i=0;i<expected;i++)rows.add(new Audit(in.readLong(),in.readLong(),read(in),read(in)));if(in.read()!=-1)throw new IOException("Trailing archive bytes");return List.copyOf(rows);}
    }
    private static String read(DataInputStream in)throws IOException{int length=in.readInt();if(length<0||length>4_000_000)throw new IOException("Invalid archived text length");byte[] bytes=in.readNBytes(length);if(bytes.length!=length)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}

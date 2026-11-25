package dev.starworld.evolution;

import dev.starworld.domain.*;
import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RegionAtlasTest {
    @TempDir Path temp;
    final UUID world=UUID.nameUUIDFromBytes("atlas-world".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Definitions definitions(){return new Definitions(300,64,20,100000,0,
            Map.of("plains",new Definitions.Terrain("plains","平原",1.3,1,.9,Map.of("fertility",100L,"ore",50L),Map.of("fertility",5L))),
            Map.of("agrarian",new Definitions.Civilization("agrarian","农耕",Map.of()),"industrial",new Definitions.Civilization("industrial","工业",Map.of())),
            Map.of("farm",new Definitions.BuildingType("farm","农场","COMPOSTER",Map.of()),"workshop",new Definitions.BuildingType("workshop","工坊","BLAST_FURNACE",Map.of())),List.of(),Map.of());}
    CoreCity city(){return new CoreCity(UUID.randomUUID(),UUID.randomUUID(),"城市","国家",1,"fixture");}
    void cultureSchema(WorldStore s,Definitions d)throws Exception{new EvolutionService(s,d,new EvolutionDefinitions(Map.of("balanced",new EvolutionDefinitions.Plan("均衡",Map.of())),Map.of(),6,100,1,2,Map.of()));}
    Building add(WorldStore s,Definitions d,CoreCity c,String type,int x,int z)throws Exception{s.ensureCity(c);Building b=new Building(UUID.randomUUID(),c.cityId(),c.nationId(),type,new Site(world,"atlas-world",x,64,z),1);s.addBuilding(b,"plains",d);return b;}

    @Test void resourcesAndCultureAreReadOncePerRegisteredRegionWithoutAdvancingRecoveryOrAging()throws Exception {
        Definitions d=definitions();try(WorldStore s=new WorldStore(temp.resolve("atlas.db"),100000)){cultureSchema(s,d);CoreCity c=city();Building farm=add(s,d,c,"farm",1,1);add(s,d,c,"workshop",2,2);add(s,d,c,"farm",17,1);
            s.reserve(farm.site().regionKey(),"ore",7);s.execute("INSERT INTO region_culture(region,tradition,influence) VALUES(?,?,?)",farm.site().regionKey(),"industrial",9);
            RegionAtlas atlas=new RegionAtlas(s,d);var summary=atlas.summary(c.cityId());assertEquals(2,summary.regions());assertEquals(3,summary.facilities());assertEquals(200,summary.reserves().get("fertility"));assertEquals(57,summary.reserves().get("ore"));assertEquals(100,summary.initialCapacities().get("ore"));assertEquals(10,summary.regeneration().get("fertility"));
            var page=atlas.page(c.cityId(),null,64);assertEquals(2,page.regions().size());var first=page.regions().stream().filter(r->r.id().equals(farm.site().regionKey())).findFirst().orElseThrow();assertEquals(2,first.facilities().size());assertEquals(1.3,first.agriculture());assertEquals(.9,first.defense());assertEquals(9,first.culture().getFirst().influence());assertEquals(-1,first.lastRecoveryPeriod());
            assertEquals(page,atlas.page(c.cityId(),null,64));assertEquals(-1,s.query("SELECT last_regen FROM region WHERE id=?",r->r.getLong(1),farm.site().regionKey()).getFirst());assertEquals(0,s.query("SELECT COUNT(*) FROM culture_decay",r->r.getInt(1)).getFirst());
            assertThrows(UnsupportedOperationException.class,()->first.reserves().clear());assertThrows(UnsupportedOperationException.class,()->first.facilities().clear());assertThrows(UnsupportedOperationException.class,()->summary.terrains().clear());
        }
    }
    @Test void keysetPagesPreserveNegativeChunkCoordinatesAndNeverLeakAnotherCity()throws Exception {
        Definitions d=definitions();try(WorldStore s=new WorldStore(temp.resolve("pages.db"),100000)){cultureSchema(s,d);CoreCity c=city(),foreign=city();add(s,d,c,"farm",-1,-17);add(s,d,c,"farm",32,16);add(s,d,c,"farm",160,16);add(s,d,foreign,"farm",320,32);RegionAtlas atlas=new RegionAtlas(s,d);
            Set<String> all=new HashSet<>();String cursor="";int count=0;do{var page=atlas.page(c.cityId(),cursor,1);assertEquals(1,page.regions().size());var region=page.regions().getFirst();assertTrue(all.add(region.id()));if(region.chunkX()==-1)assertEquals(-2,region.chunkZ());cursor=page.nextCursor();count++;assertTrue(count<=3);}while(!cursor.isEmpty());assertEquals(3,count);assertEquals(3,atlas.summary(c.cityId()).regions());
            assertThrows(IllegalArgumentException.class,()->atlas.page(c.cityId(),"not-a-region",1));assertThrows(IllegalArgumentException.class,()->atlas.page(c.cityId(),"",65));assertThrows(IllegalArgumentException.class,()->atlas.page(c.cityId(),"",0));assertThrows(java.sql.SQLException.class,()->atlas.summary(UUID.randomUUID()));
        }
    }
    @Test void transferredAndDissolvedCitiesDoNotExposeFormerNationFacilitiesButKeepHistory()throws Exception {
        Definitions d=definitions();Path db=temp.resolve("authority.db");CoreCity c=city();Building old;
        try(WorldStore s=new WorldStore(db,100000)){cultureSchema(s,d);old=add(s,d,c,"farm",1,1);RegionAtlas atlas=new RegionAtlas(s,d);assertEquals(1,atlas.summary(c.cityId()).regions());
            CoreCity transferred=new CoreCity(c.cityId(),UUID.randomUUID(),c.cityName(),"新国",1,"current snapshot");s.ensureCity(transferred);assertEquals(0,atlas.summary(c.cityId()).regions());assertEquals(transferred.nationId(),atlas.summary(c.cityId()).nation());assertEquals(1,s.buildings(c.cityId()).size());
            s.ensureCity(c);assertEquals(1,atlas.summary(c.cityId()).regions());s.invalidateCity(c.cityId());assertFalse(atlas.page(c.cityId(),"",10).authorityActive());assertEquals(0,atlas.summary(c.cityId()).facilities());
        }
        try(WorldStore s=new WorldStore(db,100000)){cultureSchema(s,d);RegionAtlas atlas=new RegionAtlas(s,d);assertFalse(atlas.summary(c.cityId()).authorityActive());assertTrue(atlas.page(c.cityId(),"",10).regions().isEmpty());assertEquals(1,s.buildings(c.cityId()).size());assertEquals(50,s.region(old.site().regionKey()).reserves().get("ore"));}
    }

    @Test void maximumSurveyedAtlasStaysPagedAndDoesNotInventFacilities()throws Exception {
        Definitions d=definitions();CoreCity c=city();try(WorldStore s=new WorldStore(temp.resolve("surveyed-atlas.db"),100000)){cultureSchema(s,d);
            s.ensureCity(c);s.stock(c.cityId(),"minecraft:paper",RegionAtlas.MAX_REGIONS_PER_CITY*8L);
            for(int i=0;i<RegionAtlas.MAX_REGIONS_PER_CITY;i++)s.surveyRegion(c,new Site(world,"atlas-world",i*16,64,-16),"plains",d,"manager");
            RegionAtlas atlas=new RegionAtlas(s,d);Set<String> ids=new HashSet<>();String cursor="";int pages=0;
            do{var page=atlas.page(c.cityId(),cursor,RegionAtlas.MAX_PAGE_SIZE);assertTrue(page.regions().size()<=RegionAtlas.MAX_PAGE_SIZE);for(var region:page.regions()){assertTrue(ids.add(region.id()));assertTrue(region.facilities().isEmpty());assertEquals(-1,region.chunkZ());}cursor=page.nextCursor();pages++;assertTrue(pages<=2);}while(!cursor.isEmpty());
            assertEquals(RegionAtlas.MAX_REGIONS_PER_CITY,ids.size());assertEquals(RegionAtlas.MAX_REGIONS_PER_CITY,atlas.summary(c.cityId()).regions());assertEquals(0,atlas.summary(c.cityId()).facilities());
        }
    }
    @Test void groupingAndMapKeepOriginalResourceRowsAndObserveNationChangesAcrossRestart()throws Exception {
        Definitions d=definitions();CoreCity c=city(),foreign=city();Path db=temp.resolve("map.db");RegionalMap.DistrictReport report;
        try(WorldStore s=new WorldStore(db,100000)){
            cultureSchema(s,d);add(s,d,c,"farm",-16,-16);add(s,d,c,"workshop",0,-16);add(s,d,c,"farm",0,0);add(s,d,foreign,"farm",16,0);
            RegionAtlas atlas=new RegionAtlas(s,d);var reserves=s.query("SELECT region||resource||amount FROM reserve ORDER BY region,resource",r->r.getString(1));
            var stock=s.stock(c.cityId());report=atlas.districts(c.cityId());assertEquals(1,report.districts().size());assertEquals(3,report.districts().getFirst().regions().size());assertEquals(3,report.districts().getFirst().facilities());assertEquals(150,report.districts().getFirst().reserves().get("ore"));
            var map=atlas.map(c.cityId(),world,0,0);assertEquals(List.of(12,13,22),map.cells().stream().map(RegionalMap.Cell::slot).toList());
            assertEquals(report.districts(),map.districts());assertEquals(reserves,s.query("SELECT region||resource||amount FROM reserve ORDER BY region,resource",r->r.getString(1)));assertEquals(stock,s.stock(c.cityId()));
            assertTrue(s.query("SELECT id FROM region WHERE last_regen<>-1",r->r.getString(1)).isEmpty());
        }
        try(WorldStore s=new WorldStore(db,100000)){
            cultureSchema(s,d);RegionAtlas atlas=new RegionAtlas(s,d);assertEquals(report,atlas.districts(c.cityId()));
            CoreCity transferred=new CoreCity(c.cityId(),UUID.randomUUID(),c.cityName(),"new nation",1,"snapshot");s.ensureCity(transferred);
            assertTrue(atlas.districts(c.cityId()).districts().isEmpty());assertTrue(atlas.map(c.cityId(),world,0,0).cells().isEmpty());
            s.ensureCity(c);assertEquals(report,atlas.districts(c.cityId()));s.invalidateCity(c.cityId());assertFalse(atlas.map(c.cityId(),world,0,0).authorityActive());assertTrue(atlas.districts(c.cityId()).districts().isEmpty());
        }
    }
}

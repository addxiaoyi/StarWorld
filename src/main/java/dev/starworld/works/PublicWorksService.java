package dev.starworld.works;

import dev.starworld.domain.WorldState.*;
import dev.starworld.persistence.WorldStore;
import dev.starworld.works.PublicWorksState.*;
import java.sql.*;
import java.util.*;

/**
 * Trusted extension repository: the runtime captures live core ownership and physical facilities.
 * cycle MUST run inside atomicSettlement around the entire settlement, before Simulation reads stock.
 * completed runs after normal city evolution and before the final settlement receipt. No world scans.
 */
public final class PublicWorksService {
    private final WorldStore store;
    private final PublicWorksDefinitions configuration;

    public PublicWorksService(WorldStore store,PublicWorksDefinitions configuration)throws SQLException{
        this.store=Objects.requireNonNull(store);this.configuration=Objects.requireNonNull(configuration);
        atomic(s->{
            s.execute("CREATE TABLE IF NOT EXISTS public_works_schema(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL)");
            s.execute("INSERT OR IGNORE INTO public_works_schema(id,version) VALUES(1,1)");
            if(s.query("SELECT version FROM public_works_schema WHERE id=1",r->r.getInt(1)).getFirst()!=1)throw new SQLException("Unsupported public works schema");
            s.execute("CREATE TABLE IF NOT EXISTS public_works(request_id TEXT PRIMARY KEY,city TEXT NOT NULL REFERENCES city_state(id),nation TEXT NOT NULL,definition_id TEXT NOT NULL,name TEXT NOT NULL,build_periods INTEGER NOT NULL,upfront TEXT NOT NULL,construction TEXT NOT NULL,upkeep TEXT NOT NULL,facilities TEXT NOT NULL,terrains TEXT NOT NULL,plans TEXT NOT NULL,civilizations TEXT NOT NULL,knowledge_threshold INTEGER NOT NULL,culture_threshold INTEGER NOT NULL,multipliers TEXT NOT NULL,requested_period INTEGER NOT NULL,effective_period INTEGER NOT NULL,progress INTEGER NOT NULL,last_build_period INTEGER NOT NULL,completed_period INTEGER NOT NULL,last_operation_period INTEGER NOT NULL,last_operation_paid INTEGER NOT NULL,last_construction_attempt_period INTEGER NOT NULL,start_plan TEXT NOT NULL,start_civilization TEXT NOT NULL,start_knowledge INTEGER NOT NULL,start_culture INTEGER NOT NULL,UNIQUE(city,nation))");
            s.execute("CREATE INDEX IF NOT EXISTS public_works_city ON public_works(city,requested_period,request_id)");
            s.execute("CREATE TABLE IF NOT EXISTS public_works_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,request_id TEXT NOT NULL REFERENCES public_works(request_id),city TEXT NOT NULL,period INTEGER NOT NULL,kind TEXT NOT NULL,detail TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS public_works_history_city ON public_works_history(city,sequence)");
            return null;
        });
    }
    public PublicWorksDefinitions configuration(){return configuration;}
    /**
     * Enclose cycle creation AND the entire Simulation call, including all other hooks and finish.
     * A surrounding caller may handle failure without committing any part of this settlement.
     * The callback still uses the existing store and its existing authority/duplicate-period guards.
     */
    public <T> T atomicSettlement(WorldStore.Work<T> work)throws SQLException{return atomic(Objects.requireNonNull(work));}
    public Optional<Project> project(UUID requestId)throws SQLException{return store.query("SELECT * FROM public_works WHERE request_id=?",this::read,requestId).stream().findFirst();}
    public Optional<Project> project(UUID city,UUID nation)throws SQLException{return store.query("SELECT * FROM public_works WHERE city=? AND nation=?",this::read,city,nation).stream().findFirst();}
    public List<Project> projects(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return List.copyOf(store.query("SELECT * FROM public_works WHERE city=? ORDER BY requested_period DESC,request_id LIMIT ? OFFSET ?",this::read,city,limit,offset));}
    public List<History> history(UUID city,int limit,int offset)throws SQLException{page(limit,offset);return List.copyOf(store.query("SELECT request_id,period,kind,detail FROM public_works_history WHERE city=? ORDER BY sequence DESC LIMIT ? OFFSET ?",r->new History(UUID.fromString(r.getString(1)),r.getLong(2),r.getString(3),r.getString(4)),city,limit,offset));}

    /** Does not initialize evolution state, reserve a slot, consume stock, or advance a watermark. */
    public List<Feasibility> inspect(CoreCity current,CityView before,long period,Set<UUID> verified)throws SQLException{
        Objects.requireNonNull(current);Objects.requireNonNull(before);Set<UUID> evidence=evidence(verified);period(period);
        return store.tx(s->{
            Optional<CityView> found=s.view(current.cityId());CityView city=found.orElse(null);
            String authority=city==null||!before.id().equals(current.cityId())||!before.nationId().equals(current.nationId())||!authorized(current,city)?"Current city authority is not verified":period<city.lastPeriod()?"Request period is stale":"";
            boolean occupied=city!=null&&project(current.cityId(),current.nationId()).isPresent();List<Feasibility> result=new ArrayList<>();
            for(ProjectDefinition definition:configuration.projects().values().stream().sorted(Comparator.comparing(ProjectDefinition::id)).toList()){
                String reason=!authority.isEmpty()?authority:occupied?"This city's national public works slot is already allocated":requirements(city,current,definition,evidence);
                Map<String,Long> missing=city==null?definition.upfrontCosts():missing(city.stock(),definition.upfrontCosts());
                if(reason.isEmpty()&&!missing.isEmpty())reason="Insufficient upfront physical stock";
                result.add(new Feasibility(definition.id(),definition.name(),reason.isEmpty(),reason,missing));
            }
            return List.copyOf(result);
        });
    }

    public Project start(UUID requestId,CoreCity current,String definitionId,long period,Set<UUID> verified,String actor)throws SQLException{
        Objects.requireNonNull(requestId);Objects.requireNonNull(current);Objects.requireNonNull(actor);Set<UUID> evidence=evidence(verified);period(period);
        return atomic(s->{
            CityView city=s.view(current.cityId()).orElseThrow(()->new IllegalArgumentException("City is not registered"));
            if(!authorized(current,city))throw new IllegalArgumentException("Current city authority is not verified");
            Optional<Project> previous=project(requestId);
            if(previous.isPresent()){
                Project value=previous.get();if(!value.cityId().equals(current.cityId())||!value.nationId().equals(current.nationId())||!value.definition().id().equals(definitionId))throw new IllegalArgumentException("Public works request UUID conflicts with its original payload");return value;
            }
            ProjectDefinition definition=configuration.projects().get(definitionId);if(definition==null)throw new IllegalArgumentException("Unknown public works definition");
            if(period<city.lastPeriod())throw new IllegalArgumentException("Public works request period is stale");
            if(project(city.id(),current.nationId()).isPresent())throw new IllegalArgumentException("This city's national public works slot is already allocated");
            String invalid=requirements(city,current,definition,evidence);if(!invalid.isEmpty())throw new IllegalArgumentException(invalid);
            Profile profile=profile(city.id()).orElseThrow();debit(city.id(),city.stock(),definition.upfrontCosts());
            Project result=new Project(requestId,city.id(),current.nationId(),definition,period,Math.addExact(period,1),0,-1,-1,-1,false,-1,profile.plan(),city.civilization(),profile.knowledge(),profile.culture());
            insert(result);history(result,period,"started","upfront="+definition.upfrontCosts()+" plan="+profile.plan()+" civilization="+city.civilization()+" knowledge="+profile.knowledge()+" culture="+profile.culture());
            s.audit(actor,"PUBLIC WORKS START "+requestId+" "+definition.id());return project(requestId).orElseThrow();
        });
    }

    /** Trusted settlement operation, never expose as a query API. Its debit must join atomicSettlement. */
    public Cycle cycle(CoreCity current,CityView before,long period,Set<UUID> verified)throws SQLException{
        Objects.requireNonNull(current);Objects.requireNonNull(before);Objects.requireNonNull(verified);period(period);
        Set<UUID> captured=Set.copyOf(verified);
        return atomic(s->{
            Optional<CityView> found=s.view(current.cityId());Optional<Project> project=project(current.cityId(),current.nationId());
            if(found.isEmpty()||!before.id().equals(current.cityId())||!before.nationId().equals(current.nationId())||!authorized(current,found.get()))return new Cycle(current,before,period,Set.of(),project,false,"Current city authority is not verified");
            CityView city=found.get();
            if(captured.size()>configuration.data().maxEvidenceFacilities())return new Cycle(current,before,period,Set.of(),project,false,"Too many verified works facilities");
            if(city.lastPeriod()>=period||before.lastPeriod()>=period||s.settled(city.id(),period))return new Cycle(current,before,period,captured,project,false,"Period already settled or clock moved backwards");
            if(project.isEmpty())return new Cycle(current,before,period,captured,project,false,"No public works allocated");
            Project value=project.get();
            if(!value.completed()||period<=value.completedPeriod())return new Cycle(current,before,period,captured,project,false,"Public works are still under construction");
            String invalid=requirements(city,current,value.definition(),captured);
            if(value.lastOperationPeriod()>=period){boolean paid=value.lastOperationPeriod()==period&&value.lastOperationPaid()&&invalid.isEmpty();return new Cycle(current,before,period,captured,project,paid,paid?"Operation already paid for this period":"Operation already paused or clock moved backwards");}
            boolean paid=invalid.isEmpty()&&missing(city.stock(),value.definition().upkeepCosts()).isEmpty();
            String reason=paid?"Operating":invalid.isEmpty()?"Insufficient operating stock":invalid;
            if(paid)debit(city.id(),city.stock(),value.definition().upkeepCosts());
            s.execute("UPDATE public_works SET last_operation_period=?,last_operation_paid=? WHERE request_id=?",period,paid?1:0,value.requestId());
            history(value,period,paid?"operating":"operation_paused",paid?"upkeep="+value.definition().upkeepCosts():reason);
            return new Cycle(current,before,period,captured,project(value.requestId()),paid,reason);
        });
    }

    public final class Cycle {
        private final CoreCity current;private final CityView before;private final long period;private final Set<UUID> verified;private final Optional<Project> project;private final boolean operating;private final String reason;
        private Cycle(CoreCity current,CityView before,long period,Set<UUID> verified,Optional<Project> project,boolean operating,String reason){this.current=current;this.before=before;this.period=period;this.verified=Set.copyOf(verified);this.project=project;this.operating=operating;this.reason=reason;}
        public Optional<Project> project(){return project;}
        public boolean operating(){return operating;}
        public String reason(){return reason;}
        public double multiplier(Building building){return operating&&verified.contains(building.id())&&building.cityId().equals(current.cityId())&&building.nationId().equals(current.nationId())?project.orElseThrow().definition().multipliers().getOrDefault(building.type(),1.0):1;}
        /** Uses this period's actual maintained set; never advances more than one step or rewards completion. */
        public Completion completed(Set<UUID> maintained)throws SQLException{return complete(this,Set.copyOf(maintained));}
    }

    private Completion complete(Cycle cycle,Set<UUID> maintained)throws SQLException{
        return atomic(s->{
            if(cycle.project.isEmpty())return new Completion(cycle.period,false,false,false,0,"No public works allocated");
            Project project=project(cycle.project.get().requestId()).orElseThrow();int progress=project.progress();
            if(project.completed())return new Completion(cycle.period,true,false,false,progress,"Public works already completed");
            CityView city=s.view(cycle.current.cityId()).orElseThrow();
            if(!authorized(cycle.current,city)||!project.nationId().equals(cycle.current.nationId())||city.lastPeriod()>=cycle.period||cycle.before.lastPeriod()>=cycle.period||s.settled(city.id(),cycle.period))return new Completion(cycle.period,true,false,false,progress,"City authority or settlement period is not current");
            if(cycle.period<project.effectivePeriod()||project.lastConstructionAttemptPeriod()>=cycle.period)return new Completion(cycle.period,true,false,false,progress,"Construction already inspected or not yet effective");
            Set<UUID> active=new HashSet<>(cycle.verified);active.retainAll(maintained);
            String invalid=requirements(city,cycle.current,project.definition(),active);
            if(invalid.isEmpty()&&!missing(city.stock(),project.definition().constructionCosts()).isEmpty())invalid="Insufficient construction stock";
            s.execute("UPDATE public_works SET last_construction_attempt_period=? WHERE request_id=?",cycle.period,project.requestId());
            if(!invalid.isEmpty()){history(project,cycle.period,"construction_paused",invalid);return new Completion(cycle.period,true,false,false,progress,invalid);}
            debit(city.id(),city.stock(),project.definition().constructionCosts());int next=progress+1;boolean finished=next==project.definition().buildPeriods();
            s.execute("UPDATE public_works SET progress=?,last_build_period=?,completed_period=? WHERE request_id=?",next,cycle.period,finished?cycle.period:-1,project.requestId());
            history(project,cycle.period,finished?"completed":"construction","progress="+next+"/"+project.definition().buildPeriods()+" construction="+project.definition().constructionCosts());
            return new Completion(cycle.period,true,true,finished,next,finished?"Completed; operation starts next settlement period":"Construction advanced");
        });
    }

    private record Profile(String plan,long knowledge,long culture){}
    private Optional<Profile> profile(UUID city)throws SQLException{return store.query("SELECT plan,knowledge,culture FROM evolution_city WHERE city=?",r->new Profile(r.getString(1),r.getLong(2),r.getLong(3)),city).stream().findFirst();}
    private String requirements(CityView city,CoreCity current,ProjectDefinition definition,Set<UUID> verified)throws SQLException{
        Optional<Profile> found=profile(city.id());if(found.isEmpty())return "City evolution profile is not initialized";Profile profile=found.get();
        if(!definition.plans().contains(profile.plan()))return "Current city plan does not support this public works";
        if(!definition.civilizations().contains(city.civilization()))return "Current national civilization does not support this public works";
        if(profile.knowledge()<definition.knowledge()||profile.culture()<definition.culture())return "Knowledge or culture threshold is not met";
        Set<String> types=new HashSet<>();
        for(Building building:city.buildings())if(verified.contains(building.id())&&building.cityId().equals(city.id())&&building.nationId().equals(current.nationId())&&definition.facilities().contains(building.type())&&definition.terrains().contains(store.region(building.site().regionKey()).terrain()))types.add(building.type());
        return types.containsAll(definition.facilities())?"":"All required facilities must be verified, maintained for construction, and on eligible terrain";
    }
    private boolean authorized(CoreCity current,CityView city)throws SQLException{return current.cityId().equals(city.id())&&current.nationId().equals(city.nationId())&&store.query("SELECT active FROM city_authority WHERE city=?",r->r.getInt(1)==1,city.id()).stream().findFirst().orElse(false);}
    private Set<UUID> evidence(Set<UUID> verified){Objects.requireNonNull(verified);if(verified.size()>configuration.data().maxEvidenceFacilities())throw new IllegalArgumentException("Too many verified public works facilities");return Set.copyOf(verified);}
    private static Map<String,Long> missing(Map<String,Long> stock,Map<String,Long> costs){Map<String,Long> missing=new TreeMap<>();costs.forEach((item,amount)->{long shortfall=amount-stock.getOrDefault(item,0L);if(shortfall>0)missing.put(item,shortfall);});return Map.copyOf(missing);}
    private void debit(UUID city,Map<String,Long> stock,Map<String,Long> costs)throws SQLException{if(!missing(stock,costs).isEmpty())throw new IllegalArgumentException("Insufficient physical public works stock");for(var cost:costs.entrySet())store.stock(city,cost.getKey(),stock.getOrDefault(cost.getKey(),0L)-cost.getValue());}
    private void history(Project project,long period,String kind,String detail)throws SQLException{
        store.execute("INSERT INTO public_works_history(request_id,city,period,kind,detail) VALUES(?,?,?,?,?)",project.requestId(),project.cityId(),period,kind,detail);
        store.execute("DELETE FROM public_works_history WHERE city=? AND sequence NOT IN (SELECT sequence FROM public_works_history WHERE city=? ORDER BY sequence DESC LIMIT ?)",project.cityId(),project.cityId(),configuration.data().historyLimit());
    }
    private void insert(Project project)throws SQLException{
        ProjectDefinition d=project.definition();String marks=String.join(",",Collections.nCopies(28,"?"));
        store.execute("INSERT INTO public_works(request_id,city,nation,definition_id,name,build_periods,upfront,construction,upkeep,facilities,terrains,plans,civilizations,knowledge_threshold,culture_threshold,multipliers,requested_period,effective_period,progress,last_build_period,completed_period,last_operation_period,last_operation_paid,last_construction_attempt_period,start_plan,start_civilization,start_knowledge,start_culture) VALUES("+marks+")",project.requestId(),project.cityId(),project.nationId(),d.id(),d.name(),d.buildPeriods(),encode(d.upfrontCosts()),encode(d.constructionCosts()),encode(d.upkeepCosts()),encode(d.facilities()),encode(d.terrains()),encode(d.plans()),encode(d.civilizations()),d.knowledge(),d.culture(),encode(d.multipliers()),project.requestedPeriod(),project.effectivePeriod(),project.progress(),project.lastBuildPeriod(),project.completedPeriod(),project.lastOperationPeriod(),project.lastOperationPaid()?1:0,project.lastConstructionAttemptPeriod(),project.startPlan(),project.startCivilization(),project.startKnowledge(),project.startCulture());
    }
    private Project read(ResultSet row)throws SQLException{
        ProjectDefinition definition=new ProjectDefinition(row.getString("definition_id"),row.getString("name"),row.getInt("build_periods"),amounts(row.getString("upfront")),amounts(row.getString("construction")),amounts(row.getString("upkeep")),ids(row.getString("facilities")),ids(row.getString("terrains")),ids(row.getString("plans")),ids(row.getString("civilizations")),row.getLong("knowledge_threshold"),row.getLong("culture_threshold"),factors(row.getString("multipliers")));
        return new Project(UUID.fromString(row.getString("request_id")),UUID.fromString(row.getString("city")),UUID.fromString(row.getString("nation")),definition,row.getLong("requested_period"),row.getLong("effective_period"),row.getInt("progress"),row.getLong("last_build_period"),row.getLong("completed_period"),row.getLong("last_operation_period"),row.getInt("last_operation_paid")==1,row.getLong("last_construction_attempt_period"),row.getString("start_plan"),row.getString("start_civilization"),row.getLong("start_knowledge"),row.getLong("start_culture"));
    }
    private static String encode(Set<String> values){return String.join(",",new TreeSet<>(values));}
    private static String encode(Map<String,? extends Number> values){StringJoiner result=new StringJoiner("\n");new TreeMap<>(values).forEach((key,value)->result.add(key+"="+value));return result.toString();}
    private static Set<String> ids(String value)throws SQLException{if(value==null||value.length()>1024)throw new SQLException("Corrupt works ID snapshot");try{return Set.of(value.split(","));}catch(IllegalArgumentException e){throw new SQLException("Duplicate works snapshot ID",e);}}
    private static Map<String,String> entries(String value)throws SQLException{if(value==null||value.length()>4096)throw new SQLException("Corrupt works snapshot size");Map<String,String> result=new TreeMap<>();for(String line:value.split("\n")){int split=line.indexOf('=');if(split<1||result.put(line.substring(0,split),line.substring(split+1))!=null||result.size()>16)throw new SQLException("Corrupt works snapshot entries");}return result;}
    private static Map<String,Long> amounts(String value)throws SQLException{Map<String,Long> result=new TreeMap<>();try{entries(value).forEach((key,number)->result.put(key,Long.parseLong(number)));}catch(NumberFormatException e){throw new SQLException("Corrupt works cost",e);}return Map.copyOf(result);}
    private static Map<String,Double> factors(String value)throws SQLException{Map<String,Double> result=new TreeMap<>();try{entries(value).forEach((key,number)->result.put(key,Double.parseDouble(number)));}catch(NumberFormatException e){throw new SQLException("Corrupt works multiplier",e);}return Map.copyOf(result);}
    private static void period(long period){if(period<0)throw new IllegalArgumentException("Invalid public works period");}
    private void page(int limit,int offset){if(limit<1||limit>configuration.data().maxPage()||offset<0||offset>1_000_000)throw new IllegalArgumentException("Public works page out of range");}
    /** A failed local write cannot escape merely because an outer WorldStore transaction handles it. */
    private <T> T atomic(WorldStore.Work<T> work)throws SQLException{
        return store.tx(s->{
            String savepoint="works_"+UUID.randomUUID().toString().replace("-","");s.execute("SAVEPOINT "+savepoint);
            try{T result=work.run(s);s.execute("RELEASE SAVEPOINT "+savepoint);return result;}
            catch(Exception|Error failure){
                try{s.execute("ROLLBACK TO SAVEPOINT "+savepoint);s.execute("RELEASE SAVEPOINT "+savepoint);}catch(SQLException rollback){failure.addSuppressed(rollback);}
                throw failure;
            }
        });
    }
}

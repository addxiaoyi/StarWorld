#!/usr/bin/env node
'use strict';

// Independent real legacy-to-current lifecycle acceptance. SQL is stopped, immutable and read-only.
// Preparation/copying, plugin replacement, and the two declared cap changes belong to the operator.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process'),{createRequire}=require('node:module');
const {randomUUID}=require('node:crypto');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil}=require('../paper-client/acceptance.cjs');
const MARKER='StarWorld isolated Paper smoke runtime v1',PLAYER='StarWorldQA';
const JVM=['-Xms128m','-Xmx768m','-Xss512k','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-XX:MaxMetaspaceSize=384m','-Dio.netty.eventLoopThreads=2'];
const MEMORY_PROBE=String.raw`
import ctypes,json,os
minimum=1610612736
if os.name!='nt':print(json.dumps({'windows':False,'minimumFreeCommitBytes':minimum}))
else:
 class Memory(ctypes.Structure):
  _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(name,ctypes.c_ulonglong) for name in ['totalPhysical','availablePhysical','totalPageFile','availablePageFile','totalVirtual','availableVirtual','availableExtendedVirtual']]
 memory=Memory();memory.length=ctypes.sizeof(memory)
 if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)):raise ctypes.WinError()
 print(json.dumps({'windows':True,'minimumFreeCommitBytes':minimum,'freeCommitBytes':memory.availablePageFile,'commitLimitBytes':memory.totalPageFile,'availablePhysicalBytes':memory.availablePhysical}))
`;
const HELP=`Usage: node lifecycle-acceptance.cjs --mode source|candidate
  --java <Java21> --paper <Paper1.21.11.jar> --python <Python3>
  --runtime <prepared-owned-copy> --client-deps <Mineflayer-directory> [--preflight]
Source also requires:
  --source-runtime <unchanged-stopped-passed-1.0-runtime>
  --baseline-evidence <that-runtime/passed-civic-evidence.json>
Candidate also requires:
  --source-evidence <source-phase/evidence.json>

Prepare a complete, separate stopped 1.0 copy, rebuilt fixtures, localhost/offline/EULA,
and change only its config period-seconds to 60. Random events stay 0 and profession
cooldown stays 1. Real CE Cookery items must remain available. Source records a real
player chef/deposit/Cookery-25 action, then cleanly stops while that exact action is
unconsumed in genuine schema1. Original accepted runtime is read only and never started.

After source passes, separately copy that stopped source to a new candidate, install
the current release JAR and the same rebuilt fixture. Change only life.yml's
player-culture-per-period and city-culture-per-period to 20; preserve all other input.
Candidate starts three times. Public exact-ID Futures must show 25->20, then 5+new12=17,
same-period repeat reads and another restart with no duplicate credit. No direct drain,
period mutation, SQL write, existing-server attachment, asset download or EULA change.
All timings, hashes, stopped probes and full child/client logs remain in
runtime/lifecycle-acceptance/<mode>-<UTC>/, including failed attempts.
`;
const PROBE=String.raw`
import hashlib,json,re,sqlite3,sys,zipfile
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
root=Path(sys.argv[1]).resolve()
tables=['city_state','building','transfer','simulation_clock','settlement','life_schema','life_profile','life_city','life_action','life_evolution_action','life_evolution_pending','life_evolution_budget','life_decoration','life_concert','civic_action_claim']
result={}
def normalize(value):
 if isinstance(value,bytes):return {'blob_sha256':hashlib.sha256(value).hexdigest(),'bytes':len(value)}
 return value
for label,relative in [('world','plugins/StarWorld/starworld.db'),('core','plugins/StarCore/starcore.db')]:
 db=root/relative
 if not db.is_file():raise RuntimeError('Missing stopped database: '+str(db))
 wal=Path(str(db)+'-wal')
 if wal.exists() and wal.stat().st_size:raise RuntimeError('Nonempty WAL; stop server cleanly: '+str(wal))
 with sqlite3.connect(db.as_uri()+'?mode=ro&immutable=1',uri=True) as con:
  names=[r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
  probe={'sha256':hashlib.sha256(db.read_bytes()).hexdigest(),'tables':names,'rows':{},'columns':{},'integrity':con.execute('PRAGMA quick_check').fetchone()[0],'foreignKeys':list(con.execute('PRAGMA foreign_key_check'))}
  for table in (tables if label=='world' else ['starcore_nation_state','starcore_treasury_state','starcore_resource_state']):
   if table not in names:continue
   columns=[row[1] for row in con.execute('PRAGMA table_info('+table+')')]
   probe['columns'][table]=columns
   quoted=','.join('"'+c.replace('"','""')+'"' for c in columns)
   probe['rows'][table]=[dict(zip(columns,[normalize(value) for value in row])) for row in con.execute('SELECT * FROM '+table+' ORDER BY '+quoted)]
  result[label]=probe
with zipfile.ZipFile(root/'plugins/StarWorld.jar') as jar:
 result['version']=re.search(r"^version:\s*['\"]?([^'\"\s]+)",jar.read('plugin.yml').decode('utf-8'),re.M).group(1)
 result['bundledConfigs']={name:hashlib.sha256(jar.read(name)).hexdigest() for name in ['climate.yml'] if name in jar.namelist()}
with zipfile.ZipFile(root/'plugins/StarWorldSenLuoFixture.jar') as jar:
 result['lifecycleFixture']= 'dev/starworld/fixture/LifecycleSenLuoFixture.class' in jar.namelist()
print(json.dumps(result,ensure_ascii=False))
`;
function argsOf(argv) {
  const result={};
  const values=['mode','java','paper','python','runtime','client-deps','source-runtime','baseline-evidence','source-evidence'];
  for(let i=0;i<argv.length;i++) {
    if(['--help','-h'].includes(argv[i])){result.help=true;continue;}
    if(argv[i]==='--preflight'){assert(!result.preflight);result.preflight=true;continue;}
    const key=argv[i].slice(2);assert(argv[i].startsWith('--')&&values.includes(key),'Unknown option '+argv[i]);
    assert(!result[key]&&argv[i+1]&&!argv[i+1].startsWith('--'),'Missing/duplicate option '+argv[i]);result[key]=argv[++i];
  }
  return result;
}
function json(file){return JSON.parse(fs.readFileSync(requireFile(file),'utf8').replace(/^\uFEFF/,''));}
function configs(runtime){return Object.fromEntries(fs.readdirSync(path.join(runtime,'plugins/StarWorld')).filter(n=>n.endsWith('.yml')).sort().map(n=>[n,hash(path.join(runtime,'plugins/StarWorld',n))]));}
function validateRunConfigurations(version,initial,actual,bundled) {
  const expected={...initial};
  if(/^1\./.test(version)&&Number(version.split('.')[1])>=2) {
    assert(!Object.hasOwn(initial,'climate.yml'),'Legacy candidate input must not already contain climate.yml');
    assert.match(bundled?.['climate.yml']||'',/^[a-f0-9]{64}$/,'Candidate JAR must provide its climate default');
    expected['climate.yml']=bundled['climate.yml'];
  }
  assert.deepEqual(actual,expected,'Configuration input changed beyond the bundled climate default');
  return actual;
}
function jars(runtime){return Object.fromEntries(fs.readdirSync(path.join(runtime,'plugins')).filter(n=>n.endsWith('.jar')).sort().map(n=>[n,hash(path.join(runtime,'plugins',n))]));}
function probe(python,runtime) {
  const process=spawnSync(python,['-c',PROBE,runtime],{encoding:'utf8',windowsHide:true,maxBuffer:16*1024*1024});
  assert.equal(process.status,0,'Stopped read-only SQLite probe failed: '+(process.error||process.stderr));
  const value=JSON.parse(process.stdout);
  for(const label of ['world','core']){assert.equal(value[label].integrity,'ok');assert.deepEqual(value[label].foreignKeys,[]);}
  return value;
}
function separate(a,b){assert.notEqual(a,b);for(const [parent,child]of [[a,b],[b,a]]){const rel=path.relative(parent,child);assert(rel==='..'||rel.startsWith('..'+path.sep)||path.isAbsolute(rel),'Source and candidate must be separate trees');}}
function withNumber(text,key,value) {
  const expression=new RegExp('^(\\s*'+key+':\\s*)([0-9]+)(\\s*(?:#.*)?)$','gm');
  let count=0;const changed=text.replace(expression,(_,prefix,old,suffix)=>{count++;return prefix+value+suffix;});
  assert.equal(count,1,'Exactly one numeric '+key+' required');return changed;
}
function unchangedRows(before,after,table) {
  const rows=after.world.rows[table]||[],index=new Map(rows.map(row=>[row.id, row]));
  for(const old of before.world.rows[table]||[])assert.deepEqual(index.get(old.id),old,'Original '+table+' changed: '+old.id);
}
function identity(state,source) {
  assert.equal(state.player,PLAYER);assert.equal(state.gameplayWrites,false);
  assert.equal(state.cityId,source.cityId);assert.equal(state.nationId,source.nationId);assert.equal(state.actorId,source.actorId);
  assert.equal(state.life.city,state.cityId);assert.equal(state.life.nation,state.nationId);
  assert.equal(state.city.id,state.cityId);assert.equal(state.profile.city,state.cityId);
}
function pendingRows(snapshot) {
  const done=new Set((snapshot.world.rows.life_evolution_action||[]).map(row=>row.id));
  return snapshot.world.rows.life_action.filter(row=>row.eligible===1&&!done.has(row.id));
}
function credit(state,id,original,credited,last,pending) {
  assert.deepEqual(state.credits[id],{actionId:id,cityId:state.cityId,nationId:state.nationId,originalCulture:original,creditedCulture:credited,lastCreditedPeriod:last,pending});
  return state.credits[id];
}
async function main() {
  const args=argsOf(process.argv.slice(2));if(args.help){console.log(HELP);return;}
  for(const key of ['mode','java','paper','python','runtime','client-deps'])assert(args[key],'Missing --'+key);
  assert(['source','candidate'].includes(args.mode));
  for(const key of ['_JAVA_OPTIONS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'])assert(!process.env[key],'Unexpected JVM option injection: '+key);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),python:requireFile(args.python),runtime:fs.realpathSync(args.runtime),username:PLAYER,jvmArgs:JVM};
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),MARKER);
  const props=properties(path.join(options.runtime,'server.properties'));
  assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');
  assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);assert(options.port>=1024&&options.port<65536);
  const configFile=path.join(options.runtime,'plugins/StarWorld/config.yml'),lifeFile=path.join(options.runtime,'plugins/StarWorld/life.yml');
  assert.equal(configuredNumber(configFile,'period-seconds'),60,'Explicit isolated 60-second observation window required');
  assert.equal(configuredNumber(configFile,'event-chance'),0);
  assert.equal(configuredNumber(lifeFile,'profession-cooldown-periods'),1);
  const before=probe(options.python,options.runtime),pluginJars=jars(options.runtime),configSha256=configs(options.runtime);
  assert(before.lifecycleFixture,'Rebuild and register LifecycleSenLuoFixture before running');
  for(const name of ['StarWorld.jar','StarCore.jar','StarWorldFixture.jar','StarWorldSenLuoFixture.jar'])assert(pluginJars[name],'Missing real plugin '+name);
  const preparation={javaSha256:hash(options.java),paperSha256:hash(options.paper),pluginJars,configSha256,inputConfigurationSha256:{...configSha256}};
  let sourceEvidence,original,accepted;
  if(args.mode==='source') {
    for(const key of ['source-runtime','baseline-evidence'])assert(args[key],'Missing --'+key);
    const source=fs.realpathSync(args['source-runtime']);separate(source,options.runtime);
    assert.equal(fs.readFileSync(path.join(source,'.starworld-smoke-runtime'),'utf8').trim(),MARKER);
    const evidenceFile=requireFile(args['baseline-evidence']);accepted=json(evidenceFile);
    const rel=path.relative(source,fs.realpathSync(evidenceFile));assert(rel&&!rel.startsWith('..')&&!path.isAbsolute(rel),'Baseline evidence must belong to original runtime');
    assert.equal(accepted.status,'passed');assert.equal(accepted.version,'1.0.0');assert(accepted.steps.length>=30&&accepted.steps.every(row=>row.status==='passed'));
    original=probe(options.python,source);
    assert.equal(original.version,'1.0.0');assert.equal(before.version,'1.0.0');
    assert.equal(accepted.pluginJars['StarWorld.jar'],jars(source)['StarWorld.jar']);assert.equal(pluginJars['StarWorld.jar'],accepted.pluginJars['StarWorld.jar']);
    for(const label of ['world','core'])assert.equal(before[label].sha256,original[label].sha256,'Source must begin as an exact stopped database copy');
    const oldJars=jars(source);for(const [name,sum]of Object.entries(oldJars))if(name!=='StarWorldSenLuoFixture.jar')assert.equal(pluginJars[name],sum,'Unexpected source plugin change: '+name);
    const oldConfigs=configs(source);assert.deepEqual(Object.keys(configSha256),Object.keys(oldConfigs));
    for(const [name,sum]of Object.entries(oldConfigs))if(name!=='config.yml')assert.equal(configSha256[name],sum,'Unexpected source configuration change: '+name);
    const oldConfig=fs.readFileSync(path.join(source,'plugins/StarWorld/config.yml'),'utf8');
    assert.equal(fs.readFileSync(configFile,'utf8'),withNumber(oldConfig,'period-seconds',60),'Only the declared source period tuning is allowed');
    assert.deepEqual(before.world.rows.life_schema,[{id:1,version:1}]);assert(!before.world.columns.life_evolution_action.includes('credited_culture'));
    assert.equal(pendingRows(before).length,0,'Accepted source has pending older life actions; prepare a normally drained stopped source');
    preparation.original={runtime:source,evidenceFile,evidenceSha256:hash(evidenceFile),databases:{world:original.world.sha256,core:original.core.sha256},configurationSha256:oldConfigs};
  } else {
    assert(args['source-evidence'],'Missing --source-evidence');const sourceFile=requireFile(args['source-evidence']);sourceEvidence=json(sourceFile);
    assert.equal(sourceEvidence.suite,'real-paper-life-carry');assert.equal(sourceEvidence.mode,'source');assert.equal(sourceEvidence.status,'passed');
    assert.equal(sourceEvidence.steps.length,4);assert(sourceEvidence.steps.every(row=>row.status==='passed'));
    const source=fs.realpathSync(sourceEvidence.runtime);separate(source,options.runtime);
    const finalSource=probe(options.python,source);
    for(const label of ['world','core']){assert.equal(finalSource[label].sha256,sourceEvidence.after[label].sha256,'Stopped source changed after proof');assert.equal(before[label].sha256,finalSource[label].sha256,'Candidate is not the exact stopped source copy');}
  assert.match(before.version,/^1\.(?:1|[2-9]\d*)\.\d+(?:-SNAPSHOT)?$/);assert.equal(finalSource.version,'1.0.0');
    assert.deepEqual(configs(source),sourceEvidence.preparation.configSha256);
    const sourceJars=jars(source);assert.deepEqual(sourceJars,sourceEvidence.preparation.pluginJars);
    assert.deepEqual(Object.keys(pluginJars),Object.keys(sourceJars));for(const [name,sum]of Object.entries(sourceJars))if(name!=='StarWorld.jar')assert.equal(pluginJars[name],sum,'Unexpected candidate plugin change: '+name);
    assert.deepEqual(Object.keys(configSha256),Object.keys(sourceEvidence.preparation.configSha256),'Candidate must begin with only the preserved source configurations');
    for(const [name,sum]of Object.entries(sourceEvidence.preparation.configSha256))if(name!=='life.yml')assert.equal(configSha256[name],sum,'Unexpected candidate configuration change: '+name);
    const sourceLife=fs.readFileSync(path.join(source,'plugins/StarWorld/life.yml'),'utf8');
    assert.equal(fs.readFileSync(lifeFile,'utf8'),withNumber(withNumber(sourceLife,'player-culture-per-period',20),'city-culture-per-period',20));
    assert.equal(configuredNumber(lifeFile,'player-culture-per-period'),20);assert.equal(configuredNumber(lifeFile,'city-culture-per-period'),20);
    preparation.source={runtime:source,evidenceFile:sourceFile,evidenceSha256:hash(sourceFile),oldAction:sourceEvidence.oldAction};
  }
  const base=path.join(options.runtime,'lifecycle-acceptance');if(fs.existsSync(base))assert(!fs.readdirSync(base).some(name=>name.startsWith(args.mode+'-')),'Preserve attempts; prepare a new runtime copy');
  if(args.preflight){console.log(JSON.stringify({status:'prepared',mode:args.mode,javaStarted:false,version:before.version,preparation}));return;}
  const mineflayer=createRequire(path.resolve(args['client-deps'],'package.json'))('mineflayer');
  const directory=path.join(base,args.mode+'-'+new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-life-carry',mode:args.mode,status:'running',runtime:options.runtime,version:before.version,startedAt:new Date().toISOString(),expectedStages:args.mode==='source'?4:7,preparation,testTuning:{periodSeconds:60,randomEventChance:0,playerCap:configuredNumber(lifeFile,'player-culture-per-period'),cityCap:configuredNumber(lifeFile,'city-culture-per-period'),jvmArgs:JVM,nodeArgs:process.execArgv,nodeVersion:process.version},scope:'Actual CE Cookery player feast and warehouse debits; genuine stopped schema1; additive lifecycle migration; indexed public exact-action culture reads; real online settlements; same-period reads; two candidate restarts. No SQL writes, direct drain, period manipulation, Core reconstruction, upstream music completion or Folia claim.',before,steps:[]};
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stopped=name=>{const value=probe(options.python,options.runtime);fs.writeFileSync(path.join(directory,name+'.json'),JSON.stringify(value,null,2)+'\n');return value;};
  const stage=async(name,run)=>{console.log('RUN '+name);const row={name,startedAt:new Date().toISOString()};evidence.steps.push(row);save();try{row.result=await run();row.status='passed';return row.result;}catch(error){row.status='failed';row.error=error.stack;throw error;}finally{row.finishedAt=new Date().toISOString();save();}};
  let server,bot,initial,lastPartial,complete,oldId,newId,legacyId,unknown=randomUUID();
  const closeClient=async()=>{if(bot){bot.quit();await bot.finishTranscript();bot=null;}};
  const shutdown=async()=>{await closeClient();if(server){await server.stop();assert(server.lines.some(row=>/All dimensions are saved/.test(row)),'Owned server did not report its normal completed world save');assert(!server.lines.some(row=>/Settlement failed \(transaction rolled back\)|OutOfMemoryError|StarWorld startup failed|Could not flush StarWorld storage|Could not close StarWorld storage/.test(row)),'Product or JVM failed during the owned real run');}};
  async function start(run){
    const checked=spawnSync(options.python,['-c',MEMORY_PROBE],{encoding:'utf8',windowsHide:true,maxBuffer:1024*1024});
    assert.equal(checked.status,0,'Free-commit preflight failed: '+(checked.error||checked.stderr));
    const memory=JSON.parse(checked.stdout);(evidence.startPreflight??=[]).push({run,at:new Date().toISOString(),...memory});save();
    if(process.platform==='win32')assert.equal(memory.windows,true,'Windows commit check was not available');
    if(memory.windows)assert(memory.freeCommitBytes>=1610612736,'Less than1.5GiB free commit; preserving failed run without starting Java or touching another process');
    server=new Paper(options,directory,run);await server.start();bot=await connect(mineflayer,options,directory,run);
  }
  async function fixture(action,extra='') {
    const from=server.lines.length;server.command('swsenluo '+action+' '+PLAYER+(extra?' '+extra:''));
    const raw=await waitUntil(()=>{server.check();const lines=server.lines.slice(from);const failed=lines.find(row=>/SW_SENLUO_FAILED|SW_FIXTURE_FAILED/.test(row));if(failed)throw new Error(failed);return lines.find(row=>row.includes('SW_SENLUO_JSON ')&&row.includes('"action":"'+action+'"'));},20000,'lifecycle fixture '+action);
    return JSON.parse(raw.slice(raw.indexOf('SW_SENLUO_JSON ')+'SW_SENLUO_JSON '.length));
  }
  const state=(ids=[])=>fixture('lifecycle-state',ids.join(' '));
  const coherent=s=>s.period===s.observedPeriod&&s.city.lastPeriod===s.period&&s.profile.lastPeriod===s.period&&s.life.decorationPeriod===s.period;
  async function settledAfter(period,ids=[]) {return waitUntil(async()=>{const value=await state(ids);return coherent(value)&&value.city.lastPeriod>period?value:false;},95000,'new actual online city settlement',()=>server.check());}
  async function seedDeposit(bundle) {
    const before=await state(),seed=await fixture('lifecycle-seed',bundle);
    for(const [item,amount]of Object.entries(seed.fixtureSeededPlayerInventory))await botMessage(bot,'/sw deposit '+item+' '+amount,/已存入/);
    const after=await state();for(const [item]of Object.entries(seed.fixtureSeededPlayerInventory))assert.equal(after.inventory[item],before.inventory[item],'Seed must enter warehouse through actual player deposit');
    return{before,seed,after};
  }
  const readAgain=async(first,ids)=>{const next=await state(ids);assert.equal(next.period,first.period,'Read proof crossed a real period boundary');assert.equal(next.city.lastPeriod,first.city.lastPeriod);assert.deepEqual(next.credits,first.credits);assert.deepEqual(next.profile,first.profile);assert.equal(next.life.culture,first.life.culture);assert.deepEqual(next.stock,first.stock);return next;};
  function actionId(message){const found=message.match(/[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}/i);assert(found,'Actual feast receipt UUID missing');return found[0];}
  function legacyRetained(snapshot) {
    unchangedRows(before,snapshot,'life_action');
    const originalActions=new Map(before.world.rows.life_action.map(row=>[row.id,row]));
    for(const old of before.world.rows.life_evolution_action) {
      const row=snapshot.world.rows.life_evolution_action.find(value=>value.id===old.id);
      assert(row,'Consumed original action disappeared');
      assert.equal(row.id,old.id);assert.equal(row.consumed_period,old.consumed_period);
      assert.equal(row.credited_culture,originalActions.get(old.id).culture,'Completed legacy action changed/requeued');
    }
  }
  try {
    if(args.mode==='source') {
      await stage('genuine 1.0 and original city/player restore',async()=>{
        await start(1);initial=await state();assert.equal(initial.version,'1.0.0');assert.equal(initial.creditApiAvailable,false);
        const oldCity=before.world.rows.city_state.find(row=>row.id===initial.cityId);assert(oldCity);assert.equal(oldCity.nation,initial.nationId);
        const oldProfile=before.world.rows.life_profile.find(row=>row.player===initial.actorId);assert(oldProfile);assert.equal(initial.profession.cultureContributed,oldProfile.contributed);
        assert.equal(initial.life.culture,before.world.rows.life_city.find(row=>row.city===initial.cityId).culture);
        return initial;
      });
      await stage('real CE Cookery inventory deposit and chef profession',async()=>{
        const deposited=await seedDeposit('cookery');
        if(deposited.after.profession.profession!=='chef') {
          if(deposited.after.period<=deposited.after.profession.changedPeriod)await settledAfter(deposited.after.period);
          await botMessage(bot,'/sw profession chef',/生活职业：/);
        }
        const ready=await state();assert.equal(ready.profession.profession,'chef');return{deposited,ready};
      });
      await stage('actual Cookery25 after online settlement then clean stop',async()=>{
        const prior=await state(),window=await settledAfter(prior.city.lastPeriod),message=await botMessage(bot,'/sw feast cookery',/宴会完成：文化 \+25/);oldId=actionId(message);
        const after=await state();assert.equal(after.period,window.period,'Cookery source crossed a real period boundary');assert.equal(after.city.lastPeriod,window.city.lastPeriod);
        assert.equal(after.life.culture,window.life.culture+25);assert.equal(after.profession.cultureContributed,window.profession.cultureContributed+25);
        assert.equal(after.stock['kaleidoscopecookery:baozi'],window.stock['kaleidoscopecookery:baozi']-4);assert.equal(after.stock['kaleidoscopecookery:oil'],window.stock['kaleidoscopecookery:oil']-1);
        evidence.identity={cityId:after.cityId,nationId:after.nationId,actorId:after.actorId};evidence.oldActionId=oldId;
        await shutdown();return{window,message,after,cleanStop:true};
      });
      await stage('stopped schema1 proves real committed25 remains unconsumed',async()=>{
        const after=stopped('stopped-source-final');assert.equal(after.version,'1.0.0');assert.deepEqual(after.world.rows.life_schema,[{id:1,version:1}]);
        assert(!after.world.columns.life_evolution_action.includes('credited_culture'));unchangedRows(before,after,'life_action');
        const added=after.world.rows.life_action.filter(row=>!before.world.rows.life_action.some(old=>old.id===row.id));
        assert.equal(added.length,1);const action=added[0];assert.equal(action.id,oldId);assert.equal(action.city,evidence.identity.cityId);assert.equal(action.nation,evidence.identity.nationId);assert.equal(action.actor,evidence.identity.actorId);assert.equal(action.kind,'feast');assert.equal(action.source,'cookery');assert.equal(action.culture,25);assert.equal(action.eligible,1);
        assert.deepEqual(pendingRows(after).map(row=>row.id),[oldId],'The source proof must have exactly the real pending25');
        assert(after.world.rows.life_evolution_action.length>0,'Need actual fully consumed historical rows for migration proof');
        assert(after.world.rows.life_evolution_action.every(row=>row.id!==oldId));
        evidence.oldAction=action;evidence.after=after;return{oldAction:action,pending:[oldId],oldCompleted:after.world.rows.life_evolution_action.length,cleanReadOnlyDatabaseSha256:after.world.sha256};
      });
    } else {
      oldId=sourceEvidence.oldActionId;legacyId=before.world.rows.life_evolution_action.find(row=>before.world.rows.life_action.find(action=>action.id===row.id&&action.city===sourceEvidence.identity.cityId))?.id;
      assert(legacyId,'Original consumed history sample required');
      const legacy=before.world.rows.life_evolution_action.find(row=>row.id===legacyId),legacyAction=before.world.rows.life_action.find(row=>row.id===legacyId);
      const ids=[oldId,legacyId,unknown];
      await stage('target life-schema migration and first actual cycle credit20',async()=>{
        await start(1);
        lastPartial=await waitUntil(async()=>{const s=await state(ids);identity(s,sourceEvidence.identity);assert.match(s.version,/^1\.(?:1|[2-9]\d*)\.\d+(?:-SNAPSHOT)?$/);assert.equal(s.creditApiAvailable,true);assert.equal(s.credits[unknown],null);
          const value=s.credits[oldId];assert(value&&value.creditedCulture<=20,'Missed partial-credit window; preserve failed run and prepare fresh candidate');
          return coherent(s)&&value.creditedCulture===20?s:false;},95000,'actual partial20',()=>server.check());
        credit(lastPartial,oldId,25,20,lastPartial.period,true);credit(lastPartial,legacyId,legacyAction.culture,legacyAction.culture,legacy.consumed_period,false);return lastPartial;
      });
      await stage('same-period public reads cannot grant more culture',async()=>readAgain(lastPartial,ids));
      await stage('real new community12 persists beside partial20 across clean stop',async()=>{
        const deposited=await seedDeposit('community');assert.equal(deposited.after.period,lastPartial.period,'Seed/deposit crossed partial20 window');
        const window=await state(ids);assert.equal(window.period,lastPartial.period);
        const message=await botMessage(bot,'/sw feast community',/宴会完成：文化 \+12/);newId=actionId(message);evidence.newActionId=newId;
        const after=await state([...ids,newId]);assert.equal(after.period,lastPartial.period,'New12 crossed partial20 window');
        credit(after,oldId,25,20,lastPartial.period,true);credit(after,newId,12,0,-1,true);
        assert.equal(after.stock['minecraft:bread'],window.stock['minecraft:bread']-8);assert.equal(after.life.culture,window.life.culture+12);
        await shutdown();const snapshot=stopped('stopped-candidate-partial');legacyRetained(snapshot);
        assert.deepEqual(snapshot.world.rows.life_schema,[{id:1,version:2}]);
        const old=snapshot.world.rows.life_evolution_action.find(row=>row.id===oldId);assert.equal(old.credited_culture,20);assert.equal(old.consumed_period,lastPartial.period);
        assert(!snapshot.world.rows.life_evolution_action.some(row=>row.id===newId));assert.deepEqual(snapshot.world.rows.life_evolution_pending.map(row=>row.id).sort(),[oldId,newId].sort());
        const budget=snapshot.world.rows.life_evolution_budget.find(row=>row.city===after.cityId&&row.nation===after.nationId);assert.equal(budget.period,lastPartial.period);assert.equal(budget.culture,20);
        evidence.partial=snapshot;return{deposited,window,message,after,stopped:snapshot};
      });
      await stage('restart credits remaining5 plus actual new12 exactly17',async()=>{
        await start(2);
        complete=await waitUntil(async()=>{const s=await state([...ids,newId]);identity(s,sourceEvidence.identity);return coherent(s)&&s.credits[oldId]?.creditedCulture===25&&s.credits[newId]?.creditedCulture===12?s:false;},95000,'remaining5 plus12 actual online cycle',()=>server.check());
        assert(complete.period>lastPartial.period);credit(complete,oldId,25,25,complete.period,false);credit(complete,newId,12,12,complete.period,false);
        assert.equal((complete.credits[oldId].creditedCulture-20)+complete.credits[newId].creditedCulture,17);return complete;
      });
      await stage('same-period completed diagnostics preserve all credit and stock',async()=>readAgain(complete,[...ids,newId]));
      await stage('second normal stop conserves originals and exactly17 budget',async()=>{
        await shutdown();const snapshot=stopped('stopped-candidate-complete');legacyRetained(snapshot);
        const budget=snapshot.world.rows.life_evolution_budget.find(row=>row.city===complete.cityId&&row.nation===complete.nationId);assert.equal(budget.period,complete.period);assert.equal(budget.culture,17);
        assert.deepEqual(snapshot.world.rows.life_evolution_pending,[]);evidence.complete=snapshot;return snapshot;
      });
      await stage('third real start keeps completed actions and normal stop unchanged',async()=>{
        await start(3);const initial=await state([...ids,newId]),next=await settledAfter(initial.city.lastPeriod,[...ids,newId]);identity(next,sourceEvidence.identity);
        assert.deepEqual(next.credits,complete.credits);await readAgain(next,[...ids,newId]);await shutdown();
        const snapshot=stopped('stopped-candidate-final');legacyRetained(snapshot);unchangedRows(evidence.complete,snapshot,'life_action');
        assert.deepEqual(snapshot.world.rows.life_evolution_action,evidence.complete.world.rows.life_evolution_action);assert.deepEqual(snapshot.world.rows.life_evolution_pending,[]);
        assert.deepEqual(snapshot.world.rows.life_evolution_budget,evidence.complete.world.rows.life_evolution_budget);evidence.after=snapshot;
        return{initial,next,stopped:snapshot,netNewLifeCredit:37,offlineCreditInvented:0};
      });
    }
    assert.deepEqual(jars(options.runtime),pluginJars,'Plugin input changed during the real run');
    preparation.configSha256=validateRunConfigurations(before.version,configSha256,configs(options.runtime),before.bundledConfigs);
    if(original){const preserved=probe(options.python,preparation.original.runtime);for(const label of ['world','core'])assert.equal(preserved[label].sha256,original[label].sha256,'Original accepted database was changed');assert.deepEqual(configs(preparation.original.runtime),preparation.original.configurationSha256);}
    assert.equal(evidence.steps.length,evidence.expectedStages);assert(evidence.steps.every(row=>row.status==='passed'));evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();
    console.log(JSON.stringify({status:evidence.status,mode:args.mode,steps:evidence.steps.length,directory,evidence:path.join(directory,'evidence.json'),oldActionId:oldId,newActionId:newId||null}));
  }catch(error){evidence.status='failed';evidence.error=error.stack;save();throw error;}
  finally {
    try{await shutdown();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;save();process.exitCode=1;}
    fs.writeFileSync(path.join(base,'latest-'+args.mode+'.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');
  }
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={argsOf,withNumber,PROBE,validateRunConfigurations};


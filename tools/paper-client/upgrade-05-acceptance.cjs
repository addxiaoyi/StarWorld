#!/usr/bin/env node
'use strict';

// Upgrade an explicitly prepared copy of a stopped, previously accepted 0.4 world.
// The Python probe uses SQLite immutable read-only connections only; no gameplay data is written by SQL.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),net=require('node:net');
const {spawnSync}=require('node:child_process');
const {createRequire}=require('node:module');
const {setTimeout:delay}=require('node:timers/promises');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil}=require('./acceptance.cjs');

const HELP=`Usage: node upgrade-05-acceptance.cjs --java <Java21> --paper <Paper132.jar>
  --runtime <prepared-upgrade-copy> --python <Python3> [--client-deps <Mineflayer>]

Requires UPGRADE-PROVENANCE.json prepared from the stopped 0.4 baseline04-server01 acceptance
world, with source/plugin/SQLite/player fingerprints and an explicitly selected 0.5 target SHA.
Never point this at the original or a production runtime. The existing city, facilities, inventory
and core authority are retained; the script does not seed goods, build countries, rewrite configs,
edit SQLite values or invoke warehouse transfers. Two real client/server starts must pass.
All seven existing configuration fingerprints and the market namespace must survive unchanged.
The four 0.5 disaster-response tables must initialize without inventing deliveries or balances.
Evidence and complete transcripts are preserved under runtime/upgrade-05-acceptance/<UTC stamp>.
`;
function argumentsOf(argv){const result={};for(let i=0;i<argv.length;i++){
  if(argv[i]==='--help'||argv[i]==='-h'){result.help=true;continue;}
  assert(['--java','--paper','--runtime','--python','--client-deps'].includes(argv[i]),`Unknown option: ${argv[i]}`);
  const key=argv[i].slice(2);assert(!result[key]&&argv[i+1]&&!argv[i+1].startsWith('--'),`Missing/duplicate ${argv[i]}`);result[key]=argv[++i];
}return result;}
const PROBE=String.raw`
import hashlib,json,sqlite3,sys,zipfile,re
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
root=Path(sys.argv[1]);result={}
selected=['city_state','building','stock','transfer','nation_state','national_evolution','region','reserve','city_authority','simulation_clock','settlement','resource_outbox','logistics_order','logistics_receipt','life_action','life_profession','life_concert','life_note','life_audience','life_evolution_action','life_decoration','market_delivery_schema','market_delivery_clock','market_delivery_receipt','market_delivery_stat','disaster_response_schema','disaster_response','disaster_response_history','disaster_recovery_ledger','market_schema','market_listing','market_purchase','market_history','culture_dynamics_schema','culture_decay','logistics_reroute','logistics_paid_shipment']
def normalize(value):
 if isinstance(value,bytes):return {'blob_sha256':hashlib.sha256(value).hexdigest(),'bytes':len(value)}
 return value
for label,relative in [('world','plugins/StarWorld/starworld.db'),('core','plugins/StarCore/starcore.db')]:
 db=root/relative
 wal=Path(str(db)+'-wal')
 if wal.exists() and wal.stat().st_size:raise RuntimeError('Probe requires a cleanly stopped/checkpointed database, not live WAL: '+str(wal))
 con=sqlite3.connect(db.as_uri()+'?mode=ro&immutable=1',uri=True)
 names=[r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
 probe={'schema':con.execute('PRAGMA user_version').fetchone()[0],'tables':names,'database_sha256':hashlib.sha256(db.read_bytes()).hexdigest(),'rows':{}}
 chosen=selected if label=='world' else ['starcore_nation_state','starcore_treasury_state','starcore_resource_state']
 for table in chosen:
  if table not in names:continue
  assert re.fullmatch('[A-Za-z0-9_]+',table)
  columns=[row[1] for row in con.execute('PRAGMA table_info('+table+')')]
  data=[dict(zip(columns,[normalize(v) for v in row])) for row in con.execute('SELECT * FROM '+table+' ORDER BY '+','.join('"'+c+'"' for c in columns))]
  probe['rows'][table]=data
 probe['integrity']=con.execute('PRAGMA quick_check').fetchone()[0];con.close();result[label]=probe
with zipfile.ZipFile(root/'plugins/StarWorld.jar') as jar:
 descriptor=jar.read('plugin.yml').decode('utf-8');result['pluginVersion']=re.search(r"^version:\s*['\"]?([^'\"\s]+)",descriptor,re.M).group(1)
print(json.dumps(result,ensure_ascii=False))
`;
function sqliteSnapshot(options){
  const result=spawnSync(options.python,['-c',PROBE,options.runtime],{encoding:'utf8',windowsHide:true,maxBuffer:16*1024*1024});
  assert.equal(result.status,0,`Read-only stopped SQLite probe failed: ${result.stderr}`);return JSON.parse(result.stdout);
}
function readJson(file){const text=fs.readFileSync(file,'utf8');return JSON.parse(text.charCodeAt(0)===0xfeff?text.slice(1):text);}
function stockOf(rows,city){return Object.fromEntries(rows.filter(row=>row.city===city).map(row=>[row.item,row.amount]));}
function productionUpperBounds(runtime,before){
  const bounds={};
  for(const name of ['definitions.yml','evolution.yml'])for(const match of fs.readFileSync(path.join(runtime,'plugins/StarWorld',name),'utf8').matchAll(/^\s*outputs:\s*\{([^}]+)\}\s*$/gm))
    for(const entry of match[1].matchAll(/([a-z0-9._-]+:[a-z0-9/._-]+):\s*([0-9]+)/g))bounds[entry[1]]=(bounds[entry[1]]||0)+Number(entry[2]);
  assert(Object.keys(bounds).length>0,'The pinned baseline recipe output bounds were not recognized');
  const levels=before.world.rows.building.reduce((total,building)=>total+building.level,0);
  return Object.fromEntries(Object.entries(bounds).map(([item,amount])=>[item,amount*levels*10]));
}
function inventoryCount(inventory,type,named){return inventory.items.filter(item=>item.type===type&&(named===undefined||item.named===named)).reduce((total,item)=>total+item.amount,0);}
function assertInventory(inventory){assert.equal(inventoryCount(inventory,'minecraft:arrow'),0);assert.equal(inventoryCount(inventory,'minecraft:wheat',false),0);assert.equal(inventoryCount(inventory,'minecraft:wheat',true),7);}
function assertPreserved(before,after){
  assert.equal(after.world.schema,2);assert.equal(after.world.integrity,'ok');assert.equal(after.core.integrity,'ok');
  for(const name of ['building','transfer','nation_state','national_evolution','resource_outbox','logistics_order','logistics_receipt','life_action','life_profession','life_concert','life_note','life_audience','life_evolution_action','life_decoration'])
    if(before.world.rows[name])assert.deepEqual(after.world.rows[name],before.world.rows[name],`Upgrade changed durable ${name}`);
  const previous=before.world.rows.city_state[0],city=after.world.rows.city_state.find(row=>row.id===previous.id);assert(city);
  assert.equal(city.nation,previous.nation);assert.equal(city.name,previous.name);assert.equal(city.civilization,previous.civilization);assert(city.last_period>=previous.last_period);
  const identity=rows=>rows.filter(row=>row.property_key!=='__starcore_revision__');
  assert.deepEqual(identity(after.core.rows.starcore_nation_state||[]),identity(before.core.rows.starcore_nation_state||[]),'Core national authority changed');
  assert.deepEqual(identity(after.core.rows.starcore_treasury_state||[]),identity(before.core.rows.starcore_treasury_state||[]),'Upgrade must not create treasury payments');
  for(const name of ['market_schema','market_listing','market_purchase','market_history','culture_dynamics_schema','culture_decay','logistics_reroute','logistics_paid_shipment','market_delivery_schema','market_delivery_clock','market_delivery_receipt','market_delivery_stat'])assert(after.world.tables.includes(name),`Missing additive module table: ${name}`);
  for(const name of ['market_listing','market_purchase','market_history','logistics_reroute','logistics_paid_shipment'])assert.equal(after.world.rows[name].length,0,`Upgrade invented ${name}`);
  assert.deepEqual(after.world.rows.market_schema,before.world.rows.market_schema,'Existing market namespace/version changed during upgrade');
  assert.deepEqual(after.world.rows.culture_dynamics_schema,before.world.rows.culture_dynamics_schema,'Existing culture schema changed during upgrade');
  assert.deepEqual(after.world.rows.market_delivery_schema,[{id:1,version:1}]);assert.deepEqual(after.world.rows.market_delivery_clock,[{id:1,sequence:0}]);
  assert.deepEqual(after.world.rows.market_delivery_receipt,[]);assert.deepEqual(after.world.rows.market_delivery_stat,[]);
  for(const name of ['market_delivery_schema','market_delivery_clock','market_delivery_receipt','market_delivery_stat'])assert.deepEqual(after.world.rows[name],before.world.rows[name],`Existing delivery namespace changed: ${name}`);
  assert.deepEqual(after.world.rows.disaster_response_schema,[{id:1,version:1}]);
  for(const name of ['disaster_response','disaster_response_history','disaster_recovery_ledger'])assert.deepEqual(after.world.rows[name],[],`Upgrade invented disaster response: ${name}`);
}
async function free(port){const server=net.createServer();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,'127.0.0.1',resolve);});await new Promise(resolve=>server.close(resolve));}

async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log(HELP);return;}
  for(const key of ['java','paper','runtime','python'])assert(args[key],`Missing --${key}`);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),python:requireFile(args.python),runtime:path.resolve(args.runtime),username:'StarWorldQA',jvmArgs:['-Xms128m','-Xmx512m','-Xss512k','-XX:MaxMetaspaceSize=192m','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-Dio.netty.eventLoopThreads=2']};
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const prep=readJson(path.join(options.runtime,'UPGRADE-PROVENANCE.json'));assert.equal(prep.schema,1);assert.equal(prep.sourceVersion,'0.4.0');assert.equal(prep.targetVersion,'0.5.0');assert.notEqual(fs.realpathSync(prep.sourceRuntime),fs.realpathSync(options.runtime));
  const props=properties(path.join(options.runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);await free(options.port);
  assert.equal(hash(path.join(options.runtime,'plugins/StarWorld.jar')),prep.targetPluginSha256);assert.notEqual(prep.sourcePluginSha256,prep.targetPluginSha256);
  assert.equal(configuredNumber(path.join(options.runtime,'plugins/StarWorld/config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(options.runtime,'plugins/StarWorld/config.yml'),'event-chance'),0);
  assert.equal(prep.sourceEvidence.status,'passed');assert.equal(prep.sourceEvidence.steps,15);assert.equal(prep.sourceEvidence.pluginSha256,prep.sourcePluginSha256);
  const localRequire=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=localRequire('mineflayer');
  const directory=path.join(options.runtime,'upgrade-05-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-upgrade-04-to-05',status:'running',startedAt:new Date().toISOString(),sourceVersion:'0.4.0',targetVersion:'0.5.0',sourcePluginSha256:prep.sourcePluginSha256,paperSha256:hash(options.paper),pluginJars:{},beforeProvenance:prep,scope:'Upgrade a copy of an actually accepted stopped 0.4 save; no SQL mutation, seeded goods, new city or warehouse commands. Natural production is bounded by actual new settlement receipts; transfers are preserved exactly.',testTuning:{jvmArgs:options.jvmArgs,nodeVersion:process.version,nodeExecArgv:process.execArgv,javaOptionsEnvironment:process.env._JAVA_OPTIONS||'',periodSeconds:10,eventChance:0},steps:[]};
  for(const file of fs.readdirSync(path.join(options.runtime,'plugins')))if(file.endsWith('.jar'))evidence.pluginJars[file]=hash(path.join(options.runtime,'plugins',file));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,startedAt:new Date().toISOString()};evidence.steps.push(step);save();console.log('RUN '+name);try{step.observed=await work();step.status='passed';}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),bot=null,firstStop;
  async function closeClient(){if(bot){bot.quit();await bot.finishTranscript();bot=null;}}
  const before=sqliteSnapshot(options),original=before.world.rows.city_state[0];
  try{
    await stage('stopped accepted 0.4 save and copied database fingerprints match provenance',async()=>{
      assert.equal(before.pluginVersion,'0.5.0');assert.equal(before.world.schema,2);assert.equal(before.world.database_sha256,prep.before.world.database_sha256);assert.equal(before.core.database_sha256,prep.before.core.database_sha256);assert.deepEqual(before.world.rows,prep.before.world.rows);assert.equal(before.world.rows.city_state.length,1);assert.equal(before.world.rows.building.length,3);assert.equal(before.world.rows.transfer.length,4);
      assert(before.world.tables.includes('market_schema'));assert(before.world.tables.includes('culture_dynamics_schema'));assert.equal(before.world.rows.market_schema[0].version,1);assert.equal(before.world.rows.culture_dynamics_schema[0].version,1);
      for(const name of ['disaster_response_schema','disaster_response','disaster_response_history','disaster_recovery_ledger'])assert(!before.world.tables.includes(name),`Source already contains 0.5 table: ${name}`);
      assert.deepEqual(before.world.rows.market_listing,[]);assert.deepEqual(before.world.rows.market_purchase,[]);assert.deepEqual(before.world.rows.logistics_receipt,[]);assert.equal(Object.keys(prep.oldConfigurationSha256).length,7);
      for(const [relative,sha]of Object.entries(prep.sourceCriticalFiles))assert.equal(hash(path.join(options.runtime,relative)),sha,`Changed copied input ${relative}`);
      return before;
    });
    await stage('new plugin loads original core city facilities inventory and national authority',async()=>{
      await server.start();bot=await connect(mineflayer,options,directory,1);const city=await server.snapshot(),inventory=await server.inventory();assert.equal(city.cityId,original.id);assert.equal(city.buildings,3);assert(city.lastPeriod>=original.last_period);assertInventory(inventory);
      const nation=await botMessage(bot,'/sw nation',new RegExp('nationId='+original.nation));return{city,inventory,nation};
    });
    await stage('existing atlas and market remain usable without replacing seven old configurations',async()=>{
      for(const [relative,sha]of Object.entries(prep.oldConfigurationSha256))assert.equal(hash(path.join(options.runtime,relative)),sha,`Existing configuration was replaced: ${relative}`);
      assert(fs.existsSync(path.join(options.runtime,'plugins/StarWorld/market.yml')));const market=await botMessage(bot,'/sw market list',/实体商品挂牌/);
      const opened=new Promise(resolve=>bot.once('windowOpen',resolve));bot.chat('/sw atlas');const window=await Promise.race([opened,delay(15000).then(()=>{throw new Error('Atlas GUI did not open');})]);assert.equal(window.slots.slice(0,45).filter(Boolean).length,3,'Atlas must recover all three registered facility regions');const title=window.title;bot.closeWindow(window);
      return{market,atlasTitle:title,oldConfigurationSha256:prep.oldConfigurationSha256,retainedMarketConfigurationSha256:hash(path.join(options.runtime,'plugins/StarWorld/market.yml'))};
    });
    await stage('normal new periods produce without replaying old transfers or offline catchup',async()=>{
      const initial=await server.snapshot();const later=await waitUntil(async()=>{const value=await server.snapshot();return value.lastPeriod>initial.lastPeriod?value:false;},30000,'one actual post-upgrade period',()=>server.check());assert.equal(later.cityId,original.id);assert.equal(later.buildings,3);assert.match(later.lastReport,/产出 \{[^}]+=/,'The original loaded production chain did not continue');const inventory=await server.inventory();assertInventory(inventory);return{initial,later,inventory};
    });
    await stage('first normal shutdown commits additive schemas and preserves durable old receipts',async()=>{
      await closeClient();await server.stop();firstStop=sqliteSnapshot(options);assertPreserved(before,firstStop);
      const newReceipts=firstStop.world.rows.settlement.filter(row=>row.period>original.last_period);const elapsed=(Date.now()-Date.parse(evidence.startedAt))/1000;assert(newReceipts.length>0&&newReceipts.length<=Math.ceil(elapsed/10)+3,'Offline catchup or duplicate settlement receipts');
      const oldStock=stockOf(before.world.rows.stock,original.id),currentStock=stockOf(firstStop.world.rows.stock,original.id),perPeriodUpperBound=productionUpperBounds(options.runtime,before);for(const [item,amount]of Object.entries(oldStock))assert((currentStock[item]||0)>=amount,`Default continuing chain unexpectedly reset ${item}`);
      for(const [item,amount]of Object.entries(currentStock))assert(amount-(oldStock[item]||0)<=(perPeriodUpperBound[item]||0)*newReceipts.length,`Stock increase exceeded all legal actual-period outputs: ${item}`);
      return{state:firstStop,newSettlements:newReceipts,elapsedSeconds:elapsed,oldStock,currentStock,perPeriodUpperBound};
    });
    server=new Paper(options,directory,2);
    await stage('second startup restores the same upgraded city and protected player inventory',async()=>{
      await server.start();bot=await connect(mineflayer,options,directory,2);const city=await server.snapshot(),inventory=await server.inventory();assert.equal(city.cityId,original.id);assert.equal(city.buildings,3);assert(city.lastPeriod>=firstStop.world.rows.city_state[0].last_period);assertInventory(inventory);return{city,inventory};
    });
    await stage('second normal shutdown preserves payment and transfer tombstones with no invented market orders',async()=>{
      await closeClient();await server.stop();const after=sqliteSnapshot(options);assertPreserved(before,after);assert.deepEqual(after.world.rows.market_schema,firstStop.world.rows.market_schema,'Market namespace changed across restart');assert.deepEqual(after.world.rows.transfer,firstStop.world.rows.transfer);return after;
    });
    evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();console.log(`PASS ${evidence.steps.length} real upgrade stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(error.stack);process.exitCode=1;}
  finally{await closeClient();if(server.child)try{await server.stop();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;save();process.exitCode=1;}fs.writeFileSync(path.join(options.runtime,'upgrade-05-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

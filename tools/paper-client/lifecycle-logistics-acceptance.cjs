#!/usr/bin/env node
'use strict';

// Independent 1.1 recovery acceptance. Actual connected founders use production commands.
// Only logistics.yml in an explicitly owned disposable runtime is edited, while stopped.
// Python opens only the stopped StarWorld extension database in mode=ro; no SQL writes.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process'),{createRequire}=require('node:module');
const {setTimeout:delay}=require('node:timers/promises');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil}=require('./acceptance.cjs');
let maintenanceLedger=[];

const HELP=`Usage: node lifecycle-logistics-acceptance.cjs --java <Java21> --paper <Paper.jar>
  --runtime <fresh-owned-directory> --python <Python3> [--client-deps <Mineflayer directory>]

Requires a separately prepared disposable localhost/offline Paper 1.21.11 runtime,
its .starworld-smoke-runtime marker, existing eula=true, matching PREPARATION.json,
real StarCore, StarWorld 1.1 and the existing current Paper fixture. No new Java fixture.
Fresh world/player/plugin databases, period-seconds=10, event-chance=0, normal
logistics.yml (travel-periods=1000, waypoints=16, blocks-per-period=128), and
zero/affordable public StarCore creation/claim costs are required.

Four clean owned server starts test normal paid/physical shipments, reduced travel
limits, independently reduced waypoint limits, then restoration. Four actual road
anchors in existing claimed/force-loaded fixture chunks form a direct two-node
short route and a deliberately longer four-node route that visits both cities.
Explicit inventory/treasury seeds are recorded as test inputs, never earned income.
Players remain deopped; real founder, claim, raycast and bilateral consent checks apply.
Historical paid cargo cannot refund as barter. Long-only checkpoint maintenance and
completion receipts are independently read only after each normal server shutdown.
No core table is accessed through SQL; core mutations and observations use public services.
Evidence and complete four-server/eight-client transcripts are retained under
runtime/lifecycle-logistics-acceptance/<UTC timestamp>. Failed attempts stay failed.
`;
function argumentsOf(argv){const out={};for(let i=0;i<argv.length;i++){
  if(['--help','-h'].includes(argv[i])){out.help=true;continue;}
  assert(['--java','--paper','--runtime','--python','--client-deps'].includes(argv[i]),`Unknown option: ${argv[i]}`);
  const key=argv[i].slice(2);assert(!out[key]&&argv[i+1]&&!argv[i+1].startsWith('--'),`Missing/duplicate ${argv[i]}`);out[key]=argv[++i];
}return out;}
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
const PROBE=String.raw`
import hashlib,json,sqlite3,sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
file=Path(sys.argv[1]).resolve();assert file.is_file()
con=sqlite3.connect(file.as_uri()+'?mode=ro',uri=True)
con.execute('PRAGMA query_only=ON')
assert [r[0] for r in con.execute('PRAGMA quick_check')]==['ok']
tables=['city_state','building','stock','logistics_node','logistics_route','logistics_route_node','logistics_order','logistics_cargo','logistics_node_usage','logistics_route_usage','logistics_reroute','logistics_paid_shipment','logistics_receipt','market_listing','market_purchase','market_history','market_delivery_receipt','market_delivery_stat','starworld_dispatch_cursor']
result={'database':'StarWorld extension only','path':str(file),'mode':'ro','integrity':'ok','sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'rows':{}}
for table in tables:
 columns=[r[1] for r in con.execute('PRAGMA table_info('+table+')')];assert columns,table
 rows=[dict(zip(columns,r)) for r in con.execute('SELECT * FROM '+table+' ORDER BY '+','.join('"'+c+'"' for c in columns)+' LIMIT 501')]
 assert len(rows)<501,'Unexpected growth in isolated test: '+table
 result['rows'][table]=rows
con.close();print(json.dumps(result,ensure_ascii=False))
`;
async function fixture(server,action,bot,suffix=''){
  const from=server.lines.length;server.command(`swfixture ${action} ${bot.username}${suffix?' '+suffix:''}`);
  return waitUntil(()=>{const lines=server.lines.slice(from);if(lines.some(line=>line.includes('SW_FIXTURE_FAILED')))throw new Error(lines.slice(-30).join('\n'));
    for(const line of lines){const at=line.indexOf('SW_FIXTURE_EXPANSION ');if(at<0)continue;const value=JSON.parse(line.slice(at+'SW_FIXTURE_EXPANSION '.length));if(value.action===action&&value.player===bot.username)return value.observed;}return false;
  },25000,`${action} ${bot.username}`,()=>{server.check();if(bot.failure)throw bot.failure;});
}
async function state(server,bot){const value=await fixture(server,'exp-progress',bot);assert.match(value.version,/^1\.1\.\d+(?:[-+][0-9A-Za-z.-]+)?$/);assert(value.city&&Array.isArray(value.nodes)&&Array.isArray(value.routes)&&Array.isArray(value.orders)&&Array.isArray(value.purchases)&&Array.isArray(value.traffic));assert.equal(value.city.buildings.length,0,'No production facility may hide cargo or maintenance changes');for(const amount of Object.values(value.city.stock))assert(Number.isSafeInteger(amount)&&amount>=0);const seen=new Set(maintenanceLedger.map(row=>`${row.node}|${row.period}`));for(const traffic of value.traffic)for(const node of traffic.nodes||[])if(node.maintenancePaid){const key=`${node.nodeId}|${node.maintenancePeriod}`;if(!seen.has(key)){seen.add(key);maintenanceLedger.push({node:node.nodeId,city:node.cityId,period:node.maintenancePeriod,route:traffic.routeId,observedAt:new Date().toISOString()});}}return value;}
async function poll(server,bot,test,timeout=45000,description='logistics state'){let last;return waitUntil(async()=>{last=await state(server,bot);return test(last)?last:false;},timeout,description,()=>{server.check();if(bot.failure)throw bot.failure;});}
function order(snapshot,id){return snapshot.orders.find(row=>row.id===id);}
function route(snapshot,id){return snapshot.routes.find(row=>row.id===id);}
function stock(snapshot,item){return snapshot.city.stock[`minecraft:${item}`]||0;}
function frozen(actual,baseline){assert(actual);assert.equal(actual.progressedPeriods,baseline.progress);assert.equal(actual.travelPeriods,baseline.travel);assert(['IN_TRANSIT','PAUSED'].includes(actual.status),'Accepted frozen cargo must remain in actual escrow');}
function dbOrder(snapshot,id){return snapshot.rows.logistics_order.find(row=>row.id===id);}
function dbStock(snapshot,city,item){return snapshot.rows.stock.find(row=>row.city===city&&row.item===`minecraft:${item}`)?.amount||0;}
function noReceipt(snapshot,ids){for(const id of ids)assert.equal(snapshot.rows.logistics_receipt.filter(row=>row.order_id===id).length,0);}
function oneReceipt(snapshot,id){assert.equal(snapshot.rows.logistics_receipt.filter(row=>row.order_id===id).length,1);}
function assertFrozenDatabase(snapshot,baseline,ids){
  const fields=['id','route','origin_city','target_city','origin_nation','target_nation','created_period','expires_period','progress','travel','completed_period'];
  for(const id of ids){const current=dbOrder(snapshot,id),old=dbOrder(baseline,id);assert(current&&old);assert.deepEqual(Object.fromEntries(fields.map(key=>[key,current[key]])),Object.fromEntries(fields.map(key=>[key,old[key]])),'Frozen accepted order identity or journey changed');assert.deepEqual(snapshot.rows.logistics_cargo.filter(row=>row.order_id===id),baseline.rows.logistics_cargo.filter(row=>row.order_id===id),'Frozen cargo payload changed');assert.deepEqual(snapshot.rows.logistics_paid_shipment.filter(row=>row.order_id===id),baseline.rows.logistics_paid_shipment.filter(row=>row.order_id===id),'Paid receipt handoff changed');}
}
function conserved(snapshot,seeds,baseline,maintenance){
  for(const item of ['minecraft:bread','minecraft:iron_ingot','minecraft:oak_log']){
    let total=snapshot.rows.stock.filter(row=>row.item===item).reduce((n,row)=>n+row.amount,0);
    const orderStates=new Map(snapshot.rows.logistics_order.map(row=>[row.id,row.state]));
    total+=snapshot.rows.logistics_cargo.filter(row=>{
      if(row.item!==item)return false;
      const state=orderStates.get(row.order_id);
      if(state==='OFFERED')return row.side==='OFFERED'; // The requested goods stay with the buyer until acceptance.
      return state==='IN_TRANSIT'||state==='PAUSED';
    }).reduce((n,row)=>n+row.amount,0);
    total+=snapshot.rows.market_listing.filter(row=>['AVAILABLE','LOCKED'].includes(row.state)&&row.item===item).reduce((n,row)=>n+row.units,0);
    assert.equal(total,seeds.reduce((n,seed)=>n+(seed.items?.[item]||0),0),`Stock + actual escrow conservation for ${item}`);
  }
  assert.equal(snapshot.rows.building.length,0);assert.equal(snapshot.rows.starworld_dispatch_cursor.length,2);
  const nodes=snapshot.rows.logistics_node;assert.equal(nodes.length,4);assert(nodes.every(row=>row.type==='ROAD'&&row.level===1));
  const nodeIds=new Set(nodes.map(row=>row.id));assert(snapshot.rows.logistics_node_usage.every(row=>nodeIds.has(row.node)&&row.used>0));
  const planks=snapshot.rows.stock.filter(row=>row.item==='minecraft:oak_planks').reduce((n,row)=>n+row.amount,0),provided=seeds.reduce((n,seed)=>n+(seed.items?.['minecraft:oak_planks']||0),0);
  // The node_usage table retains only a rolling window, while node.last_maintenance
  // is a watermark; neither is a lifetime debit ledger. Prove no stock was invented,
  // and verify each long-only node's maintenance watermark against the stopped baseline.
  assert(nodes.length===0||planks+16*nodes.length<=provided,'Road construction cannot debit more planks than the recorded seed');
  if(baseline){
    const owners=new Map(baseline.nodes.map(node=>[node.id,node.city])),cycles=new Map(baseline.nodes.map(node=>[node.id,new Set()]));
    for(const row of maintenance){if(row.period>baseline.period&&owners.has(row.node))cycles.get(row.node).add(row.period);}
    for(const city of baseline.cities){const expected=baseline.nodes.filter(node=>node.city===city.id).reduce((n,node)=>n+cycles.get(node.id).size,0),actual=snapshot.rows.stock.find(row=>row.city===city.id&&row.item==='minecraft:oak_planks')?.amount||0;assert.equal(actual,city.planks-expected,`Oak planks must reconcile with exact paid ROAD maintenance periods: ${city.id}`);}
    for(const node of baseline.nodes){const saved=snapshot.rows.logistics_node.find(row=>row.id===node.id);assert(saved,'Registered maintenance node disappeared');if(!cycles.get(node.id).size)assert.equal(saved.last_maintenance,node.lastMaintenance,'A node with no observed paid cycle was charged');else assert(saved.last_maintenance>=Math.max(...cycles.get(node.id)),'Observed maintenance period lacks a persisted node watermark');}
  }
}
function assertLongOnlyMaintenance(snapshot,baseline,nodes){for(const id of nodes){const current=snapshot.rows.logistics_node.find(row=>row.id===id),old=baseline.rows.logistics_node.find(row=>row.id===id);assert(current&&old);assert.equal(current.last_maintenance,old.last_maintenance,'Restricted route charged a long-only node');assert.equal(current.maintained,old.maintained);}}
async function registerNode(server,bot,action,slot){const from=bot.messages.length,proof=await fixture(server,action,bot,String(slot));await waitUntil(()=>bot.messages.slice(from).some(line=>line.includes('已登记 ')),15000,'actual raycast road registration');return proof;}
async function createRoute(bot,target,nodes){const text=await botMessage(bot,`/sw route create ${target} land ${nodes.join(',')}`,/商路 [0-9a-f-]{36} ·/);return text.match(/商路 ([0-9a-f-]{36})/)[1];}
async function offer(bot,routeId,give,take){const text=await botMessage(bot,`/sw trade offer ${routeId} ${give} ${take}`,/已托管报价 [0-9a-f-]{36}/);return text.match(/已托管报价 ([0-9a-f-]{36})/)[1];}
async function accept(bot,id){return botMessage(bot,`/sw trade accept ${id}`,/货单 .* IN_TRANSIT/);}
async function sell(bot,units){const text=await botMessage(bot,`/sw market sell minecraft:oak_log ${units} 2.00`,/挂牌已托管真实商品：[0-9a-f-]{36}/);return text.match(/挂牌已托管真实商品：([0-9a-f-]{36})/)[1];}
async function buy(bot,id,routeId){const text=await botMessage(bot,`/sw market buy ${id} ${routeId}`,/购买已准备：[0-9a-f-]{36}/);return text.match(/购买已准备：([0-9a-f-]{36})/)[1];}
function receiptPayload(value){assert(value);assert(Number.isFinite(Date.parse(value.appliedAt)));return{...value,appliedAt:new Date(value.appliedAt).toISOString()};}

async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){process.stdout.write(HELP);return;}
  for(const key of ['java','paper','runtime','python'])assert(args[key],`Required --${key}`);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),python:requireFile(args.python),runtime:path.resolve(args.runtime),jvmArgs:['-Xms48m','-Xmx320m','-Xss512k','-XX:MaxMetaspaceSize=192m','-XX:MaxDirectMemorySize=48m','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=48m','-Dio.netty.eventLoopThreads=2']};
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const props=properties(path.join(options.runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);assert(Number.isInteger(options.port)&&options.port>=1024&&options.port<=65535);
  const configDir=path.join(options.runtime,'plugins/StarWorld'),logisticsFile=path.join(configDir,'logistics.yml'),originalLogistics=fs.readFileSync(logisticsFile,'utf8');
  assert.equal(hash(logisticsFile),hash(path.resolve(__dirname,'../../src/main/resources/logistics.yml')),'Copy the repository default logistics.yml template byte for byte into this fresh runtime');
  assert.equal(configuredNumber(path.join(configDir,'config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(configDir,'config.yml'),'event-chance'),0);assert.equal(configuredNumber(logisticsFile,'travel-periods'),1000);assert.equal(configuredNumber(logisticsFile,'waypoints'),16);assert.equal(configuredNumber(logisticsFile,'blocks-per-period'),128);
  const preparationFile=path.join(options.runtime,'PREPARATION.json'),prepared=JSON.parse(fs.readFileSync(preparationFile,'utf8').replace(/^\uFEFF/,''));assert.equal(prepared.schema,1);assert.match(prepared.productVersion,/^1\.1\.\d+(?:[-+][0-9A-Za-z.-]+)?$/);assert.equal(prepared.artifactsBound,true);assert.equal(prepared.targetPluginSha256,hash(path.join(options.runtime,'plugins/StarWorld.jar')));assert.equal(prepared.fixtureSha256,hash(path.join(options.runtime,'plugins/StarWorldFixture.jar')));assert.equal(prepared.serverPort,options.port);for(const key of ['worldsCopied','databasesCopied','playersCopied'])assert.equal(prepared[key],0);
  assert(!fs.existsSync(path.join(configDir,'starworld.db')),'Lifecycle acceptance requires a fresh extension database');assert(!fs.existsSync(path.join(options.runtime,props['level-name']||'world')),'Lifecycle acceptance requires a fresh world');
  const localRequire=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=localRequire('mineflayer');
  const directory=path.join(options.runtime,'lifecycle-logistics-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});fs.writeFileSync(path.join(directory,'logistics-original.yml'),originalLogistics);
  maintenanceLedger=[];const evidence={schema:1,suite:'real-paper-lifecycle-logistics',status:'running',startedAt:new Date().toISOString(),minecraft:'1.21.11',node:process.version,runnerSha256:hash(__filename),mineflayer:localRequire('mineflayer/package.json').version,paperSha256:hash(options.paper),preparation:{sha256:hash(preparationFile),...prepared},pluginJars:{},configSha256:{},seeds:[],steps:[],configurationChanges:[],maintenanceObservations:maintenanceLedger,testTuning:{periodSeconds:10,eventChance:0,jvmArgs:options.jvmArgs,physicalSeedsAreProductionEvidence:false,nationalTreasurySeedsAreEarnedIncome:false,registeredBuildings:0,forceLoadedFixtureChunks:[20,21,22,23,24,52,53,54,55,56],routeModel:'public declared ordered nodes; four-node long route deliberately revisits the endpoint cities',databaseAccess:'StarWorld extension only; mode=ro after clean stop',coreDatabaseAccess:'none'}};
  for(const file of fs.readdirSync(path.join(options.runtime,'plugins')))if(file.endsWith('.jar'))evidence.pluginJars[file]=hash(path.join(options.runtime,'plugins',file));for(const file of fs.readdirSync(configDir))if(file.endsWith('.yml'))evidence.configSha256[file]=hash(path.join(configDir,file));evidence.configSha256['StarCore/config.yml']=hash(path.join(options.runtime,'plugins/StarCore/config.yml'));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();console.log('RUN '+name);try{step.observed=await work();step.status='passed';console.log('PASS '+name);return step.observed;}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),a,b,setupA,setupB,shortRoute,longRoute,longOnlyNodes,paidLong,paidShort,listingLong,listingShort,shipmentLong,shipmentShort,keptBarter,reroutedBarter,offeredLong,shortBarter,rerouteId,baselineMoney,firstReceipt,checkpoint1,checkpoint2,checkpoint3,checkpoint4,shortPeriods,sampling=false,sampler,samplerError;
  function startSampler(){if(sampling)return;samplerError=null;sampling=true;sampler=(async()=>{while(sampling){try{await state(server,b);}catch(error){samplerError=error;sampling=false;break;}await delay(1000);}})();}
  async function stopSampler(){sampling=false;if(sampler)await sampler;if(samplerError)throw samplerError;}
  const longIds=()=>[shipmentLong,keptBarter,reroutedBarter];
  async function players(run){a=await connect(mineflayer,{...options,username:'StarWorldLA'},directory,`${run}-a`);b=await connect(mineflayer,{...options,username:'StarWorldLB'},directory,`${run}-b`);server.command('deop StarWorldLA');server.command('deop StarWorldLB');for(const bot of [a,b])await waitUntil(async()=>{const permissions=await fixture(server,'exp-permissions',bot);return permissions.admin===false&&permissions.use===true?permissions:false;},10000,'ordinary founder permission');if(setupA&&setupB)await pair();}
  async function startOwned(run){
    assert.equal(server.child,null,'Do not overlap owned Java children');
    const checked=spawnSync(options.python,['-c',MEMORY_PROBE],{encoding:'utf8',windowsHide:true,timeout:15000,maxBuffer:1024*1024});assert.equal(checked.status,0,'Free-commit preflight failed: '+(checked.error||checked.stderr));
    const memory={run,at:new Date().toISOString(),...JSON.parse(checked.stdout)};(evidence.startPreflight??=[]).push(memory);fs.writeFileSync(path.join(directory,`memory-before-run-${run}.json`),JSON.stringify(memory,null,2)+'\n');save();
    if(process.platform==='win32')assert.equal(memory.windows,true,'Windows commit check was not available');
    if(memory.windows)assert(memory.freeCommitBytes>=1610612736,'Less than 1.5 GiB free commit: failed before starting Java; no unrelated process is stopped or awaited');
    await server.start();
  }
  async function stopOwned(){
    const runningLines=server.lines.slice();
    await stopSampler();
    await closePlayers();
    await server.stop();
    assert(server.lines.some(line=>/All dimensions are saved/.test(line)),'Owned server did not finish its normal world save');
    assert(!server.lines.some(line=>/OutOfMemoryError|StarWorld startup failed|Could not flush StarWorld storage|Could not close StarWorld storage/.test(line)),'Product or JVM error invalidates the owned run');
    assert(!runningLines.some(line=>/Settlement failed \(transaction rolled back\)|StarWorld tick failed|Extension tick failed/.test(line)),'A gameplay batch failed before owned shutdown');
  }
  async function closePlayers(){for(const bot of [a,b])if(bot){bot.quit();await bot.finishTranscript();}a=b=null;}
  async function pair(){const current={a:await state(server,a),b:await state(server,b)};for(const [key,expected]of [['a',setupA],['b',setupB]])if(expected){assert.equal(current[key].city.id,expected.cityId,'Original founder city changed');assert.equal(current[key].city.nationId,expected.nationId,'Original authoritative nation changed');}return current;}
  function probe(label){assert.equal(server.child,null,'Never open gameplay database while Paper is alive');const result=spawnSync(options.python,['-c',PROBE,path.join(configDir,'starworld.db')],{encoding:'utf8',windowsHide:true,timeout:15000,maxBuffer:8*1024*1024});assert.equal(result.status,0,`Stopped read-only probe: ${result.stderr}`);const value=JSON.parse(result.stdout);fs.writeFileSync(path.join(directory,`stopped-${label}.json`),JSON.stringify(value,null,2)+'\n');conserved(value,evidence.seeds,evidence.roadBaseline,maintenanceLedger);return value;}
  function configure(travel,waypoints,label){assert.equal(server.child,null,'Configuration may change only after owned clean shutdown');let text=originalLogistics;for(const [key,value]of [['travel-periods',travel],['waypoints',waypoints]]){assert(Number.isSafeInteger(value)&&value>=1);const pattern=key==='travel-periods'?/^([ \t]*travel-periods:[ \t]*)[0-9]+([ \t]*(?:#.*)?\r?)$/gm:/^([ \t]*waypoints:[ \t]*)[0-9]+([ \t]*(?:#.*)?\r?)$/gm;assert.equal([...text.matchAll(pattern)].length,1);text=text.replace(pattern,(_,prefix,suffix)=>prefix+value+suffix);}fs.writeFileSync(logisticsFile,text);const record={label,whileServerStopped:true,travelPeriods:travel,waypoints,sha256:hash(logisticsFile)};evidence.configurationChanges.push(record);fs.writeFileSync(path.join(directory,`logistics-${label}.yml`),text);return record;}
  async function receipt(id,replay=false){return fixture(server,replay?'exp-market-replay':'exp-market-receipt',b,id);}
  function originalReceipt(value){assert.equal(value.transactionId,firstReceipt.transactionId);assert.deepEqual(receiptPayload(value.receipt),receiptPayload(firstReceipt.receipt));assert.equal(Number(value.payerBefore),Number(value.payerAfter));assert.equal(Number(value.receiverBefore),Number(value.receiverAfter));return value;}
  function unchangedMoney(current,expected){assert.equal(Number(current.a.treasury),expected.seller);assert.equal(Number(current.b.treasury),expected.buyer);}
  try{
    await stage('first real Paper start creates ordinary founders and four registered road anchors',async()=>{
      await startOwned(1);await players(1);setupA=await fixture(server,'exp-setup',a,'0');setupB=await fixture(server,'exp-setup',b,'1');assert.notEqual(setupA.cityId,setupB.cityId);assert.notEqual(setupA.nationId,setupB.nationId);
      for(const bot of [a,b]){const seed=await fixture(server,'exp-progress-seed',bot);evidence.seeds.push({player:bot.username,...seed});for(const [item,amount]of Object.entries(seed.items))await botMessage(bot,`/sw deposit ${item} ${amount}`,new RegExp(`已存入 ${amount} ${item}`));}
      await registerNode(server,a,'exp-node',0);await registerNode(server,b,'exp-node',1);const direct=await pair();const a0=direct.a.nodes[0].id,b0=direct.b.nodes[0].id;await registerNode(server,a,'exp-alt-node',0);await registerNode(server,b,'exp-alt-node',1);const added=await pair();const a1=added.a.nodes.find(n=>n.id!==a0).id,b1=added.b.nodes.find(n=>n.id!==b0).id;longOnlyNodes=[a1,b1];
      shortRoute=await createRoute(a,setupB.cityId,[a0,b0]);longRoute=await createRoute(a,setupB.cityId,[a0,b1,a1,b0]);const routes=await state(server,a);shortPeriods=route(routes,shortRoute).travelPeriods;assert.equal(route(routes,shortRoute).nodes.length,2);assert.equal(route(routes,longRoute).nodes.length,4);assert(route(routes,longRoute).travelPeriods>=shortPeriods*2);assert(route(routes,longRoute).travelPeriods<24);
      const trafficNodes=new Map(routes.traffic.flatMap(value=>value.nodes).map(value=>[value.nodeId,value]));
      evidence.roadBaseline={period:Math.min(added.a.city.lastPeriod,added.b.city.lastPeriod),cities:[{id:setupA.cityId,planks:stock(added.a,'oak_planks')},{id:setupB.cityId,planks:stock(added.b,'oak_planks')}],nodes:[...added.a.nodes,...added.b.nodes].map(node=>{const state=trafficNodes.get(node.id);assert(state&&state.maintenancePeriod===-1&&!state.maintenancePaid,'All road anchors must have a clean pre-traffic maintenance watermark');return{id:node.id,city:node.cityId,lastMaintenance:state.maintenancePeriod};})};startSampler();
      return{setupA,setupB,roadBaseline:evidence.roadBaseline,short:route(routes,shortRoute),long:route(routes,longRoute),longOnlyNodes};
    });
    await stage('real public treasury funding and founder purchase create one paid long shipment',async()=>{
      for(const bot of [a,b])evidence.seeds.push(await fixture(server,'exp-fund',bot,'1000'));const before=await pair();baselineMoney={seller:Number(before.a.treasury),buyer:Number(before.b.treasury)};listingLong=await sell(a,11);paidLong=await buy(b,listingLong,longRoute);const sent=await poll(server,b,s=>s.purchases.some(p=>p.id===paidLong&&p.status==='SHIPPED'),45000,'actual long paid shipment');shipmentLong=sent.purchases.find(p=>p.id===paidLong).shipmentId;firstReceipt=await receipt(paidLong);assert.equal(Number(firstReceipt.receipt.amount),22);assert.equal(firstReceipt.receipt.category,'OTHER');const moving=await poll(server,b,s=>order(s,shipmentLong)?.progressedPeriods>0&&order(s,shipmentLong).status!=='COMPLETED',30000,'one real long movement before restart');assert(order(moving,shipmentLong).progressedPeriods<order(moving,shipmentLong).travelPeriods-1);unchangedMoney(await pair(),{seller:baselineMoney.seller+22,buyer:baselineMoney.buyer-22});return{before,moving,receipt:firstReceipt};
    });
    await stage('bilateral long cargo cancellable offer and short cargo are actual player commands',async()=>{
      keptBarter=await offer(a,longRoute,'minecraft:bread=13','minecraft:iron_ingot=3');await accept(b,keptBarter);reroutedBarter=await offer(a,longRoute,'minecraft:bread=7','minecraft:iron_ingot=2');await accept(b,reroutedBarter);offeredLong=await offer(a,longRoute,'minecraft:bread=5','minecraft:iron_ingot=1');shortBarter=await offer(a,shortRoute,'minecraft:bread=3','minecraft:iron_ingot=1');await accept(b,shortBarter);const observed=await pair();assert.equal(order(observed.a,offeredLong).status,'OFFERED');return observed;
    });
    await stage('normal first shutdown preserves unfinished cargo before any configuration changes',async()=>{
      await stopOwned();checkpoint1=probe('default');for(const id of [...longIds(),shortBarter]){const saved=dbOrder(checkpoint1,id);assert.equal(saved.state,'IN_TRANSIT');assert(saved.progress<saved.travel);}assert.equal(dbOrder(checkpoint1,offeredLong).state,'OFFERED');assert(dbOrder(checkpoint1,shipmentLong).progress>0);noReceipt(checkpoint1,longIds());return{checkpoint:checkpoint1,configuration:configure(shortPeriods,16,'travel-reduced')};
    });
    server=new Paper(options,directory,2);
    await stage('reduced travel limit restarts successfully and retained offer can actually cancel',async()=>{
      await startOwned(2);await players(2);const reply=await botMessage(a,`/sw trade cancel ${offeredLong}`,/货单 .* CANCELLED/);const current=await poll(server,b,s=>longIds().every(id=>order(s,id)?.status==='PAUSED'&&/最大运输时长/.test(order(s,id).reason)),30000,'each long shipment paused by travel limit');for(const id of longIds())frozen(order(current,id),dbOrder(checkpoint1,id));assert.equal(current.traffic.find(row=>row.routeId===longRoute).available,0);const denied=await botMessage(b,`/sw trade cancel ${shipmentLong}`,/已支付货运不能按实物易货撤销/);const status=await botMessage(a,`/sw route status ${longRoute}`,/运输暂停：.*最大运输时长/);return{reply,current,denied,status,receipt:originalReceipt(await receipt(paidLong))};
    });
    await stage('invalid long route cannot prepare another payment or trade while short barter keeps moving',async()=>{
      const before=await pair(),listing=await sell(a,1);const paymentDenied=await botMessage(b,`/sw market buy ${listing} ${longRoute}`,/最大运输时长/),tradeDenied=await botMessage(a,`/sw trade offer ${longRoute} minecraft:bread=1 minecraft:iron_ingot=1`,/最大运输时长/);const after=await pair();assert.equal(after.b.purchases.length,before.b.purchases.length);unchangedMoney(after,{seller:baselineMoney.seller+22,buyer:baselineMoney.buyer-22});await botMessage(a,`/sw market cancel ${listing}`,/挂牌已撤销/);const done=await poll(server,b,s=>order(s,shortBarter)?.status==='COMPLETED',70000,'independent short barter completes despite long route');for(const id of longIds())frozen(order(done,id),dbOrder(checkpoint1,id));return{before,after,paymentDenied,tradeDenied,done};
    });
    await stage('another actual national payment and short shipment succeed behind retained long cargo',async()=>{
      listingShort=await sell(a,6);paidShort=await buy(b,listingShort,shortRoute);const sent=await poll(server,b,s=>s.purchases.some(p=>p.id===paidShort&&p.status==='SHIPPED'),45000,'new short payment and shipment');shipmentShort=sent.purchases.find(p=>p.id===paidShort).shipmentId;const done=await poll(server,b,s=>order(s,shipmentShort)?.status==='COMPLETED',70000,'paid short shipment completes');assert.equal(stock(done,'oak_log'),64+6,'Restricted paid long cargo must not arrive for free');for(const id of longIds())frozen(order(done,id),dbOrder(checkpoint1,id));const money=await pair();unchangedMoney(money,{seller:baselineMoney.seller+34,buyer:baselineMoney.buyer-34});return{done,money,receipt:await receipt(paidShort)};
    });
    await stage('second clean stop proves restricted nodes consumed no maintenance and no long receipt exists',async()=>{
      await stopOwned();checkpoint2=probe('travel-reduced');for(const id of longIds()){const now=dbOrder(checkpoint2,id),old=dbOrder(checkpoint1,id);assert.equal(now.state,'PAUSED');assert.equal(now.progress,old.progress);assert.equal(now.travel,old.travel);assert(now.last_period>old.last_period);}assertFrozenDatabase(checkpoint2,checkpoint1,longIds());assertLongOnlyMaintenance(checkpoint2,checkpoint1,longOnlyNodes);noReceipt(checkpoint2,longIds());oneReceipt(checkpoint2,shortBarter);oneReceipt(checkpoint2,shipmentShort);assert.equal(dbOrder(checkpoint2,offeredLong).state,'CANCELLED');assert.equal(dbStock(checkpoint2,setupB.cityId,'oak_log'),70);return{checkpoint:checkpoint2,configuration:configure(1000,2,'waypoints-reduced')};
    });
    server=new Paper(options,directory,3);
    await stage('independent waypoint reduction retains queryable routes and freezes original accepted journey',async()=>{
      await startOwned(3);await players(3);const paused=await poll(server,b,s=>longIds().every(id=>order(s,id)?.status==='PAUSED'&&/节点数/.test(order(s,id).reason)),30000,'independent waypoint limit');for(const id of longIds())frozen(order(paused,id),dbOrder(checkpoint1,id));const from=a.messages.length;await botMessage(a,'/sw route list',new RegExp(longRoute));await waitUntil(()=>a.messages.slice(from).some(line=>line.includes(longRoute)&&line.includes('节点数')),10000,'stored route restriction is visible');const comparison=await fixture(server,'exp-operations',a,setupB.cityId);assert.deepEqual(comparison.routePlans.map(row=>row.routeId),[shortRoute]);return{paused,comparison,receipt:originalReceipt(await receipt(paidLong))};
    });
    await stage('both ordinary managers reroute one accepted barter while old route can disable safely',async()=>{
      const disabled=await botMessage(a,`/sw route disable ${longRoute}`,/商路已停用/),enableDenied=await botMessage(a,`/sw route enable ${longRoute}`,/节点数/);const alternatives=await botMessage(a,`/sw trade alternatives ${reroutedBarter}`,/已登记的同方向替代商路/);const text=await botMessage(a,`/sw trade reroute ${reroutedBarter} ${shortRoute}`,/改道提议 [0-9a-f-]{36}/);rerouteId=text.match(/改道提议 ([0-9a-f-]{36})/)[1];const wrongSide=await botMessage(a,`/sw trade reroute-accept ${rerouteId}`,/需要城市管理权限/);await botMessage(b,`/sw trade reroute-accept ${rerouteId}`,/改道请求 .* APPLIED/);const moving=await poll(server,b,s=>order(s,reroutedBarter)?.progressedPeriods>=1,30000,'real movement on accepted alternative');assert.equal(order(moving,reroutedBarter).routeId,shortRoute);assert.equal(order(moving,reroutedBarter).travelPeriods,shortPeriods);const previous=order(moving,reroutedBarter).progressedPeriods;await botMessage(b,`/sw trade reroute-accept ${rerouteId}`,/改道请求 .* APPLIED/);const replay=await state(server,b);assert(order(replay,reroutedBarter).progressedPeriods>=previous,'Applied proposal replay reset the journey');const done=await poll(server,b,s=>order(s,reroutedBarter)?.status==='COMPLETED',70000,'rerouted same escrow finishes');for(const id of [shipmentLong,keptBarter])frozen(order(done,id),dbOrder(checkpoint1,id));assert.equal(stock(done,'oak_log'),70);return{disabled,enableDenied,alternatives,wrongSide,moving,replay,done,proposal:await fixture(server,'exp-reroute-proof',a,rerouteId)};
    });
    await stage('third normal stop preserves original paid cargo and one applied reroute without extra charges',async()=>{
      await stopOwned();checkpoint3=probe('waypoints-reduced');assertFrozenDatabase(checkpoint3,checkpoint1,[shipmentLong,keptBarter]);assertLongOnlyMaintenance(checkpoint3,checkpoint1,longOnlyNodes);for(const id of [shipmentLong,keptBarter]){assert.equal(dbOrder(checkpoint3,id).progress,dbOrder(checkpoint1,id).progress);assert.equal(dbOrder(checkpoint3,id).travel,dbOrder(checkpoint1,id).travel);}noReceipt(checkpoint3,[shipmentLong,keptBarter]);oneReceipt(checkpoint3,reroutedBarter);assert.equal(checkpoint3.rows.logistics_reroute.find(row=>row.id===rerouteId).state,'APPLIED');assert.equal(checkpoint3.rows.logistics_route.find(row=>row.id===longRoute).enabled,0);return{checkpoint:checkpoint3,configuration:configure(1000,16,'restored')};
    });
    server=new Paper(options,directory,4);
    await stage('restored configuration leaves disabled route disabled until its real manager enables it',async()=>{
      await startOwned(4);await players(4);const restored=await state(server,b);assert.equal(route(restored,longRoute).enabled,false);for(const id of [shipmentLong,keptBarter])frozen(order(restored,id),dbOrder(checkpoint1,id));const enabledAtPeriod=restored.currentPeriod;const reply=await botMessage(a,`/sw route enable ${longRoute}`,/商路已启用/);const resumed=await poll(server,b,s=>order(s,shipmentLong)?.progressedPeriods>dbOrder(checkpoint1,shipmentLong).progress,30000,'original paid journey resumes');assert.equal(order(resumed,shipmentLong).travelPeriods,dbOrder(checkpoint1,shipmentLong).travel);assert.equal(order(resumed,keptBarter).travelPeriods,dbOrder(checkpoint1,keptBarter).travel);for(const id of [shipmentLong,keptBarter]){const actual=order(resumed,id),before=dbOrder(checkpoint1,id);assert(actual.progressedPeriods-before.progress<=Math.max(1,actual.lastPeriod-enabledAtPeriod+1),'Restart must not credit offline movement before the real enable command');}return{restored,reply,resumed,receipt:originalReceipt(await receipt(paidLong))};
    });
    await stage('original paid receipt and physical escrow complete once with exact conserved money and goods',async()=>{
      const done=await poll(server,b,s=>[shipmentLong,keptBarter,reroutedBarter,shortBarter,shipmentShort].every(id=>order(s,id)?.status==='COMPLETED'),160000,'original frozen full journeys finish');const current=await pair();assert.equal(stock(current.b,'oak_log'),81);assert.equal(stock(current.a,'oak_log'),47);assert.equal(stock(current.b,'bread'),279);assert.equal(stock(current.a,'bread'),233);assert.equal(stock(current.b,'iron_ingot'),122);assert.equal(stock(current.a,'iron_ingot'),134);unchangedMoney(current,{seller:baselineMoney.seller+34,buyer:baselineMoney.buyer-34});const replay=await receipt(paidLong,true);assert.equal(replay.replay.status,'CONFIRMED');assert.equal(Number(replay.payerBefore),Number(replay.payerAfter));assert.equal(Number(replay.receiverBefore),Number(replay.receiverAfter));assert.deepEqual(receiptPayload(replay.receipt),receiptPayload(firstReceipt.receipt));const completedPeriod=Math.max(...[shipmentLong,keptBarter].map(id=>order(done,id).completedPeriod));const later=await poll(server,b,s=>s.city.lastPeriod>completedPeriod,30000,'one later committed period without duplicate arrival');assert.equal(stock(later,'oak_log'),81);assert.equal(stock(later,'bread'),279);return{done,current,replay,later};
    });
    await stage('final clean shutdown independently verifies five unique completion receipts and two paid handoffs',async()=>{
      await stopOwned();checkpoint4=probe('restored');for(const id of [shipmentLong,keptBarter,reroutedBarter,shortBarter,shipmentShort]){assert.equal(dbOrder(checkpoint4,id).state,'COMPLETED');oneReceipt(checkpoint4,id);}assert.equal(checkpoint4.rows.logistics_receipt.length,5);assert.equal(checkpoint4.rows.logistics_paid_shipment.length,2);assert.equal(checkpoint4.rows.market_purchase.length,2);assert.equal(checkpoint4.rows.market_history.length,2);for(const id of [paidLong,paidShort])assert.equal(checkpoint4.rows.market_purchase.find(row=>row.id===id).state,'SHIPPED');assert.equal(checkpoint4.rows.logistics_reroute.filter(row=>row.id===rerouteId&&row.state==='APPLIED').length,1);assert.equal(dbStock(checkpoint4,setupB.cityId,'oak_log'),81);assert.equal(dbStock(checkpoint4,setupA.cityId,'oak_log'),47);return checkpoint4;
    });
    evidence.status='passed';evidence.finishedAt=new Date().toISOString();evidence.ids={shortRoute,longRoute,paidLong,paidShort,listingLong,listingShort,shipmentLong,shipmentShort,keptBarter,reroutedBarter,offeredLong,shortBarter,rerouteId};save();console.log(`PASS ${evidence.steps.length} lifecycle logistics stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(error.stack);process.exitCode=1;}
  finally{
    try{await stopSampler();}catch(error){evidence.samplingError=error.stack;evidence.status='failed';process.exitCode=1;}
    try{await closePlayers();}catch(error){evidence.status='failed';evidence.playerCleanupError=error.stack;process.exitCode=1;}
    if(server.child)try{await server.stop();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;process.exitCode=1;}
    try{if(!server.child){fs.writeFileSync(logisticsFile,originalLogistics);evidence.originalConfigurationRestored=hash(logisticsFile)===evidence.configSha256['logistics.yml'];assert(evidence.originalConfigurationRestored,'Original logistics bytes were not restored');}else evidence.originalConfigurationRestored=false;}catch(error){evidence.status='failed';evidence.configurationRestoreError=error.stack;evidence.originalConfigurationRestored=false;process.exitCode=1;}
    save();fs.writeFileSync(path.join(options.runtime,'lifecycle-logistics-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');
  }
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

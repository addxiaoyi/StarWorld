#!/usr/bin/env node
'use strict';

// Copy an accepted, stopped 1.4 progression world; prove market migration and receipt replay on Paper.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {Paper,connect,properties,hash,requireFile,waitUntil}=require('./acceptance.cjs');
const {sqliteSnapshot,assertMarketMigration,assertCorridorMigration}=require('./upgrade-10-acceptance.cjs');
const MARKER='StarWorld isolated Paper smoke runtime v1';
const read=file=>JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));
const omit=new Set(['logs','crash-reports','.paper-remapped','session.lock','PREPARATION.json','UNIT-EVIDENCE.json']);
function argumentsOf(argv){
  const args={};for(let i=0;i<argv.length;i++){
    if(argv[i]==='--help'){args.help=true;continue;}
    assert(['--source-evidence','--runtime','--jar','--java','--python','--port','--client-deps'].includes(argv[i]),`Unknown option ${argv[i]}`);
    const key=argv[i].slice(2);assert(!Object.hasOwn(args,key)&&argv[i+1]&&!argv[i+1].startsWith('--'),`Duplicate or missing option ${argv[i]}`);args[key]=argv[++i];
  }return args;
}

function inputs(directory){
  const files=[];
  function walk(folder){for(const entry of fs.readdirSync(folder,{withFileTypes:true})){
    assert(!entry.isSymbolicLink(),'Linked source input is not allowed');
    if(omit.has(entry.name)||entry.name.endsWith('-acceptance')||/\.(log|pid|hprof)$|-(wal|shm)$/.test(entry.name))continue;
    const file=path.join(folder,entry.name);if(entry.isDirectory())walk(file);else{assert(entry.isFile());files.push({relative:path.relative(directory,file),sha256:hash(file),bytes:fs.statSync(file).size});}
  }}walk(directory);return files.sort((a,b)=>a.relative.localeCompare(b.relative));
}
function unchanged(root,files){for(const file of files)assert.equal(hash(path.join(root,file.relative)),file.sha256,`Source changed: ${file.relative}`);}
function conserved(before,after,version){
  assert.equal(after.pluginVersion,version);assert.equal(after.world.integrity,'ok');assert.equal(after.core.integrity,'ok');assert.deepEqual(after.world.foreignKeys,[]);
  const expectedTables=new Set(before.world.tables);
  if(Number(version.split('.')[1])>=7)expectedTables.add('logistics_corridor');
  if(Number(version.split('.')[1])>=8)expectedTables.add('market_refund');
  if(Number(version.split('.')[1])>=9)expectedTables.add('market_quote');
  assert.deepEqual([...after.world.tables].sort(),[...expectedTables].sort());
  assertMarketMigration(before,after,version);assertCorridorMigration(before,after,version);
  const queues=new Set(before.world.rows.starworld_dispatch_cursor.map(row=>row.queue));
  if(Number(version.split('.')[1])>=8)queues.add('starworld:market-refund');
  const cursors=after.world.rows.starworld_dispatch_cursor;
  assert.equal(cursors.length,queues.size);assert.deepEqual(cursors.map(row=>row.queue).sort(),[...queues].sort());
  assert(cursors.every(row=>['FRESH','DUE'].includes(row.next_lane)));
  for(const table of ['market_delivery_receipt','market_delivery_stat','market_delivery_clock','logistics_paid_shipment','logistics_order','logistics_receipt'])
    assert.deepEqual(after.world.rows[table],before.world.rows[table],`Migrated market changed ${table}`);
  const treasury=rows=>rows.filter(r=>r.property_key!=='__starcore_revision__');
  assert.deepEqual(treasury(after.core.rows.starcore_treasury_state),treasury(before.core.rows.starcore_treasury_state),'Migration or replay changed real national money');
  for(const old of before.world.rows.stock.filter(r=>r.item==='minecraft:oak_log'))
    assert.deepEqual(after.world.rows.stock.find(r=>r.city===old.city&&r.item===old.item),old,'Completed old shipment delivered again');
}
async function fixture(server,action,bot,id){
  const from=server.lines.length;server.command(`swfixture ${action} ${bot.username} ${id}`);
  return waitUntil(()=>{server.check();if(bot.failure)throw bot.failure;for(const line of server.lines.slice(from)){
    assert(!line.includes('SW_FIXTURE_FAILED'),line);const at=line.indexOf('SW_FIXTURE_EXPANSION ');if(at<0)continue;
    const result=JSON.parse(line.slice(at+21));if(result.action===action&&result.player===bot.username)return result.observed;
  }return false;},20000,'original market receipt replay');
}
async function replay(server,buyer,purchase){
  const value=await fixture(server,'exp-market-replay',buyer,purchase.id);
  assert.equal(value.replay.status,'CONFIRMED');assert.equal(value.receipt.transactionId,purchase.transaction_id);
  assert.equal(Number(value.receipt.amount),Number(purchase.total));assert.equal(Number(value.payerBefore),Number(value.payerAfter));
  assert.equal(Number(value.receiverBefore),Number(value.receiverAfter));return value;
}
const inventory=bot=>bot.inventory.items().map(i=>({name:i.name,count:i.count,slot:i.slot})).sort((a,b)=>a.slot-b.slot);

async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log('node market-upgrade-acceptance.cjs --source-evidence <passed-1.4-progression/evidence.json> --runtime <new-owned-copy> --jar <1.5+jar> --java <java21> --python <python3> --port <loopback-port> --client-deps <mineflayer>');return;}
  for(const key of ['source-evidence','runtime','jar','java','python','port','client-deps'])assert(args[key],`Missing --${key}`);
  const sourceEvidence=requireFile(args['source-evidence']),source=path.dirname(path.dirname(path.dirname(sourceEvidence))),old=read(sourceEvidence);
  const runtime=path.resolve(args.runtime),jar=requireFile(args.jar),python=requireFile(args.python),java=requireFile(args.java),port=Number(args.port);
  assert(!fs.existsSync(runtime),'Preserve previous attempts; target must be new');assert(!runtime.startsWith(source+path.sep)&&!source.startsWith(runtime+path.sep));
  assert(Number.isInteger(port)&&port>=1024&&port<=65535);assert.equal(path.basename(path.dirname(path.dirname(sourceEvidence))),'progression-acceptance');
  assert.equal(old.status,'passed');assert.equal(old.suite,'real-paper-progression');assert.equal(old.steps.length,20);assert(old.steps.every(s=>s.status==='passed'));
  for(const key of ['error','cleanupError','playerCleanupError'])assert(!Object.hasOwn(old,key));
  assert.equal(fs.readFileSync(path.join(source,'.starworld-smoke-runtime'),'utf8').trim(),MARKER);
  assert.match(fs.readFileSync(path.join(path.dirname(sourceEvidence),'paper-run-2.log'),'utf8'),/All dimensions are saved/);
  for(const [name,sha]of Object.entries(old.pluginJars))assert.equal(hash(path.join(source,'plugins',name)),sha);
  const props=properties(path.join(source,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');
  assert.equal(properties(path.join(source,'eula.txt')).eula,'true');
  const before=sqliteSnapshot(python,source);assert.equal(before.pluginVersion,'1.4.0');assert.equal(before.world.integrity,'ok');assert.equal(before.core.integrity,'ok');
  assert.deepEqual(before.world.foreignKeys,[]);assert.equal(before.world.rows.market_schema[0].version,1);
  assert.equal(before.world.rows.market_purchase.length,1);const purchase=before.world.rows.market_purchase[0];assert.equal(purchase.state,'SHIPPED');
  assert.equal(purchase.id,old.ids.purchaseId);assert.equal(before.world.rows.market_history.length,1);assert.equal(before.world.rows.market_delivery_receipt.length,1);
  const unit=read(path.join(__dirname,'../../docs/evidence/unit-tests.json'));assert.equal(unit.artifact_sha256,hash(jar));assert(unit.totals.tests>0);assert.equal(unit.totals.failures+unit.totals.errors+unit.totals.skipped,0);
  assert.match(unit.version,/^1\.(?:[5-9]|[1-9]\d+)\.\d+$/);
  const sourceFiles=inputs(source),required=sourceFiles.reduce((sum,f)=>sum+f.bytes,0)+1024**3,space=fs.statfsSync(path.dirname(runtime));assert(space.bavail*space.bsize>required,'Insufficient disk space for preserved migration copy');
  fs.mkdirSync(runtime);
  for(const file of sourceFiles){const from=path.join(source,file.relative),to=path.join(runtime,file.relative);fs.mkdirSync(path.dirname(to),{recursive:true});
    // Bootstrap JARs are immutable. All world, plugin, database and configuration files are independent copies.
    if(/^(cache|libraries|versions)[/\\]/.test(file.relative)&&file.relative.endsWith('.jar'))fs.linkSync(from,to);else fs.copyFileSync(from,to);
  }
  unchanged(source,sourceFiles);unchanged(runtime,sourceFiles);
  fs.copyFileSync(jar,path.join(runtime,'plugins/StarWorld.jar'));
  const settings=fs.readFileSync(path.join(runtime,'server.properties'),'utf8');assert.equal((settings.match(/^server-port=/gm)||[]).length,1);
  fs.writeFileSync(path.join(runtime,'server.properties'),settings.replace(/^server-port=.*$/m,`server-port=${port}`));
  const preparation={schema:1,source,sourceEvidence,sourceEvidenceSha256:hash(sourceEvidence),sourceFiles,sourceSnapshot:before,targetVersion:unit.version,targetPluginSha256:hash(jar),changedFiles:['plugins/StarWorld.jar','server.properties'],eulaScope:'copied existing choice for project isolated loopback tests'};
  fs.writeFileSync(path.join(runtime,'UPGRADE-PROVENANCE.json'),JSON.stringify(preparation,null,2)+'\n');
  const directory=path.join(runtime,'market-upgrade-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const options={java,paper:requireFile(path.join(runtime,'paper.jar')),runtime,port,jvmArgs:['-Xms128m','-Xmx768m','-Xss512k','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-Dio.netty.eventLoopThreads=2']};
  const client=createRequire(path.resolve(args['client-deps'],'package.json')),mineflayer=client('mineflayer');
  const evidence={schema:1,suite:'real-paper-market-upgrade',status:'running',startedAt:new Date().toISOString(),version:unit.version,sourceVersion:before.pluginVersion,
    artifactSha256:hash(jar),runnerSha256:hash(__filename),probeSha256:hash(path.join(__dirname,'upgrade-10-acceptance.cjs')),acceptanceHelperSha256:hash(path.join(__dirname,'acceptance.cjs')),
    paperSha256:hash(options.paper),provenanceSha256:hash(path.join(runtime,'UPGRADE-PROVENANCE.json')),jvmArgs:options.jvmArgs,
    pluginJars:Object.fromEntries(fs.readdirSync(path.join(runtime,'plugins')).filter(name=>name.endsWith('.jar')).map(name=>[name,hash(path.join(runtime,'plugins',name))])),
    configSha256:Object.fromEntries(fs.readdirSync(path.join(runtime,'plugins/StarWorld')).filter(name=>name.endsWith('.yml')).map(name=>[name,hash(path.join(runtime,'plugins/StarWorld',name))])),steps:[]};
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,run)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();try{step.observed=await run();step.status='passed';console.log(`[PASS] ${name}`);}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),seller,buyer,initialInventory,first;
  const start=async(run)=>{await server.start();seller=await connect(mineflayer,{...options,username:'StarWorldPA'},directory,`${run}-a`);buyer=await connect(mineflayer,{...options,username:'StarWorldPB'},directory,`${run}-b`);return{seller:inventory(seller),buyer:inventory(buyer)};};
  const close=async()=>{for(const bot of [seller,buyer])if(bot){bot.quit();await bot.finishTranscript();}seller=buyer=null;if(server.child)await server.stop();};
  try{
    await stage('stopped real 1.4 market and copied state match their original fingerprints',async()=>({sourceEvidenceSha256:hash(sourceEvidence),before,purchase}));
    await stage('first upgraded Paper start restores original buyers sellers and inventories',async()=>{initialInventory=await start(1);return initialInventory;});
    await stage('original national payment receipt replays without charging after migration',()=>replay(server,buyer,purchase));
    await stage('first shutdown proves exact market migration and preserved delivery statistics',async()=>{await close();first=sqliteSnapshot(python,runtime);conserved(before,first,unit.version);unchanged(source,sourceFiles);return first;});
    server=new Paper(options,directory,2);
    await stage('second start preserves inventories and still replays the same national payment',async()=>{const items=await start(2);assert.deepEqual(items,initialInventory);return{items,replay:await replay(server,buyer,purchase)};});
    await stage('second shutdown keeps migrated escrow history money and completed cargo unchanged',async()=>{await close();const after=sqliteSnapshot(python,runtime);conserved(before,after,unit.version);conserved(first,after,unit.version);unchanged(source,sourceFiles);return after;});
    evidence.status='passed';console.log(`PASS ${evidence.steps.length} market upgrade stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;console.error(error.stack);process.exitCode=1;}
  finally{try{await close();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;process.exitCode=1;}evidence.finishedAt=new Date().toISOString();save();fs.writeFileSync(path.join(runtime,'market-upgrade-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});
module.exports={conserved};

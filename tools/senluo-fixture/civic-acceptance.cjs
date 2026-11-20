#!/usr/bin/env node
'use strict';

// Independent final-1.0 civic extension of all fifteen original SenLuo gameplay stages.
// Manages only its own prepared localhost child process. Never imports or rewrites the original suite.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {randomUUID,createHash}=require('node:crypto');
const {createRequire}=require('node:module');
const {setTimeout:delay}=require('node:timers/promises');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('../paper-client/acceptance.cjs');
const SHA=/^[a-f0-9]{64}$/;
function json(file){return JSON.parse(fs.readFileSync(requireFile(file),'utf8').replace(/^\uFEFF/,''));}
function localFile(root,relative){assert(!path.isAbsolute(relative)&&!relative.split(/[\\/]/).includes('..'),`Unsafe preparation path: ${relative}`);return requireFile(path.join(root,relative));}
function filesUnder(root,prefix=''){return fs.readdirSync(path.join(root,prefix),{withFileTypes:true}).flatMap(entry=>{const relative=prefix?`${prefix}/${entry.name}`:entry.name;assert(!entry.isSymbolicLink(),`Linked preparation input: ${relative}`);return entry.isDirectory()?filesUnder(root,relative):[relative];}).sort();}
function verifyPreparation(options){
  const preparationFile=requireFile(path.join(options.runtime,'PREPARATION.json')),preparation=json(preparationFile);
  assert.equal(preparation.schema,1);assert(['prepared','running'].includes(preparation.status),'Bind the current fixture before acceptance');
  assert.equal(path.resolve(preparation.runtime),options.runtime);assert.match(preparation.upstreamAuditCommit,/^[a-f0-9]{40}$/);
  assert.match(preparation.starWorldVersion,/^1\.0\.\d+$/,'Final 1.0 product required');assert.match(preparation.starWorldSha256,SHA);
  assert.match(preparation.cookeryJarSha256,SHA);assert.match(preparation.kitchenPackSha256,SHA);
  assert(preparation.unitEvidence,'A bound final-product clean unit evidence document is required');
  const unitFile=localFile(options.runtime,preparation.unitEvidence.file);assert.match(preparation.unitEvidence.sha256,SHA);assert.equal(hash(unitFile),preparation.unitEvidence.sha256);
  const unitEvidence=json(unitFile);assert.equal(unitEvidence.version,preparation.starWorldVersion);assert.equal(unitEvidence.artifact_sha256,preparation.starWorldSha256);
  assert(unitEvidence.totals.tests>0);for(const key of ['failures','errors','skipped'])assert.equal(unitEvidence.totals[key],0,`Clean final-product unit totals: ${key}`);
  for(const suite of ['dev.starworld.civic.CivicNeedsServiceTest','dev.starworld.civic.CivicNeedsDefinitionsTest'])assert(unitEvidence.suites.some(row=>row.suite===suite&&row.tests>0&&row.failures===0&&row.errors===0&&row.skipped===0),`Missing clean civic unit suite: ${suite}`);
  assert.equal(preparation.worldsCopied,0);assert.equal(preparation.authorityFilesCopied,0);
  assert(preparation.processStarted===false||preparation.processStarted===true);assert.equal(preparation.testTuning.serverStarts,2);
  assert.equal(preparation.testTuning.host,'127.0.0.1');assert.equal(preparation.testTuning.port,options.port);
  assert(preparation.testTuning.minimumFreeCommitBytes>=1610612736);assert.deepEqual(preparation.testTuning.jvmArgs,options.jvmArgs);
  for(const name of ['_JAVA_OPTIONS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'])assert(!process.env[name],`Unexpected JVM injection: ${name}`);
  const plugins=path.join(options.runtime,'plugins'),actualJars=fs.readdirSync(plugins).filter(name=>name.endsWith('.jar')).sort();
  assert.equal(actualJars.length,9);for(const required of ['StarCore.jar','StarWorld.jar','StarWorldFixture.jar','StarWorldSenLuoFixture.jar'])assert(actualJars.includes(required));
  assert.equal(preparation.installedPlugins.length,9);assert.deepEqual(preparation.installedPlugins.map(row=>row.file).sort(),actualJars);
  const pluginJars={};for(const row of preparation.installedPlugins){assert.equal(path.basename(row.file),row.file);assert.match(row.sha256,SHA);const file=localFile(plugins,row.file);pluginJars[row.file]=hash(file);assert.equal(pluginJars[row.file],row.sha256,`Prepared JAR drift: ${row.file}`);assert.equal(fs.statSync(file).size,row.bytes);}
  assert.equal(pluginJars['StarWorld.jar'],preparation.starWorldSha256);
  const cookery=preparation.installedPlugins.filter(row=>/^KaleidoscopeCookeryPlugin-.*\.jar$/.test(row.file));assert.equal(cookery.length,1);assert.equal(cookery[0].sha256,preparation.cookeryJarSha256);
  assert.equal(pluginJars['StarWorldSenLuoFixture.jar'],preparation.senluoFixtureSha256);assert.match(preparation.senluoFixtureSha256,/^[a-f0-9]{64}$/);
  for(const required of ['config.yml','definitions.yml','evolution.yml','exports.yml','life.yml','logistics.yml','market.yml','disaster-response.yml','public-works.yml','civic-needs.yml'])assert(Object.hasOwn(preparation.configSha256,required),`Unbound final-product configuration: ${required}`);
  assert.deepEqual(Object.keys(preparation.configSha256).sort(),fs.readdirSync(path.join(plugins,'StarWorld')).filter(name=>name.endsWith('.yml')).sort());
  for(const [name,expected]of Object.entries(preparation.configSha256))assert.equal(hash(localFile(path.join(plugins,'StarWorld'),name)),expected,`Prepared configuration drift: ${name}`);
  const provenanceFile=localFile(options.runtime,'UPSTREAM-PROVENANCE.json'),provenance=json(provenanceFile);
  assert.match(preparation.newAssetPreparation.upstreamProvenanceSha256,SHA);assert.equal(hash(provenanceFile),preparation.newAssetPreparation.upstreamProvenanceSha256);
  assert.equal(provenance.commit,preparation.upstreamAuditCommit);assert.equal(provenance.cookeryJar.sha256,preparation.cookeryJarSha256);assert.equal(provenance.kitchenPack.sha256,preparation.kitchenPackSha256);
  assert.match(preparation.upstreamProductionSourceCommit,/^[a-f0-9]{40}$/);assert.equal(provenance.productionSourceCommit,preparation.upstreamProductionSourceCommit);
  assert(provenance.auditedHeadChanges.includes('docs/feature-ledger.json'));assert.deepEqual(provenance.auditedHeadChanges,provenance.auditHeadEvidence.files);assert.equal(provenance.auditHeadEvidence.sha,preparation.upstreamAuditCommit);assert.equal(provenance.auditHeadEvidence.base,preparation.upstreamProductionSourceCommit);
  const archiveName=path.win32.basename(provenance.kitchenPack.path);assert(archiveName.endsWith('.zip'));
  assert.equal(hash(localFile(options.runtime,`asset-inputs/${archiveName}`)),preparation.kitchenPackSha256);
  const assetRoot=path.join(options.runtime,'asset-inputs',archiveName.slice(0,-4)),manifestFile=localFile(assetRoot,'manifest.json'),manifest=json(manifestFile);
  assert.deepEqual(manifest,provenance.kitchenPack.manifest);assert.equal(hash(manifestFile),preparation.assetManifestSha256);
  const assetFiles=Object.keys(manifest.files).sort();assert.equal(assetFiles.length,2399);
  assert.deepEqual(filesUnder(assetRoot),[...assetFiles,'manifest.json'].sort());
  const activeResources=path.join(plugins,'CraftEngine/resources');
  assert.equal(path.resolve(preparation.newAssetPreparation.activeCookeryResources),path.join(activeResources,'cookery_kitchen'));
  assert.deepEqual(filesUnder(path.join(activeResources,'cookery_kitchen')),assetFiles.map(name=>{assert(name.startsWith('cookery_kitchen/'));return name.slice('cookery_kitchen/'.length);}).sort());
  for(const [name,expected]of Object.entries(manifest.files)){assert.equal(hash(localFile(assetRoot,name)),expected,`Extracted pack drift: ${name}`);assert.equal(hash(localFile(activeResources,name)),expected,`Loaded pack drift: ${name}`);}
  assert(preparation.newAssetPreparation.internalFiles>0);assert.equal(filesUnder(path.join(activeResources,'internal')).length,preparation.newAssetPreparation.internalFiles);
  assert(Object.keys(preparation.dependencyFilesSha256).length>=55,'Dependency/configuration fingerprints required');
  for(const [name,expected]of Object.entries(preparation.dependencyFilesSha256))assert.equal(hash(localFile(options.runtime,name)),expected,`Prepared dependency drift: ${name}`);
  assert.equal(hash(options.paper),preparation.paperSha256);assert.equal(hash(options.java),preparation.javaSha256);
  const level=properties(path.join(options.runtime,'server.properties'))['level-name'];assert(level&&path.basename(level)===level);
  for(const name of [level,`${level}_nether`,`${level}_the_end`,'world','world_nether','world_the_end'])assert(!fs.existsSync(path.join(options.runtime,name)),`Fresh world required: ${name}`);
  for(const folder of ['StarWorld','StarCore'])for(const name of filesUnder(path.join(plugins,folder)))assert(!/\.(?:db|sqlite|sqlite3)(?:-|$)/i.test(name),`Fresh database required: ${folder}/${name}`);
  for(const folder of ['senluo-acceptance','civic-senluo-acceptance'])assert(!fs.existsSync(path.join(options.runtime,folder)),'Preserve previous acceptance; prepare another fresh runtime');
  return{preparationFile,preparation,pluginJars,unitEvidence,verifiedAssetFiles:assetFiles.length,verifiedDependencyFiles:Object.keys(preparation.dependencyFilesSha256).length};
}
async function main(){
  const cli=process.argv.slice(2),preflightOnly=cli.includes('--preflight'),args=argumentsOf(cli.filter(arg=>arg!=='--preflight'));
  if(args.help){console.log('node --max-old-space-size=128 --max-semi-space-size=4 tools/senluo-fixture/civic-acceptance.cjs --java <java21> --paper <paper132.jar> --runtime <fresh-owned-final1.0-runtime> --client-deps <mineflayer-directory> [--preflight]');return;}
  for(const name of ['java','paper','runtime','client-deps'])assert(args[name],`Missing --${name}`);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),runtime:path.resolve(args.runtime),username:'StarWorldQA',
    jvmArgs:['-Xms128m','-Xmx768m','-Xss512k','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-XX:MaxMetaspaceSize=384m','-Dio.netty.eventLoopThreads=2']};
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const props=properties(path.join(options.runtime,'server.properties'));
  assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');
  assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);
  const worldConfig=path.join(options.runtime,'plugins/StarWorld/config.yml'),lifeConfig=path.join(options.runtime,'plugins/StarWorld/life.yml');
  assert.equal(configuredNumber(worldConfig,'period-seconds'),10);assert.equal(configuredNumber(worldConfig,'event-chance'),0);
  assert.equal(configuredNumber(lifeConfig,'profession-cooldown-periods'),1,'Explicit isolated profession cooldown required');
  assert.equal(configuredNumber(lifeConfig,'minimum-duration-millis'),60000,'Concert duration must remain default');
  const verified=verifyPreparation(options);
  const localRequire=createRequire(path.resolve(args['client-deps'],'package.json')),mineflayer=localRequire('mineflayer');
  if(preflightOnly){console.log(JSON.stringify({status:'prepared',javaStarted:false,productSha256:verified.pluginJars['StarWorld.jar'],preparationSha256:hash(verified.preparationFile),verifiedAssetFiles:verified.verifiedAssetFiles,verifiedDependencyFiles:verified.verifiedDependencyFiles,configurations:Object.keys(verified.preparation.configSha256).length}));return;}
  const directory=path.join(options.runtime,'civic-senluo-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,status:'running',expectedStages:30,startedAt:new Date().toISOString(),minecraft:'1.21.11',version:verified.preparation.starWorldVersion,senluoAuditCommit:verified.preparation.upstreamAuditCommit,senluoProductionSourceCommit:verified.preparation.upstreamProductionSourceCommit,paperSha256:hash(options.paper),pluginJars:verified.pluginJars,unitEvidence:verified.unitEvidence,assetPreparation:{fileSha256:hash(verified.preparationFile),...verified.preparation},preflight:{verifiedAssetFiles:verified.verifiedAssetFiles,verifiedDependencyFiles:verified.verifiedDependencyFiles,freshWorldAndDatabases:true},
    scope:'All fifteen original real CE gameplay assertions plus three civic needs through player commands/GUI, declared inventory seeds, two different maintained CE chairs, real sixty-second instrument/listener concert, public immutable receipt/claim UUIDs, restart and replay. No SQL mutations, upstream kmusic completion, resource-pack visual/audio or Folia claim.',
    testTuning:{periodSeconds:10,randomEventChance:0,professionCooldownPeriods:1,concertMinimumDurationMillis:60000,lifeConfigSha256:hash(lifeConfig),jvmArgs:options.jvmArgs,nodeArgs:process.execArgv,nodeVersion:process.version,paperSparkEnabled:false},steps:[]};
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,run)=>{console.log(`RUN ${name}`);const row={name,startedAt:new Date().toISOString()};evidence.steps.push(row);save();try{row.result=await run();row.status='passed';}catch(error){row.status='failed';row.error=error.stack;throw error;}finally{row.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),bot,listener,preRestart,concertId,civicPreRestart;
  const needs={};
  async function fixture(action,extra=''){
    const from=server.lines.length;server.command(`swsenluo ${action} StarWorldQA ${extra}`);
    const raw=await waitUntil(()=>{server.check();const rows=server.lines.slice(from);const failed=rows.find(x=>/SW_SENLUO_FAILED/.test(x));if(failed)throw new Error(failed);return rows.find(x=>x.includes('SW_SENLUO_JSON ')&&x.includes(`"action":"${action}"`));},20000,`SenLuo fixture ${action}`);
    return JSON.parse(raw.slice(raw.indexOf('SW_SENLUO_JSON ')+'SW_SENLUO_JSON '.length));
  }
  const snapshot=()=>fixture('snapshot');
  async function poll(predicate,timeout=35000){let latest;return waitUntil(async()=>{latest=await snapshot();return predicate(latest)?latest:false;},timeout,'live life state',()=>server.check());}
  async function profession(id){const before=await snapshot();if(before.profession.profession!=='none'&&before.profession.profession!==id)await poll(s=>s.period>before.profession.changedPeriod,20000);await botMessage(bot,`/sw profession ${id}`,/生活职业：/);return snapshot();}
  const civicSnapshot=async()=>{const state=await fixture('civic-snapshot');assert.equal(state.apiVersion,verified.preparation.starWorldVersion);assert.equal(state.apiServiceParity,true);assert.equal(state.life.city,state.cityId);assert.equal(state.life.nation,state.nationId);assert.equal(state.profile.city,state.cityId);for(const request of state.requests){assert.equal(request.cityId,state.cityId);assert.equal(request.nationId,state.nationId);}return state;};
  async function civicPoll(predicate,timeout=35000){const end=Date.now()+timeout;let latest;while(Date.now()<end){latest=await civicSnapshot();if(predicate(latest))return latest;server.check();await delay(900);}throw new Error(`Civic wait timed out: ${JSON.stringify(latest)}`);}
  async function nextCivicPeriod(period){return civicPoll(s=>s.period>period&&s.profile.lastPeriod===s.period&&s.life.decorationPeriod===s.period,40000);}
  function needOf(state,id){const request=state.requests.find(row=>row.id===id);assert(request,`Missing immutable civic request ${id}`);return request;}
  function receiptOf(state,id,count,costs){const receipt=state.receipts[id];assert(receipt,`Missing immutable civic completion receipt ${id}`);assert.equal(receipt.requestId,id);assert.equal(receipt.cityId,state.cityId);assert.equal(receipt.nationId,state.nationId);assert.equal(receipt.applied,false,'Read-only API receipt must not claim a new mutation');assert.equal(receipt.claimedActionUUIDs.length,count);assert.equal(new Set(receipt.claimedActionUUIDs).size,count);assert.deepEqual(receipt.paidItems,costs);assert(receipt.actualStability>=0&&receipt.actualStability<=2);assert(receipt.actualProsperity>=0&&receipt.actualProsperity<=3);assert.equal(state.history.filter(row=>row.requestId===id&&row.kind==='COMPLETED').length,1);assert.equal(needOf(state,id).status,'COMPLETED');return receipt;}
  async function seedCivic(bundle){const before=await civicSnapshot(),seeded=await fixture('civic-seed',bundle);for(const [id,amount]of Object.entries(seeded.fixtureSeededPlayerInventory))await botMessage(bot,`/sw deposit ${id} ${amount}`,/已存入/);const after=await civicSnapshot();for(const [id,amount]of Object.entries(seeded.fixtureSeededPlayerInventory)){assert.equal(after.inventory[id],before.inventory[id]);assert.equal(after.stock[id]||0,(before.stock[id]||0)+amount);}return{before,seeded,after};}
  async function requestNeed(type){const id=randomUUID(),before=await civicSnapshot();const message=await botMessage(bot,`/sw civic request ${type} ${id}`,new RegExp(`城市需求回执 ${id}`));const after=await civicSnapshot(),request=needOf(after,id);assert.equal(request.type||request.definition.id,type);assert.equal(request.status,'OPEN');assert.equal(request.nationId,after.nationId);assert.deepEqual(after.stock,before.stock);needs[type]=id;return{id,request,message,before,after};}
  async function claimNeed(type,count,costs){const id=needs[type],before=await nextCivicPeriod((await civicSnapshot()).period);const message=await botMessage(bot,`/sw civic complete ${id}`,new RegExp(`需求完成回执 ${id}.*新提交=true`));const after=await civicSnapshot(),receipt=receiptOf(after,id,count,costs);assert.equal(after.period,before.period,'Claim crossed a settlement boundary; preserve this run as inconclusive/failed');for(const [item,amount]of Object.entries(costs))assert.equal(after.stock[item],before.stock[item]-amount);assert.equal(after.profile.stability,Math.min(100,before.profile.stability+receipt.actualStability));assert.equal(after.profile.prosperity,Math.min(100,before.profile.prosperity+receipt.actualProsperity));const replayMessage=await botMessage(bot,`/sw civic complete ${id}`,new RegExp(`需求完成回执 ${id}.*新提交=false`));const replay=await civicSnapshot();assert.equal(replay.period,after.period,'Replay crossed a settlement boundary');assert.deepEqual(replay.stock,after.stock);assert.equal(replay.profile.stability,after.profile.stability);assert.equal(replay.profile.prosperity,after.profile.prosperity);assert.deepEqual(receiptOf(replay,id,count,costs),receipt);return{before,message,after,receipt,replayMessage,replay};}
  function decorationActionId(city,nation,period,furniture){const bytes=createHash('md5').update(`decoration|${city}|${nation}|${period}|${furniture}`,'utf8').digest();bytes[6]=(bytes[6]&15)|48;bytes[8]=(bytes[8]&63)|128;const hex=bytes.toString('hex');return`${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;}
  function decorationSources(state,id,furniture){const request=needOf(state,id),receipt=state.receipts[id],matches=[];for(const action of receipt.claimedActionUUIDs){let found;for(let period=request.requestedPeriod+1;period<=request.expiryPeriod;period++)for(const source of furniture)if(decorationActionId(state.cityId,state.nationId,period,source)===action)found={action,source,period};assert(found,`Claimed decoration action does not match an actual maintained test chair: ${action}`);assert(found.period<=state.period);matches.push(found);}assert.equal(new Set(matches.map(row=>row.source)).size,2);return{method:'Audit only: Java nameUUIDFromBytes of the existing LifeService durable decoration identity; never submitted as a receipt',matches};}
  async function openGui(command,title){const opened=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Real civic GUI did not open')),15000);bot.once('windowOpen',window=>{clearTimeout(timer);resolve(window);});});bot.chat(command);const window=await opened;assert.match(typeof window.title==='string'?window.title:JSON.stringify(window.title),title);await delay(250);return window;}
  async function closePlayers(){for(const client of [bot,listener])if(client){client.quit();await client.finishTranscript();}bot=listener=null;}
  try{
    await stage('all optional plugins and actual CE definitions load',async()=>{
      await server.start();bot=await connect(mineflayer,options,directory,1);listener=await connect(mineflayer,{...options,username:'StarWorldEar'},directory,'listener-1');
      const state=await fixture('status');for(const row of Object.values(state.plugins))assert(row.enabled);for(const available of Object.values(state.items))assert(available);assert(state.furnitureLoaded);return state;
    });
    await stage('real country city claims and same-city listener',async()=>{
      await server.fixture('setup');const state=await fixture('audience','StarWorldEar');assert(state.sameCity);server.command('op StarWorldQA');await delay(300);return state;
    });
    await stage('fixture CE stacks are moved from player inventory to warehouse',async()=>{
      const seeded=await fixture('seed');for(const [id,amount]of Object.entries(seeded.fixtureSeededPlayerInventory))await botMessage(bot,`/sw deposit ${id} ${amount}`,/已存入/);
      const after=await snapshot();for(const [id,amount]of Object.entries(seeded.fixtureSeededPlayerInventory)){assert.equal(after.inventory[id],0);assert.equal(after.stock[id],amount);}return{seeded,after};
    });
    await stage('Cookery feast consumes exact real custom goods once',async()=>{
      await profession('chef');const before=await snapshot();await botMessage(bot,'/sw feast cookery',/宴会完成：文化 \+25/);const after=await snapshot();assert.equal(after.stock['kaleidoscopecookery:baozi']||0,0);assert.equal(after.stock['kaleidoscopecookery:oil']||0,0);assert.equal(after.life.culture-before.life.culture,25);assert(after.life.productionMultiplier>=1.08);return{before,after};
    });
    await stage('Tavern feast consumes real wine and bread',async()=>{
      await profession('brewer');const before=await snapshot();await botMessage(bot,'/sw feast tavern',/宴会完成：文化 \+20/);const after=await snapshot();assert.equal(after.stock['kaleidoscope_tavern:wine']||0,0);assert.equal(after.stock['minecraft:bread'],before.stock['minecraft:bread']-4);assert.equal(after.life.culture-before.life.culture,20);return{before,after};
    });
    await stage('real CE furniture is registered through player command',async()=>{
      await profession('artisan');const placed=await fixture('furniture');assert(placed.valid);const aimed=await fixture('aim');assert(aimed.metaEvidence,'Public CE metadata evidence failed');assert(aimed.ceTargetEvidence,'Public CE ray trace evidence failed');
      await botMessage(bot,'/sw decoration register',/装饰已登记：/);const after=await snapshot();assert.equal(after.life.decorations.length,1);assert.equal(after.stock['minecraft:oak_planks'],4);assert(Object.values(after.decorationValidity).every(Boolean));return{placed,aimed,after};
    });
    await stage('maintained decoration earns culture and consumes upkeep',async()=>{
      const before=await snapshot();const after=await poll(s=>s.life.culture>before.life.culture&&s.life.decorationPeriod>before.life.decorationPeriod);assert(after.stock['minecraft:bread']<before.stock['minecraft:bread']);return{before,after};
    });
    await stage('national territory loss pauses valid furniture contribution',async()=>{
      const unclaimed=await fixture('unclaim');assert.equal(unclaimed.claimed,false);assert.equal(unclaimed.furnitureStillValid,true);assert.equal(unclaimed.decorationEvidence,false);
      const first=await snapshot();const after=await poll(s=>s.life.decorationPeriod>=first.life.decorationPeriod+2,45000);assert.equal(after.life.culture,first.life.culture);assert.deepEqual(after.stock,first.stock);assert(Object.values(after.decorationValidity).every(x=>!x));return{unclaimed,first,after};
    });
    await stage('restored territory resumes maintained decoration once',async()=>{
      const reclaimed=await fixture('reclaim');assert.equal(reclaimed.claimed,true);assert.equal(reclaimed.furnitureStillValid,true);assert.equal(reclaimed.decorationEvidence,true);
      const before=await snapshot();const after=await poll(s=>s.life.culture>before.life.culture&&s.life.decorationPeriod>before.life.decorationPeriod);assert(after.stock['minecraft:bread']<before.stock['minecraft:bread']);assert(Object.values(after.decorationValidity).every(Boolean));return{reclaimed,before,after};
    });
    await stage('actual CE furniture removal stops culture and upkeep',async()=>{
      const removed=await fixture('destroy');assert.equal(removed.valid,false);const first=await snapshot();const after=await poll(s=>s.life.decorationPeriod>=first.life.decorationPeriod+2,45000);assert.equal(after.life.culture,first.life.culture);assert.equal(after.stock['minecraft:bread'],first.stock['minecraft:bread']);assert(Object.values(after.decorationValidity).every(x=>!x));return{first,after};
    });
    await stage('real CE instrument and actual listener support sixty-second concert',async()=>{
      await profession('musician');server.command('tp StarWorldEar StarWorldQA');await delay(300);const held=await fixture('hold');assert(held.matches);
      const before=await snapshot(),startedAt=Date.now();const text=await botMessage(bot,'/sw concert start',/已消耗演奏补给。会话：/);concertId=text.match(/会话：([0-9a-f-]{36})/i)[1];
      const notes=[];for(let i=0;i<14;i++){await delay(4700);notes.push(await botMessage(bot,`/sw concert note ${concertId} ${i%5+8}`,/有效音符/));}
      assert(Date.now()-startedAt>=60000);await botMessage(bot,`/sw concert finish ${concertId}`,/演奏结算：文化 \+20/);const after=await snapshot();assert.equal(after.life.culture-before.life.culture,20);assert.equal(after.stock['minecraft:bread'],before.stock['minecraft:bread']-8);return{concertId,notes,before,after,elapsedMillis:Date.now()-startedAt};
    });
    await stage('completed concert replay gives no duplicate culture',async()=>{
      const before=await snapshot();await botMessage(bot,`/sw concert finish ${concertId}`,/演奏结算：文化 \+20/);const after=await snapshot();assert.equal(after.life.culture,before.life.culture);assert.deepEqual(after.stock,before.stock);preRestart=after;return{before,after};
    });
    await stage('civic meals use declared real inventory deposits and a real player request',async()=>{
      const seeded=await seedCivic('meals');const requested=await requestNeed('community_meals');assert.equal(requested.request.definition.actionKind,'feast');assert.equal(requested.request.definition.actionCount,1);assert.deepEqual(requested.request.definition.costs,{'minecraft:wheat':16,'minecraft:bread':4});return{seeded,requested};
    });
    await stage('pre-request Cookery and Tavern contributions cannot complete civic meals',async()=>{
      const request=needOf(await civicSnapshot(),needs.community_meals),before=await nextCivicPeriod(request.requestedPeriod);const message=await botMessage(bot,`/sw civic complete ${request.id}`,/缺少请求后已提交的宴会\/完成演奏生活贡献/);const after=await civicSnapshot();assert.equal(needOf(after,request.id).status,'OPEN');assert(!after.receipts[request.id]);assert.deepEqual(after.stock,before.stock);return{before,message,after};
    });
    await stage('a next-period real Cookery feast generates the civic meal evidence',async()=>{
      await profession('chef');const before=await civicSnapshot();assert(before.period>needOf(before,needs.community_meals).requestedPeriod);const message=await botMessage(bot,'/sw feast cookery',/宴会完成：文化 \+25/);const after=await civicSnapshot();assert.equal(after.stock['kaleidoscopecookery:baozi']||0,0);assert.equal(after.stock['kaleidoscopecookery:oil']||0,0);assert.equal(after.life.culture-before.life.culture,25);assert(after.apiServiceParity);assert.equal(after.apiVersion,verified.preparation.starWorldVersion);return{before,message,after};
    });
    await stage('civic meals consume wheat and bread once with an immutable real-action receipt',async()=>claimNeed('community_meals',1,{'minecraft:wheat':16,'minecraft:bread':4}));
    let firstChair,secondChair;
    await stage('public-space request is backed by declared real planks and a real CE chair registration',async()=>{
      const seeded=await seedCivic('spaces');await profession('artisan');const requested=await requestNeed('public_spaces');assert.equal(requested.request.definition.actionKind,'decoration');assert.equal(requested.request.definition.distinctSources,true);assert.equal(requested.request.definition.actionCount,2);
      firstChair=await fixture('civic-chair','0');assert(firstChair.valid);const aim=await fixture('civic-aim','0');assert(aim.metaEvidence&&aim.ceTargetEvidence);const before=await civicSnapshot(),message=await botMessage(bot,'/sw decoration register',/装饰已登记：/),after=await civicSnapshot();assert.equal(after.stock['minecraft:oak_planks'],before.stock['minecraft:oak_planks']-4);assert(after.life.decorations.some(row=>row.furnitureId===firstChair.uuid));return{seeded,requested,firstChair,aim,before,message,after};
    });
    await stage('one real chair maintained across two periods cannot substitute for two furniture sources',async()=>{
      const before=await civicSnapshot();const first=await civicPoll(s=>s.life.decorationPeriod>before.life.decorationPeriod&&s.life.culture>before.life.culture);const second=await civicPoll(s=>s.life.decorationPeriod>first.life.decorationPeriod&&s.life.culture>first.life.culture);assert.equal(second.decorationValidity[firstChair.uuid],true);assert(second.stock['minecraft:bread']<=before.stock['minecraft:bread']-2);const message=await botMessage(bot,`/sw civic complete ${needs.public_spaces}`,/需请求后两件或更多不同真实家具的已提交维护凭据/);const after=await civicSnapshot();assert.equal(needOf(after,needs.public_spaces).status,'OPEN');assert(!after.receipts[needs.public_spaces]);assert.equal(after.stock['minecraft:oak_planks'],second.stock['minecraft:oak_planks']);return{before,first,second,message,after};
    });
    await stage('a second distinct real CE chair earns maintained contribution before civic completion',async()=>{
      secondChair=await fixture('civic-chair','1');assert(secondChair.valid);assert.notEqual(secondChair.uuid,firstChair.uuid);const aimed=await fixture('civic-aim','1');assert(aimed.metaEvidence&&aimed.ceTargetEvidence);const before=await civicSnapshot(),message=await botMessage(bot,'/sw decoration register',/装饰已登记：/),registered=await civicSnapshot();assert.equal(registered.stock['minecraft:oak_planks'],before.stock['minecraft:oak_planks']-4);const after=await civicPoll(s=>s.life.decorationPeriod>registered.life.decorationPeriod&&s.life.culture>=registered.life.culture+2);assert.equal(after.decorationValidity[firstChair.uuid],true);assert.equal(after.decorationValidity[secondChair.uuid],true);assert(after.stock['minecraft:bread']<=registered.stock['minecraft:bread']-2);return{secondChair,aimed,before,message,registered,after};
    });
    await stage('public spaces debit sixteen real planks once and claim two different furniture sources',async()=>{
      const claimed=await claimNeed('public_spaces',2,{'minecraft:oak_planks':16});return{...claimed,sourceEvidence:decorationSources(claimed.after,needs.public_spaces,[firstChair.uuid,secondChair.uuid])};
    });
    await stage('festival request requires a new completed performance instead of the old concert',async()=>{
      const removed=await fixture('civic-remove');assert.equal(removed.chairs.length,2);assert(removed.chairs.every(row=>row.removed&&!row.valid));await profession('musician');const seeded=await seedCivic('festival'),requested=await requestNeed('festival');const before=await nextCivicPeriod(requested.request.requestedPeriod),message=await botMessage(bot,`/sw civic complete ${needs.festival}`,/缺少请求后已提交的宴会\/完成演奏生活贡献/),after=await civicSnapshot();assert(!after.receipts[needs.festival]);assert.deepEqual(after.stock,before.stock);return{removed,seeded,requested,before,message,after};
    });
    let civicConcertId;
    await stage('an active real CE instrument concert cannot complete the festival before sixty seconds',async()=>{
      server.command('tp StarWorldEar StarWorldQA');await delay(300);const held=await fixture('hold');assert(held.matches);const before=await civicSnapshot(),message=await botMessage(bot,'/sw concert start',/已消耗演奏补给。会话：/);civicConcertId=message.match(/会话：([0-9a-f-]{36})/i)[1];const rejected=await botMessage(bot,`/sw civic complete ${needs.festival}`,/缺少请求后已提交的宴会\/完成演奏生活贡献/),after=await civicSnapshot();assert.equal(after.stock['minecraft:bread'],before.stock['minecraft:bread']-8);assert(after.life.concerts.some(row=>row.id===civicConcertId&&row.state==='active'));assert(!after.receipts[needs.festival]);return{held,before,message,rejected,after};
    });
    await stage('a real sixty-second CE instrument and same-city listener finish the civic festival concert',async()=>{
      const before=await civicSnapshot(),started=Date.now(),notes=[];for(let i=0;i<14;i++){await delay(4700);notes.push(await botMessage(bot,`/sw concert note ${civicConcertId} ${i%5+8}`,/有效音符/));}assert(Date.now()-started>=60000);const message=await botMessage(bot,`/sw concert finish ${civicConcertId}`,/演奏结算：文化 \+20/),after=await civicSnapshot();assert.equal(after.life.culture-before.life.culture,20);assert.equal(after.stock['minecraft:bread'],before.stock['minecraft:bread']);return{before,notes,elapsedMillis:Date.now()-started,message,after};
    });
    await stage('festival consumes real paper and bread once and claims exactly the completed concert UUID',async()=>{
      const claimed=await claimNeed('festival',1,{'minecraft:paper':8,'minecraft:bread':8});assert.deepEqual(claimed.receipt.claimedActionUUIDs,[civicConcertId]);return claimed;
    });
    await stage('real civic status and life GUIs reflect immutable API receipts without extracting display items',async()=>{
      const before=await civicSnapshot();await botMessage(bot,'/sw civic status',new RegExp(needs.festival));const statusWindow=await openGui('/sw civic-menu',/城市需求状态/),status=await fixture('civic-gui');assert.equal(status.size,54);assert.match(status.holder,/GovernanceMenu/);for(const id of Object.values(needs))assert(status.slots.some(row=>row.lore.some(line=>line.includes(id))&&row.lore.some(line=>line.includes('COMPLETED'))));
      const opened=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('Real life contribution GUI did not open')),15000);bot.once('windowOpen',window=>{clearTimeout(timer);resolve(window);});});await bot.clickWindow(48,0,0);const lifeWindow=await opened;assert.match(typeof lifeWindow.title==='string'?lifeWindow.title:JSON.stringify(lifeWindow.title),/已发生生活贡献/);const lifeGui=await fixture('civic-gui'),lifeCard=lifeGui.slots.find(row=>row.slot===10&&row.name==='已提交生活文化');assert(lifeCard);assert(lifeCard.lore.some(line=>line.includes(`累计：${before.life.culture}`)));assert(lifeCard.lore.some(line=>line.includes(`家具登记数量：${before.life.decorations.length}`)));
      const inventoryBefore=bot.inventory.items().reduce((sum,item)=>sum+item.count,0);await bot.clickWindow(10,0,1);await delay(350);assert.equal(bot.inventory.items().reduce((sum,item)=>sum+item.count,0),inventoryBefore);assert.equal(bot.currentWindow?.slots[10]?.name,'jukebox');bot.closeWindow(bot.currentWindow);const after=await civicSnapshot();assert.deepEqual(after.stock,before.stock);assert.deepEqual(after.receipts,before.receipts);assert.equal(after.life.culture,before.life.culture);return{before,statusTitle:statusWindow.title,status,lifeTitle:lifeWindow.title,lifeGui,after};
    });
    await stage('three completed civic needs retain four globally unique real-action claims before shutdown',async()=>{
      civicPreRestart=await civicSnapshot();assert.equal(civicPreRestart.requests.length,3);assert(civicPreRestart.requests.every(row=>row.status==='COMPLETED'));const actions=Object.values(civicPreRestart.receipts).flatMap(row=>row.claimedActionUUIDs);assert.equal(actions.length,4);assert.equal(new Set(actions).size,4);assert.equal(civicPreRestart.history.filter(row=>row.kind==='COMPLETED').length,3);preRestart=await snapshot();return{needs,civicPreRestart,preRestart};
    });
    await stage('clean shutdown persists committed extension and player state',async()=>{await closePlayers();await server.stop();return{clean:true};});
    server=new Paper(options,directory,2);
    await stage('restart restores city culture and cannot replay concert reward',async()=>{
      await server.start();bot=await connect(mineflayer,options,directory,2);const restored=await snapshot();assert.equal(restored.cityId,preRestart.cityId);assert.equal(restored.life.culture,preRestart.life.culture);assert.deepEqual(restored.stock,preRestart.stock);assert.equal(restored.profession.profession,'musician');
      await botMessage(bot,`/sw concert finish ${concertId}`,/演奏结算：文化 \+20/);const after=await snapshot();assert.equal(after.life.culture,restored.life.culture);assert.deepEqual(after.stock,restored.stock);return{restored,after};
    });
    await stage('restart preserves civic receipts and all four globally unique real-action claims',async()=>{
      const restored=await civicSnapshot();assert.equal(restored.apiVersion,verified.preparation.starWorldVersion);assert.deepEqual(restored.receipts,civicPreRestart.receipts);assert.deepEqual(restored.requests,civicPreRestart.requests);assert.deepEqual(restored.history,civicPreRestart.history);assert.equal(restored.life.culture,civicPreRestart.life.culture);const before=await nextCivicPeriod(restored.period),messages=[];for(const id of Object.values(needs))messages.push(await botMessage(bot,`/sw civic complete ${id}`,new RegExp(`需求完成回执 ${id}.*新提交=false`)));const after=await civicSnapshot();assert.equal(after.period,before.period,'Restart replay crossed a settlement boundary');assert.deepEqual(after.stock,before.stock);assert.deepEqual(after.receipts,civicPreRestart.receipts);assert.deepEqual(after.history,before.history);assert.equal(after.profile.stability,before.profile.stability);assert.equal(after.profile.prosperity,before.profile.prosperity);const actions=Object.values(after.receipts).flatMap(row=>row.claimedActionUUIDs);assert.equal(actions.length,4);assert.equal(new Set(actions).size,4);return{restored,before,messages,after};
    });
    await stage('second server stops normally',async()=>{await closePlayers();await server.stop();return{clean:true};});
    assert.equal(evidence.steps.length,evidence.expectedStages);evidence.originalStagesPreserved=15;evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();console.log(`PASS ${evidence.steps.length} optional civic integration stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(error.stack);process.exitCode=1;}
  finally{await closePlayers();try{await server.stop();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;save();process.exitCode=1;}fs.writeFileSync(path.join(options.runtime,'civic-senluo-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
main().catch(error=>{console.error(error.stack);process.exitCode=1;});

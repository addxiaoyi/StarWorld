#!/usr/bin/env node
'use strict';

// Real public national claims and wars, naturally selected production events.
// No SQL, synthetic witnesses, direct event triggers, or force-loaded chunks.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {setTimeout:delay}=require('node:timers/promises');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('./acceptance.cjs');

const HELP=`Usage: node border-acceptance.cjs --java <Java21> --paper <Paper132.jar>
  --runtime <fresh-owned-border-runtime> [--client-deps <Mineflayer directory>]

Requires a separately prepared disposable localhost Paper 1.21.11 runtime with
the sentinel/EULA/offline settings used by acceptance.cjs, actual StarCore,
StarWorld and latest paper-fixture JARs, no old worlds/StarWorld database/players.
Set isolated period-seconds=10 and event-chance=1; retain complete definitions.yml.
In evolution.yml retain all definitions but keep event-rules only border_dispute
with condition=border, weight=2, cooldown=10; border event duration=12, aftermath=2.
Disable isolated core new-country protection and war.declaration_cooldown_hours,
and set creation/claim/war costs to zero or explicitly fund through public services.
These are recorded test tunings; the pair declares again after the peace check.

Two real players establish two countries/cities and four actual farm/sawmill
anchors. Explicit inventory seeds enter warehouses through real /sw deposit.
Remote war and peaceful adjacent claims must not generate a dispute. A public
NationService peer claim beside a verified resource site plus real WarService
war must naturally select border_dispute in a normal production period with
resource/remaining/peer/chunk evidence. Removing the claim removes witnesses;
the event and one historical receipt persist across two normal owned starts,
and no new dispute appears after its aftermath and cooldown. No event command,
SQL edit, fake authority, neighbor chunk load or setForceLoaded is used.
Complete evidence and 2 server/4 client transcripts are kept under
runtime/border-acceptance/<UTC timestamp>; failed attempts stay failed.
`;

async function fixture(server,action,bot,suffix=''){
  const from=server.lines.length;server.command(`swfixture ${action} ${bot.username}${suffix?' '+suffix:''}`);
  return waitUntil(()=>{
    const lines=server.lines.slice(from);if(lines.some(line=>line.includes('SW_FIXTURE_FAILED')))throw new Error(lines.slice(-20).join('\n'));
    for(const line of lines){const index=line.indexOf('SW_FIXTURE_EXPANSION ');if(index<0)continue;const value=JSON.parse(line.slice(index+'SW_FIXTURE_EXPANSION '.length));if(value.action===action&&value.player===bot.username)return value.observed;}
    return false;
  },25000,`${action} ${bot.username}`,()=>{server.check();if(bot.failure)throw bot.failure;});
}
async function state(server,bot){
  const value=await fixture(server,'exp-border-state',bot);
  assert.equal(value.entryPoint,'ExpansionRuntime.captureBorderEvidence');assert(value.city&&value.captured&&Array.isArray(value.captured.witnesses)&&Array.isArray(value.history));
  assert(Number.isSafeInteger(value.city.lastPeriod));assert(Array.isArray(value.verifiedBuildings));return value;
}
function disputeHistory(value){return value.history.filter(row=>row.kind==='event'&&/^border_dispute\b/.test(row.detail));}
async function poll(server,bot,predicate,timeout,description){
  const deadline=Date.now()+timeout;let current;
  while(Date.now()<deadline){current=await state(server,bot);if(predicate(current))return current;await delay(1000);}
  throw new Error(`Timeout ${description}; last=${JSON.stringify(current)}`);
}
async function advance(server,bot,previous,periods=1){return poll(server,bot,value=>value.city.lastPeriod>=previous.city.lastPeriod+periods,(periods+2)*10000,'normal production periods');}
function noDispute(value){assert.equal(value.captured.witnesses.length,0);assert.equal(disputeHistory(value).length,0);assert.equal(value.city.event??null,null);}
function onlyOriginal(value,original){assert.deepEqual(disputeHistory(value),[original]);assert.equal(value.captured.witnesses.length,0);}
function eventDefinition(file){
  const text=fs.readFileSync(file,'utf8'),events=text.match(/^events:\s*\r?\n([\s\S]*?)(?=^event-rules:)/m);assert(events,'Missing complete event definitions');
  const border=events[1].match(/^  border_dispute:\s*\r?\n([\s\S]*?)(?=^  [a-z_]+:|$(?![\s\S]))/m);assert(border,'Missing border definition');
  const number=key=>{const found=border[1].match(new RegExp('^    '+key+':\\s*(\\d+)\\s*$','m'));assert(found,`Missing border ${key}`);return Number(found[1]);};
  return {duration:number('duration'),aftermath:number('aftermath')};
}

async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log(HELP);return;}
  for(const key of ['java','paper','runtime'])assert(args[key],`Missing --${key}\n${HELP}`);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),runtime:path.resolve(args.runtime),jvmArgs:['-Xms128m','-Xmx512m','-Xss512k','-XX:MaxMetaspaceSize=192m','-XX:ActiveProcessorCount=1','-XX:+UseSerialGC','-XX:TieredStopAtLevel=1','-XX:ReservedCodeCacheSize=64m','-Dio.netty.eventLoopThreads=2']};
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true');
  const props=properties(path.join(options.runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');options.port=Number(props['server-port']);assert(Number.isInteger(options.port)&&options.port>=1024&&options.port<=65535);
  const configDir=path.join(options.runtime,'plugins/StarWorld');assert(!fs.existsSync(path.join(configDir,'starworld.db')),'Use a fresh runtime for each attempt');
  assert(!fs.existsSync(path.join(options.runtime,props['level-name'])),'Do not reuse a generated world');
  assert.equal(configuredNumber(path.join(configDir,'config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(configDir,'config.yml'),'event-chance'),1);
  const evolution=fs.readFileSync(path.join(configDir,'evolution.yml'),'utf8'),rules=evolution.match(/^event-rules:\s*\r?\n((?:[ \t]+[^\r\n]*\r?\n|\s*\r?\n)+)/m);assert(rules,'Missing event-rules');
  assert.match(rules[1],/^  border_dispute:\s*\{condition:\s*border,\s*weight:\s*2,\s*cooldown:\s*10\}\s*$/m);
  assert.equal([...rules[1].matchAll(/^  [a-z_]+:/gm)].length,1,'Only the border rule may be enabled in this test');
  const borderDefinition=eventDefinition(path.join(configDir,'evolution.yml'));assert.deepEqual(borderDefinition,{duration:12,aftermath:2});
  assert.equal(hash(path.join(configDir,'definitions.yml')),hash(path.resolve(__dirname,'../../src/main/resources/definitions.yml')),'Keep all normal base definitions');
  const coreConfig=fs.readFileSync(path.join(options.runtime,'plugins/StarCore/config.yml'),'utf8');assert.match(coreConfig,/^\s*new-nation-protection-days:\s*0\s*(?:#.*)?$/m);assert.match(coreConfig,/^\s*declaration_cooldown_hours:\s*0\s*(?:#.*)?$/m);
  const localRequire=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=localRequire('mineflayer');
  const directory=path.join(options.runtime,'border-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-border-resource-dispute',status:'running',startedAt:new Date().toISOString(),node:process.version,minecraft:'1.21.11',mineflayer:localRequire('mineflayer/package.json').version,paperSha256:hash(options.paper),pluginJars:{},configSha256:{},steps:[],seeds:[],testTuning:{jvmArgs:options.jvmArgs,nodeFlags:process.execArgv,periodSeconds:10,eventChance:1,eventRules:['border_dispute'],borderDuration:12,borderAftermath:2,borderCooldown:10,newNationProtectionDays:0,warDeclarationCooldownHours:0,slots:[20,52],registeredFacilities:4,fixtureForceLoadedChunks:[],explicitPlayerInventorySeeds:true,neighborClaim:'NationService public API; no neighbor world/block/chunk load'}};
  for(const file of fs.readdirSync(path.join(options.runtime,'plugins')))if(file.endsWith('.jar'))evidence.pluginJars[file]=hash(path.join(options.runtime,'plugins',file));
  for(const file of ['config.yml','definitions.yml','evolution.yml','exports.yml','life.yml','logistics.yml','market.yml'])if(fs.existsSync(path.join(configDir,file)))evidence.configSha256[file]=hash(path.join(configDir,file));evidence.configSha256['StarCore/config.yml']=hash(path.join(options.runtime,'plugins/StarCore/config.yml'));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();console.log('RUN '+name);try{step.observed=await work();step.status='passed';}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),a,b,setupA,setupB,originalEvent,originalHistory,preRestart;
  async function closePlayers(){for(const bot of [a,b])if(bot){bot.quit();await bot.finishTranscript();}a=null;b=null;}
  async function players(run){a=await connect(mineflayer,{...options,username:'StarWorldBA'},directory,`${run}-a`);b=await connect(mineflayer,{...options,username:'StarWorldBB'},directory,`${run}-b`);server.command('deop StarWorldBA');server.command('deop StarWorldBB');}
  try{
    await stage('real Paper enables public core services and two fresh protocol clients',async()=>{await server.start();await players(1);return{pid:server.child.pid,players:[a.player.uuid,b.player.uuid]};});
    await stage('two real countries and cities own distinct nonadjacent national and city claims',async()=>{setupA=await fixture(server,'exp-border-setup',a,'0');setupB=await fixture(server,'exp-border-setup',b,'1');assert.notEqual(setupA.nationId,setupB.nationId);assert.notEqual(setupA.cityId,setupB.cityId);assert.deepEqual(setupA.forceLoadedChunks,[]);assert.deepEqual(setupB.forceLoadedChunks,[]);return{first:setupA,second:setupB};});
    await stage('explicit physical seeds and four raycast registered resource facilities settle normally',async()=>{
      const observations=[];for(const [bot,slot]of [[a,0],[b,1]]){const seed=await fixture(server,'exp-seed',bot);evidence.seeds.push({player:bot.username,...seed});for(const [item,count]of Object.entries(seed.items))await botMessage(bot,`/sw deposit ${item} ${count}`,new RegExp(`已存入 ${count} ${item}`));const from=bot.messages.length;const registration=await fixture(server,'exp-border-register',bot,String(slot));await waitUntil(()=>bot.messages.slice(from).filter(line=>line.includes('已登记 ')).length>=2,20000,'two actual resource registration replies');const current=await poll(server,bot,v=>v.city.buildings.length===2&&v.verifiedBuildings.length===2&&v.city.stock['minecraft:wheat']>seed.items['minecraft:wheat'],45000,'real farm production');assert.deepEqual(current.city.buildings.map(v=>v.type).sort(),['farm','sawmill']);noDispute(current);observations.push({seed,registration,state:current});}return observations;
    });
    await stage('war against a distant country has no border witness or dispute across normal periods',async()=>{const war=await fixture(server,'exp-war',a,b.username),before=await state(server,a);assert(before.captured.nation.warOpponents.includes(setupB.nationId));noDispute(before);const after=await advance(server,a,before,2);noDispute(after);return{war,before,after};});
    await stage('public peace and a real adjacent peer claim still produce no resource dispute',async()=>{const peace=await fixture(server,'exp-peace',a,b.username),claim=await fixture(server,'exp-border-claim',b,a.username);assert.equal(claim.peerNation,setupB.nationId);assert.equal(claim.resourceCity,setupA.cityId);assert.equal(claim.nationalClaimPresent,true);assert.equal(claim.forcedChunkLoad,false);assert.equal(claim.neighborLoadedBefore,claim.neighborLoadedAfter);assert.equal(Math.abs(claim.chunkX-claim.neighborChunkX)+Math.abs(claim.chunkZ-claim.neighborChunkZ),1);const before=await state(server,a);noDispute(before);const after=await advance(server,a,before,2);noDispute(after);return{peace,claim,before,after};});
    await stage('real war with that adjacent nation naturally records resource remaining peer and chunks',async()=>{
      const war=await fixture(server,'exp-war',a,b.username),captured=await state(server,a);assert(captured.captured.witnesses.length>0);const witness=captured.captured.witnesses.find(v=>v.peerNation===setupB.nationId&&v.chunkX===20&&v.chunkZ===0&&v.neighborChunkX===20&&v.neighborChunkZ===1);assert(witness);assert.equal(witness.relation,'WAR');assert.equal(witness.city,setupA.cityId);assert.equal(witness.nation,setupA.nationId);
      const after=await poll(server,a,v=>v.city.event?.type==='border_dispute'&&disputeHistory(v).length===1,45000,'natural border event from production');originalEvent=after.city.event;originalHistory=disputeHistory(after)[0];assert.equal(originalHistory.period,originalEvent.startPeriod);assert.match(originalHistory.detail,/resource=[a-z_]+/);assert.match(originalHistory.detail,/remaining=[1-9][0-9]*/);assert(originalHistory.detail.includes(setupB.nationId));assert(originalHistory.detail.includes(witness.worldId));assert.match(originalHistory.detail,/evidence=[1-9][0-9]*/);assert.match(originalHistory.detail,/relation=WAR/);assert.match(originalHistory.detail,/20[:/,]0|20,0/);assert.match(originalHistory.detail,/20[:/,]1|20,1/);return{war,captured,after,originalEvent,originalHistory};
    });
    await stage('public neighbor unclaim removes all current witnesses while retaining the original event',async()=>{const unclaim=await fixture(server,'exp-border-unclaim',b,a.username);assert.equal(unclaim.nationalClaimPresent,false);assert.equal(unclaim.neighborLoadedBefore,unclaim.neighborLoadedAfter);const current=await state(server,a);onlyOriginal(current,originalHistory);assert.deepEqual(current.city.event,originalEvent);const later=await advance(server,a,current);onlyOriginal(later,originalHistory);assert.deepEqual(later.city.event,originalEvent);return{unclaim,current,later};});
    await stage('first normal shutdown saves the active original event and single history receipt',async()=>{preRestart=await state(server,a);onlyOriginal(preRestart,originalHistory);assert.deepEqual(preRestart.city.event,originalEvent);await closePlayers();await server.stop();return{state:preRestart,clean:true};});
    server=new Paper(options,directory,2);
    await stage('second real startup restores the event countries facilities and exact historical evidence',async()=>{await server.start();await players(2);const first=await state(server,a),second=await state(server,b);assert.equal(first.city.id,setupA.cityId);assert.equal(second.city.id,setupB.cityId);assert.equal(first.city.nationId,setupA.nationId);assert.equal(first.city.buildings.length,2);assert.equal(second.city.buildings.length,2);assert.deepEqual(first.city.event,originalEvent);onlyOriginal(first,originalHistory);assert(first.city.lastPeriod>=preRestart.city.lastPeriod);return{first,second};});
    await stage('after original aftermath and cooldown distant continuing war cannot create another dispute',async()=>{const after=await poll(server,a,v=>v.city.lastPeriod>=Math.max(originalEvent.aftermathEndPeriod,originalEvent.startPeriod+10)+2,200000,'original event expiration and cooldown');onlyOriginal(after,originalHistory);assert.equal(after.city.event??null,null);assert(after.captured.nation.warOpponents.includes(setupB.nationId));return after;});
    await stage('second owned Paper process stops normally with one durable natural border event',async()=>{const final=await state(server,a);onlyOriginal(final,originalHistory);await closePlayers();await server.stop();return{final,clean:true};});
    evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();console.log(`PASS ${evidence.steps.length} border stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(error.stack);process.exitCode=1;}
  finally{try{await closePlayers();}catch{}if(server.child)try{await server.stop();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;save();process.exitCode=1;}fs.writeFileSync(path.join(options.runtime,'border-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

#!/usr/bin/env node
'use strict';

// Optional integration protocol suite. Manages only its own localhost child process.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {setTimeout:delay}=require('node:timers/promises');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('../paper-client/acceptance.cjs');
async function main(){
  const args=argumentsOf(process.argv.slice(2));
  if(args.help){console.log('node tools/senluo-fixture/acceptance.cjs --java <java21> --paper <paper132.jar> --runtime <owned-runtime> --client-deps <mineflayer-directory>');return;}
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
  const localRequire=createRequire(path.resolve(args['client-deps'],'package.json')),mineflayer=localRequire('mineflayer');
  const directory=path.join(options.runtime,'senluo-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const preparationFile=requireFile(path.join(options.runtime,'PREPARATION.json'));
  const assetPreparation=JSON.parse(fs.readFileSync(preparationFile,'utf8').replace(/^\uFEFF/,''));
  assert.equal(assetPreparation.upstreamAuditCommit,'c3642ae22fc5252bf6da22ae745cd39be994b054','Current upstream candidate preparation required');
  const evidence={schema:1,status:'running',startedAt:new Date().toISOString(),minecraft:'1.21.11',senluoAuditCommit:'c3642ae22fc5252bf6da22ae745cd39be994b054',paperSha256:hash(options.paper),pluginJars:{},assetPreparation:{fileSha256:hash(preparationFile),...assetPreparation},
    scope:'Real CE player inventory deposits, feast warehouse consumption, actual furniture lifecycle, StarWorld-controlled concert with a real same-city listener, restart. No upstream kmusic completion, resource-pack visual/audio or Folia claim.',
    testTuning:{periodSeconds:10,randomEventChance:0,professionCooldownPeriods:1,concertMinimumDurationMillis:60000,lifeConfigSha256:hash(lifeConfig),jvmArgs:options.jvmArgs,nodeArgs:process.execArgv,nodeVersion:process.version,paperSparkEnabled:false},steps:[]};
  for(const name of fs.readdirSync(path.join(options.runtime,'plugins')))if(name.endsWith('.jar'))evidence.pluginJars[name]=hash(path.join(options.runtime,'plugins',name));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,run)=>{console.log(`RUN ${name}`);const row={name,startedAt:new Date().toISOString()};evidence.steps.push(row);save();try{row.result=await run();row.status='passed';}catch(error){row.status='failed';row.error=error.stack;throw error;}finally{row.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),bot,listener,preRestart,concertId;
  async function fixture(action,extra=''){
    const from=server.lines.length;server.command(`swsenluo ${action} StarWorldQA ${extra}`);
    const raw=await waitUntil(()=>{server.check();const rows=server.lines.slice(from);const failed=rows.find(x=>/SW_SENLUO_FAILED/.test(x));if(failed)throw new Error(failed);return rows.find(x=>x.includes('SW_SENLUO_JSON ')&&x.includes(`"action":"${action}"`));},20000,`SenLuo fixture ${action}`);
    return JSON.parse(raw.slice(raw.indexOf('SW_SENLUO_JSON ')+'SW_SENLUO_JSON '.length));
  }
  const snapshot=()=>fixture('snapshot');
  async function poll(predicate,timeout=35000){let latest;return waitUntil(async()=>{latest=await snapshot();return predicate(latest)?latest:false;},timeout,'live life state',()=>server.check());}
  async function profession(id){const before=await snapshot();if(before.profession.profession!=='none'&&before.profession.profession!==id)await poll(s=>s.period>before.profession.changedPeriod,20000);await botMessage(bot,`/sw profession ${id}`,/生活职业：/);return snapshot();}
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
    await stage('clean shutdown persists committed extension and player state',async()=>{await closePlayers();await server.stop();return{clean:true};});
    server=new Paper(options,directory,2);
    await stage('restart restores city culture and cannot replay concert reward',async()=>{
      await server.start();bot=await connect(mineflayer,options,directory,2);const restored=await snapshot();assert.equal(restored.cityId,preRestart.cityId);assert.equal(restored.life.culture,preRestart.life.culture);assert.deepEqual(restored.stock,preRestart.stock);assert.equal(restored.profession.profession,'musician');
      await botMessage(bot,`/sw concert finish ${concertId}`,/演奏结算：文化 \+20/);const after=await snapshot();assert.equal(after.life.culture,restored.life.culture);assert.deepEqual(after.stock,restored.stock);return{restored,after};
    });
    await stage('second server stops normally',async()=>{await closePlayers();await server.stop();return{clean:true};});
    evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();console.log(`PASS ${evidence.steps.length} optional integration stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(error.stack);process.exitCode=1;}
  finally{await closePlayers();try{await server.stop();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;save();process.exitCode=1;}fs.writeFileSync(path.join(options.runtime,'senluo-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
main().catch(error=>{console.error(error.stack);process.exitCode=1;});

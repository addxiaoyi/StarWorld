#!/usr/bin/env node
'use strict';

// Optional integration protocol suite. Manages only its own localhost child process.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {setTimeout:delay}=require('node:timers/promises');
const {Paper,connect,botMessage,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('../paper-client/acceptance.cjs');
const AUDIT_COMMIT='3e774d00567deb9cb65eee8da6de8a65254e5b8e';
const EXPECTED_JARS={
  'StarWorld.jar':'68cc01bc395a018fb443f1350a4bd7426c87b660aad0c54dd948b5d87597bbba',
  'StarWorldFixture.jar':'ce2aaaeeb8c34b870eefa2572444db2cf60b0d804d31fcf24b19a72b41e3e22b',
  'StarCore.jar':'953f926f12384e0cfafae6f55d266a10c7156468e7e28be75518c3f7b413884b',
  'craftengine-26.9.1.jar':'021260c87e3546730d321f4360b1744e6e33caa1d68e98ae30dcdbbdc347ae0f',
  'KaleidoscopeCookeryPlugin-1.3.0.jar':'26a37d7187766b4990a53ca7c01622e098fd6606d1c763eb479586051f952457',
  'KaleidoscopeTavern-0.1.0-dev.jar':'205b36165b519106cda696f0d48ab91e031b6b90ba6bef8ee0d2202155948033',
  'KaleidoscopeDeco-0.1.0-dev.jar':'1db18976692f5de6b284b31cfc5ef40e14d60f86925f75e87fe7dc3cf807f263',
  'KaleidoscopeMusic-0.1.0-dev.jar':'983fb4b88b39bb5211e12ead23d3202222624e114e2ee4137f48d49d434b1078'};
const PACK_SHA='a3f68c026c7440153444cd306374ce043dc7825dc7ab74df98c3fb1efd1d6c45';
const PROVENANCE_SHA='07fa559d255a0ea254fe57ca9abfdded031334260a4f06764a77ea6c36a89440';
function json(file){return JSON.parse(fs.readFileSync(requireFile(file),'utf8').replace(/^\uFEFF/,''));}
function localFile(root,relative){assert(!path.isAbsolute(relative)&&!relative.split(/[\\/]/).includes('..'),`Unsafe preparation path: ${relative}`);return requireFile(path.join(root,relative));}
function filesUnder(root,prefix=''){return fs.readdirSync(path.join(root,prefix),{withFileTypes:true}).flatMap(entry=>{const relative=prefix?`${prefix}/${entry.name}`:entry.name;assert(!entry.isSymbolicLink(),`Linked preparation input: ${relative}`);return entry.isDirectory()?filesUnder(root,relative):[relative];}).sort();}
function verifyPreparation(options){
  const preparationFile=requireFile(path.join(options.runtime,'PREPARATION.json')),preparation=json(preparationFile);
  assert.equal(preparation.schema,1);assert(['prepared','running'].includes(preparation.status),'Bind the current fixture before acceptance');
  assert.equal(path.resolve(preparation.runtime),options.runtime);assert.equal(preparation.upstreamAuditCommit,AUDIT_COMMIT);
  assert.equal(preparation.starWorldVersion,'0.4.0');assert.equal(preparation.starWorldSha256,EXPECTED_JARS['StarWorld.jar']);
  assert.equal(preparation.cookeryJarSha256,EXPECTED_JARS['KaleidoscopeCookeryPlugin-1.3.0.jar']);assert.equal(preparation.kitchenPackSha256,PACK_SHA);
  assert.equal(preparation.worldsCopied,0);assert.equal(preparation.authorityFilesCopied,0);
  assert(preparation.processStarted===false||preparation.processStarted===true);assert.equal(preparation.testTuning.serverStarts,2);
  assert.equal(preparation.testTuning.host,'127.0.0.1');assert.equal(preparation.testTuning.port,options.port);
  assert.equal(preparation.testTuning.minimumFreeCommitBytes,1610612736);assert.deepEqual(preparation.testTuning.jvmArgs,options.jvmArgs);
  for(const name of ['_JAVA_OPTIONS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'])assert(!process.env[name],`Unexpected JVM injection: ${name}`);
  const plugins=path.join(options.runtime,'plugins'),actualJars=fs.readdirSync(plugins).filter(name=>name.endsWith('.jar')).sort();
  assert.equal(actualJars.length,9);assert.deepEqual(actualJars,[...Object.keys(EXPECTED_JARS),'StarWorldSenLuoFixture.jar'].sort());
  assert.equal(preparation.installedPlugins.length,9);assert.deepEqual(preparation.installedPlugins.map(row=>row.file).sort(),actualJars);
  const pluginJars={};for(const row of preparation.installedPlugins){assert.equal(path.basename(row.file),row.file);const file=localFile(plugins,row.file);pluginJars[row.file]=hash(file);assert.equal(pluginJars[row.file],row.sha256,`Prepared JAR drift: ${row.file}`);assert.equal(fs.statSync(file).size,row.bytes);if(EXPECTED_JARS[row.file])assert.equal(row.sha256,EXPECTED_JARS[row.file]);}
  assert.equal(pluginJars['StarWorldSenLuoFixture.jar'],preparation.senluoFixtureSha256);assert.match(preparation.senluoFixtureSha256,/^[a-f0-9]{64}$/);
  assert.deepEqual(Object.keys(preparation.configSha256).sort(),['config.yml','definitions.yml','evolution.yml','exports.yml','life.yml','logistics.yml','market.yml']);
  for(const [name,expected]of Object.entries(preparation.configSha256))assert.equal(hash(localFile(path.join(plugins,'StarWorld'),name)),expected,`Prepared configuration drift: ${name}`);
  const provenanceFile=localFile(options.runtime,'UPSTREAM-PROVENANCE.json'),provenance=json(provenanceFile);
  assert.equal(hash(provenanceFile),PROVENANCE_SHA);assert.equal(preparation.newAssetPreparation.upstreamProvenanceSha256,PROVENANCE_SHA);
  assert.equal(provenance.commit,AUDIT_COMMIT);assert.equal(provenance.cookeryJar.sha256,preparation.cookeryJarSha256);assert.equal(provenance.kitchenPack.sha256,PACK_SHA);
  assert.equal(hash(localFile(options.runtime,'asset-inputs/kitchen-crops-3.zip')),PACK_SHA);
  const assetRoot=path.join(options.runtime,'asset-inputs/kitchen-crops-3'),manifestFile=localFile(assetRoot,'manifest.json'),manifest=json(manifestFile);
  assert.deepEqual(manifest,provenance.kitchenPack.manifest);assert.equal(hash(manifestFile),preparation.assetManifestSha256);
  const assetFiles=Object.keys(manifest.files).sort();assert.equal(assetFiles.length,2399);
  assert.deepEqual(filesUnder(assetRoot),[...assetFiles,'manifest.json'].sort());
  const activeResources=path.join(plugins,'CraftEngine/resources');
  assert.equal(path.resolve(preparation.newAssetPreparation.activeCookeryResources),path.join(activeResources,'cookery_kitchen'));
  assert.deepEqual(filesUnder(path.join(activeResources,'cookery_kitchen')),assetFiles.map(name=>{assert(name.startsWith('cookery_kitchen/'));return name.slice('cookery_kitchen/'.length);}).sort());
  for(const [name,expected]of Object.entries(manifest.files)){assert.equal(hash(localFile(assetRoot,name)),expected,`Extracted pack drift: ${name}`);assert.equal(hash(localFile(activeResources,name)),expected,`Loaded pack drift: ${name}`);}
  assert.equal(preparation.newAssetPreparation.internalFiles,55);assert.equal(filesUnder(path.join(activeResources,'internal')).length,55);
  assert(Object.keys(preparation.dependencyFilesSha256).length>=55,'Dependency/configuration fingerprints required');
  for(const [name,expected]of Object.entries(preparation.dependencyFilesSha256))assert.equal(hash(localFile(options.runtime,name)),expected,`Prepared dependency drift: ${name}`);
  assert.equal(hash(options.paper),preparation.paperSha256);assert.equal(hash(options.java),preparation.javaSha256);
  const level=properties(path.join(options.runtime,'server.properties'))['level-name'];assert(level&&path.basename(level)===level);
  for(const name of [level,`${level}_nether`,`${level}_the_end`,'world','world_nether','world_the_end'])assert(!fs.existsSync(path.join(options.runtime,name)),`Fresh world required: ${name}`);
  for(const folder of ['StarWorld','StarCore'])for(const name of filesUnder(path.join(plugins,folder)))assert(!/\.(?:db|sqlite|sqlite3)(?:-|$)/i.test(name),`Fresh database required: ${folder}/${name}`);
  assert(!fs.existsSync(path.join(options.runtime,'senluo-acceptance')),'Preserve previous acceptance; prepare another fresh runtime');
  return{preparationFile,preparation,pluginJars,verifiedAssetFiles:assetFiles.length,verifiedDependencyFiles:Object.keys(preparation.dependencyFilesSha256).length};
}
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
  const verified=verifyPreparation(options);
  const localRequire=createRequire(path.resolve(args['client-deps'],'package.json')),mineflayer=localRequire('mineflayer');
  const directory=path.join(options.runtime,'senluo-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,status:'running',startedAt:new Date().toISOString(),minecraft:'1.21.11',senluoAuditCommit:AUDIT_COMMIT,paperSha256:hash(options.paper),pluginJars:verified.pluginJars,assetPreparation:{fileSha256:hash(verified.preparationFile),...verified.preparation},preflight:{verifiedAssetFiles:verified.verifiedAssetFiles,verifiedDependencyFiles:verified.verifiedDependencyFiles,freshWorldAndDatabases:true},
    scope:'Real CE player inventory deposits, feast warehouse consumption, actual furniture lifecycle, StarWorld-controlled concert with a real same-city listener, restart. No upstream kmusic completion, resource-pack visual/audio or Folia claim.',
    testTuning:{periodSeconds:10,randomEventChance:0,professionCooldownPeriods:1,concertMinimumDurationMillis:60000,lifeConfigSha256:hash(lifeConfig),jvmArgs:options.jvmArgs,nodeArgs:process.execArgv,nodeVersion:process.version,paperSparkEnabled:false},steps:[]};
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

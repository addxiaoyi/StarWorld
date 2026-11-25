'use strict';

const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const v8=require('node:v8');
const {Paper,connect,botMessage,hash,requireFile,properties,assertResourceHeadroom,RESOURCE_LIMITS}=require('./acceptance.cjs');

function argsOf(values){const result={};for(let i=0;i<values.length;i++){if(values[i]==='--help'||values[i]==='-h'){result.help=true;continue;}if(!['--java','--runtime','--paper','--client-deps'].includes(values[i]))throw new Error(`Unknown argument: ${values[i]}`);const key=values[i].slice(2);if(!values[i+1]||values[i+1].startsWith('--'))throw new Error(`Missing value: ${values[i]}`);result[key]=values[++i];}return result;}
const help=`Usage: node --max-old-space-size=192 --max-semi-space-size=8 survey-acceptance.cjs --java <java.exe> --paper <Paper.jar> --runtime <owned-fresh-runtime> [--client-deps <mineflayer-dir>]
Runs one isolated Paper start with real StarCore, StarWorld and the test fixture. It verifies current-chunk survey rules and repeats 384-query queue saturation with 250 ms recovery gaps for 60 seconds. Results go under runtime/survey-acceptance.`;

async function main(){
  const supplied=argsOf(process.argv.slice(2));if(supplied.help){console.log(help);return;}
  for(const name of ['java','paper','runtime'])assert(supplied[name],`Required --${name}\n${help}`);
  const options={java:requireFile(supplied.java),paper:requireFile(supplied.paper),runtime:path.resolve(supplied.runtime),jvmArgs:['-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC']};
  const resourcePreflight=assertResourceHeadroom();
  const minimumFreeSystemMemoryBytes=resourcePreflight.minimumStartupFreeMemoryBytes;
  const startupFreeSystemMemoryBytes=resourcePreflight.startupFreeMemoryBytes;
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1','Refusing an unowned runtime');
  const runtimeHeapLimitBytes=v8.getHeapStatistics().heap_size_limit;
  assert(runtimeHeapLimitBytes<=256*1024*1024,`Node heap limit must be at most 256 MiB; got ${runtimeHeapLimitBytes} bytes.\n${help}`);
  assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true','Accept the EULA separately; this script will not alter it');
  const serverProperties=properties(path.join(options.runtime,'server.properties'));
  assert.equal(serverProperties['server-ip'],'127.0.0.1','Test server must explicitly bind localhost');
  assert.equal(serverProperties['online-mode'],'false','Disposable offline protocol test requires online-mode=false');
  options.port=Number(serverProperties['server-port']);assert(Number.isInteger(options.port)&&options.port>=1024&&options.port<=65535,'Invalid isolated server port');
  const localRequire=supplied['client-deps']?createRequire(path.resolve(supplied['client-deps'],'package.json')):require,mineflayer=localRequire('mineflayer');
  const stamp=new Date().toISOString().replace(/[:.]/g,'-'),directory=path.join(options.runtime,'survey-acceptance',stamp);fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-survey-and-storage-backpressure',status:'running',startedAt:new Date().toISOString(),minecraft:'1.21.11',node:process.version,nodeHeapLimitBytes:runtimeHeapLimitBytes,mineflayer:localRequire('mineflayer/package.json').version,runnerSha256:hash(__filename),paperSha256:hash(options.paper),runtime:options.runtime,pluginJars:{},testTuning:{jvmArgs:options.jvmArgs,nodeExecArgv:process.execArgv,nodeOptionsEnvironment:process.env.NODE_OPTIONS||'',minimumFreeSystemMemoryBytes,startupFreeSystemMemoryBytes,minimumFreeCommitMemoryBytes:resourcePreflight.minimumStartupFreeCommitBytes,startupFreeCommitMemoryBytes:resourcePreflight.startupFreeCommitBytes,runtimeFreeMemoryStopThresholdBytes:RESOURCE_LIMITS.minimumRuntimeFreeMemoryBytes,chunkAccess:'player current chunk only; no scans or forced loads',maximumRegionsPerCity:128,paperCost:8,storageQueueCapacity:256,storagePressureRequestsPerRound:384,storagePressureDurationSeconds:60,storagePressureRecoveryGapMilliseconds:250,storagePressureBatchSize:384,storageRequestTimeoutSeconds:30,paperHeapSafetyLimitPercent:95},steps:[]};
  evidence.configSha256={};for(const file of fs.readdirSync(path.join(options.runtime,'plugins','StarWorld'))){if(file.endsWith('.yml'))evidence.configSha256[file]=hash(path.join(options.runtime,'plugins','StarWorld',file));}
  for(const file of fs.readdirSync(path.join(options.runtime,'plugins')))if(/\.jar$/i.test(file))evidence.pluginJars[file]=hash(path.join(options.runtime,'plugins',file));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();try{step.observed=await work();step.status='passed';step.finishedAt=new Date().toISOString();save();console.log(`[PASS] ${name}`);return step.observed;}catch(error){step.status='failed';step.error=error.stack;save();throw error;}};
  let server=new Paper(options,directory,1),bot=null;
  try{
    await stage('real Paper, StarCore and StarWorld startup',async()=>{await server.start();return{pid:server.child.pid};});
    await stage('real player and public dual-claim city setup',async()=>{bot=await connect(mineflayer,options,directory,1);server.command('deop StarWorldQA');await server.fixture('setup');return{username:bot.username};});
    await stage('city warehouse receives real paper through player deposit',async()=>{server.command('swfixture survey-seed StarWorldQA');await server.wait(/SW_FIXTURE_SURVEY_SEED paper=16/);await botMessage(bot,'/sw deposit minecraft:paper 16',/已存入 16 minecraft:paper/);const snapshot=await server.snapshot();assert.equal(snapshot.stock['minecraft:paper'],16);return snapshot.stock;});
    await stage('owned loaded chunk survey records terrain and spends eight paper',async()=>{server.command('swfixture survey-position StarWorldQA 1');await server.wait(/SW_FIXTURE_SURVEY_POSITION chunk=1 loaded=true/);await botMessage(bot,'/sw survey',/已勘探 .*城市仓库消耗8张纸/);server.command('swfixture survey-snapshot StarWorldQA');const raw=await server.wait(/SW_FIXTURE_SURVEY_JSON /);const observed=JSON.parse(raw.slice(raw.indexOf('SW_FIXTURE_SURVEY_JSON ')+ 'SW_FIXTURE_SURVEY_JSON '.length));assert.equal(observed.summary.regions,1);assert.equal(observed.summary.facilities,0);assert.equal(observed.regions.length,1);assert.equal(observed.regions[0].chunkX,1);assert.equal(observed.regions[0].chunkZ,0);assert.equal(observed.regions[0].facilities.length,0);const stock=await server.snapshot();assert.equal(stock.stock['minecraft:paper'],8);return{observed,stock:stock.stock};});
    await stage('duplicate survey preserves the original charge and region count',async()=>{await botMessage(bot,'/sw survey',/该区块已在地图册中/);const stock=await server.snapshot();assert.equal(stock.stock['minecraft:paper'],8);server.command('swfixture survey-snapshot StarWorldQA');const raw=await server.wait(/SW_FIXTURE_SURVEY_JSON /);const observed=JSON.parse(raw.slice(raw.indexOf('SW_FIXTURE_SURVEY_JSON ')+ 'SW_FIXTURE_SURVEY_JSON '.length));assert.equal(observed.summary.regions,1);return{paper:stock.stock['minecraft:paper'],regions:observed.summary.regions};});
    await stage('real Paper sustains bounded queue saturation and recovers for 60 seconds',async()=>{
      const durationMillis=60000,requestsPerRound=384,recoveryGapMillis=250,deadline=Date.now()+durationMillis;
      const total={rounds:0,requests:0,accepted:0,rejected:0,heapSamples:0,peakHeapBytes:0,maxHeapBytes:0,firstRoundMillis:null,lastRoundMillis:null};
      while(Date.now()<deadline){
        const from=server.lines.length;server.command(`swfixture storage-saturation StarWorldQA ${requestsPerRound}`);
        const raw=await server.wait(/SW_FIXTURE_STORAGE_JSON /,from,135000);
        const observed=JSON.parse(raw.slice(raw.indexOf('SW_FIXTURE_STORAGE_JSON ')+'SW_FIXTURE_STORAGE_JSON '.length));
        assert.equal(observed.requests,requestsPerRound);assert.equal(observed.queueCapacity,256);assert.equal(observed.batchSize,384);assert.equal(observed.requestTimeoutSeconds,30);
        assert(observed.accepted>0);assert.equal(observed.accepted+observed.rejected,observed.requests);assert.equal(observed.recovery,true);
        assert(observed.heapSampleCount>0);assert(observed.sampledPeakHeapBytes>0);assert(observed.maxHeapBytes>0&&observed.maxHeapBytes<=512*1024*1024,'Paper maximum heap exceeded 512 MiB');
        assert(observed.sampledPeakHeapBytes<=observed.maxHeapBytes*0.95,'Paper heap crossed the 95% hard-stop limit');assert(observed.elapsedMillis<135000);
        total.rounds++;total.requests+=observed.requests;total.accepted+=observed.accepted;total.rejected+=observed.rejected;
        total.heapSamples+=observed.heapSampleCount;total.peakHeapBytes=Math.max(total.peakHeapBytes,observed.sampledPeakHeapBytes);total.maxHeapBytes=Math.max(total.maxHeapBytes,observed.maxHeapBytes);
        if(total.firstRoundMillis===null)total.firstRoundMillis=observed.elapsedMillis;total.lastRoundMillis=observed.elapsedMillis;
        await new Promise(resolve=>setTimeout(resolve,recoveryGapMillis));
      }
      total.elapsedMillis=Date.now()-(deadline-durationMillis);total.safetyLimitPercent=95;
      total.requestsPerRound=requestsPerRound;total.recoveryGapMillis=recoveryGapMillis;
      total.peakPercentOfMaximum=total.peakHeapBytes/total.maxHeapBytes*100;
      assert(total.rounds>=10,'Sustained pressure did not complete enough bounded rounds');
      assert(total.accepted+total.rejected===total.requests);assert(total.rejected>0,'The soak never observed queue saturation');
      assert(total.peakPercentOfMaximum<=total.safetyLimitPercent,'Paper heap crossed the 95% hard-stop limit during the soak');
      return total;
    });
    await stage('missing national claim rejects survey without charging',async()=>{await server.fixture('unclaim');server.command('swfixture survey-position StarWorldQA 2');await server.wait(/SW_FIXTURE_SURVEY_POSITION chunk=2 loaded=true/);await botMessage(bot,'/sw survey',/只能勘探本城市和本国都拥有的区块/);const stock=await server.snapshot();assert.equal(stock.stock['minecraft:paper'],8);server.command('swfixture survey-snapshot StarWorldQA');const raw=await server.wait(/SW_FIXTURE_SURVEY_JSON /);const observed=JSON.parse(raw.slice(raw.indexOf('SW_FIXTURE_SURVEY_JSON ')+ 'SW_FIXTURE_SURVEY_JSON '.length));assert.equal(observed.summary.regions,1);return{paper:stock.stock['minecraft:paper'],regions:observed.summary.regions};});
    await stage('owned Paper process stops cleanly',async()=>{bot.end('survey acceptance complete');await bot.finishTranscript();bot=null;await server.stop();return{cleanExit:true};});
    evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();console.log(`PASS ${evidence.steps.length} survey stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(`${error.stack}\n${server.lines.slice(-24).join('\n')}`);process.exitCode=1;}
  finally{if(bot){try{bot.end('survey acceptance cleanup');await bot.finishTranscript();}catch{}}if(server.child)try{await server.stop();}catch(error){evidence.status='failed';evidence.cleanupError=error.stack;save();process.exitCode=1;}fs.writeFileSync(path.join(options.runtime,'survey-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

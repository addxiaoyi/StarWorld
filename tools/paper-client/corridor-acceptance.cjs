#!/usr/bin/env node
'use strict';

const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {Paper,connect,botMessage:sendCommand,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('./acceptance.cjs');
const lastCommand=new WeakMap();
async function botMessage(bot,...args){const wait=1100-(Date.now()-(lastCommand.get(bot)||0));if(wait>0)await new Promise(resolve=>setTimeout(resolve,wait));lastCommand.set(bot,Date.now());return sendCommand(bot,...args);}
const HELP='Usage: node --max-old-space-size=192 --max-semi-space-size=8 corridor-acceptance.cjs --java <java21> --paper <paper.jar> --runtime <fresh-owned-runtime> [--client-deps <mineflayer>]\nTwo real clients, two Paper starts, real claims and physical land/water corridors. Requires a 10-second period, event-chance 0 and blocks-per-period 8. EULA and localhost must already be configured.';
async function fixture(server,action,bot,suffix=''){
  const from=server.lines.length;server.command(`swfixture ${action} ${bot.username}${suffix?' '+suffix:''}`);
  return waitUntil(()=>{server.check();if(bot.failure)throw bot.failure;for(const line of server.lines.slice(from)){
    if(line.includes('SW_FIXTURE_FAILED'))throw new Error(server.lines.slice(-25).join('\n'));
    const start=line.indexOf('SW_FIXTURE_EXPANSION ');if(start<0)continue;const value=JSON.parse(line.slice(start+'SW_FIXTURE_EXPANSION '.length));if(value.action===action&&value.player===bot.username)return value.observed;
  }return null;},20000,action);
}
async function snapshot(server,bot){return fixture(server,'exp-progress',bot);}
function cargo(s,id){return s.orders.find(o=>o.id===id);}
function amount(s,item){return s.city.stock[item]||0;}
async function poll(server,bot,condition,label,timeout=65000){return waitUntil(async()=>{const state=await snapshot(server,bot);return condition(state)?state:null;},timeout,label,()=>server.check());}
async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log(HELP);return;}
  for(const name of ['java','runtime','paper'])assert(args[name],HELP);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),runtime:path.resolve(args.runtime),jvmArgs:['-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC']};
  assert.equal(fs.readFileSync(path.join(options.runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const props=properties(path.join(options.runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');assert.equal(properties(path.join(options.runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);
  const config=path.join(options.runtime,'plugins/StarWorld');assert.equal(configuredNumber(path.join(config,'config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(config,'config.yml'),'event-chance'),0);assert.equal(configuredNumber(path.join(config,'logistics.yml'),'blocks-per-period'),8);
  const requireClient=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=requireClient('mineflayer');
  const directory=path.join(options.runtime,'corridor-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-physical-corridors',status:'running',startedAt:new Date().toISOString(),minecraft:'1.21.11',node:process.version,mineflayer:requireClient('mineflayer/package.json').version,paperSha256:hash(options.paper),runnerSha256:hash(__filename),acceptanceHelperSha256:hash(path.join(__dirname,'acceptance.cjs')),pluginJars:{},configSha256:{},steps:[],testTuning:{jvmArgs:options.jvmArgs,nodeExecArgv:process.execArgv,periodSeconds:10,blocksPerPeriod:8,eventChance:0,fixtureForceLoadedChunks:[],maximumColumns:1024,maximumBlockReadsPerBatch:16384,physicalSeedsAreProductionEvidence:false}};
  for(const name of fs.readdirSync(path.join(options.runtime,'plugins')))if(name.endsWith('.jar'))evidence.pluginJars[name]=hash(path.join(options.runtime,'plugins',name));
  for(const name of fs.readdirSync(config))if(name.endsWith('.yml'))evidence.configSha256[name]=hash(path.join(config,name));evidence.configSha256['StarCore/config.yml']=hash(path.join(options.runtime,'plugins/StarCore/config.yml'));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();try{step.observed=await work();step.status='passed';step.finishedAt=new Date().toISOString();save();console.log(`[PASS] ${name}`);return step.observed;}catch(error){step.status='failed';step.error=error.stack;save();throw error;}};
  let server=new Paper(options,directory,1),a,b,land,sea,landOrder,waterOrder,initialA,initialB,pausedBefore;
  const close=async()=>{for(const bot of [a,b])if(bot){bot.end('corridor acceptance');await bot.finishTranscript();}a=b=null;};
  const players=async run=>{a=await connect(mineflayer,{...options,username:'StarWorldRA'},directory,`${run}-a`);b=await connect(mineflayer,{...options,username:'StarWorldRB'},directory,`${run}-b`);};
  try{
    await stage('real Paper and two corridor clients start',async()=>{await server.start();await players(1);return{pid:server.child.pid,players:[a.username,b.username]};});
    await stage('public adjacent city claims and finite physical test terrain',async()=>{
      const first=await fixture(server,'exp-corridor-setup',a,'0'),second=await fixture(server,'exp-corridor-setup',b,'1');
      const built=await fixture(server,'exp-corridor-build',a);server.command(`deop ${a.username}`);server.command(`deop ${b.username}`);return{first,second,built};
    });
    await stage('real inventory deposits fund both transport endpoints',async()=>{
      const seeds=[];for(const bot of [a,b]){const seed=await fixture(server,'exp-progress-seed',bot);seeds.push(seed);for(const [item,count]of Object.entries(seed.items))await botMessage(bot,`/sw deposit ${item} ${count}`,new RegExp(`已存入 ${count} ${item}`));}
      return{seeds,a:await snapshot(server,a),b:await snapshot(server,b)};
    });
    await stage('ordinary raycasts register road and port anchors and bind both finite corridors',async()=>{
      for(const [bot,slot]of [[a,0],[b,1]])for(const mode of ['land','sea']){const from=bot.messages.length;await fixture(server,'exp-corridor-node',bot,`${slot} ${mode}`);await waitUntil(()=>bot.messages.slice(from).some(m=>m.includes('已登记 ')),10000,'node registration');}
      const first=await snapshot(server,a),second=await snapshot(server,b);assert.match(first.version,/^1\.(?:[7-9]|[1-9][0-9]+)\./);
      for(const mode of ['land','sea']){const type=mode==='land'?'ROAD':'PORT';const text=await botMessage(a,`/sw route create ${second.city.id} ${mode} ${first.nodes.find(n=>n.type===type).id},${second.nodes.find(n=>n.type===type).id}`,/商路 [0-9a-f-]{36} ·/);const id=text.match(/商路 ([0-9a-f-]{36})/)[1];if(mode==='land')land=id;else sea=id;
        await botMessage(a,`/sw route inspect ${id}`,/全程有限通道已核验/);await botMessage(a,`/sw route bind ${id}`,/商路已绑定物理通道/);}
      const state=await snapshot(server,a);assert(state.routes.every(r=>r.corridorRequired));assert(state.routes.every(r=>r.travelPeriods>=3));return state;
    });
    await stage('a physical road gap rejects a new offer without escrowing goods',async()=>{
      await fixture(server,'exp-corridor-gap',a);const before=await snapshot(server,a);await botMessage(a,`/sw route inspect ${land}`,/道路路面缺失/);
      await botMessage(a,`/sw trade offer ${land} minecraft:iron_ingot=4 minecraft:oak_log=6`,/通道受阻/);const after=await snapshot(server,a);assert.equal(after.orders.length,0);assert.equal(amount(after,'minecraft:iron_ingot'),amount(before,'minecraft:iron_ingot'));return{before,after};
    });
    await stage('repaired road accepts real escrow then a new gap freezes progress and upkeep',async()=>{
      await fixture(server,'exp-corridor-repair',a);initialA=await snapshot(server,a);initialB=await snapshot(server,b);
      const text=await botMessage(a,`/sw trade offer ${land} minecraft:iron_ingot=4 minecraft:oak_log=6`,/已托管报价 [0-9a-f-]{36}/);landOrder=text.match(/已托管报价 ([0-9a-f-]{36})/)[1];await botMessage(b,`/sw trade accept ${landOrder}`,/货单 .* IN_TRANSIT/);await fixture(server,'exp-corridor-gap',a);
      const paused=await poll(server,a,s=>cargo(s,landOrder)?.status==='PAUSED','road gap pause');const later=await poll(server,a,s=>cargo(s,landOrder)?.lastPeriod>cargo(paused,landOrder).lastPeriod,'next blocked period');
      assert.match(cargo(later,landOrder).reason,/通道受阻/);assert.equal(cargo(later,landOrder).progressedPeriods,cargo(paused,landOrder).progressedPeriods);assert.equal(amount(later,'minecraft:oak_planks'),amount(paused,'minecraft:oak_planks'));assert.equal(amount(later,'minecraft:iron_ingot'),amount(initialA,'minecraft:iron_ingot')-4);pausedBefore=later;return{paused,later};
    });
    await stage('first corridor server saves bound paths and paused escrow normally',async()=>{await close();await server.stop();return{clean:true};});
    server=new Paper(options,directory,2);
    await stage('restart preserves bound corridors and the same paused shipment',async()=>{
      await server.start();await players(2);const state=await snapshot(server,a);assert(state.routes.every(r=>r.corridorRequired));assert.equal(cargo(state,landOrder).status,'PAUSED');assert.equal(cargo(state,landOrder).progressedPeriods,cargo(pausedBefore,landOrder).progressedPeriods);assert.equal(amount(state,'minecraft:iron_ingot'),amount(pausedBefore,'minecraft:iron_ingot'));return state;
    });
    await stage('lost intermediate national claim blocks a repaired road while both anchors remain valid',async()=>{
      await fixture(server,'exp-corridor-unclaim',a);await fixture(server,'exp-corridor-repair',a);await botMessage(a,`/sw route inspect ${land}`,/沿途双层领土/);const state=await poll(server,a,s=>/沿途双层领土/.test(cargo(s,landOrder)?.reason||''),'intermediate territorial pause');assert.equal(cargo(state,landOrder).progressedPeriods,cargo(pausedBefore,landOrder).progressedPeriods);return state;
    });
    await stage('restoring the intermediate claim delivers both escrow sides exactly once',async()=>{
      await fixture(server,'exp-corridor-reclaim',a);const done=await poll(server,a,s=>cargo(s,landOrder)?.status==='COMPLETED'&&amount(s,'minecraft:oak_log')===amount(initialA,'minecraft:oak_log')+6,'restored road delivery'),other=await poll(server,b,s=>cargo(s,landOrder)?.status==='COMPLETED'&&amount(s,'minecraft:iron_ingot')===amount(initialB,'minecraft:iron_ingot')+4,'destination road stock');
      assert.equal(amount(done,'minecraft:oak_log'),amount(initialA,'minecraft:oak_log')+6);assert.equal(amount(other,'minecraft:iron_ingot'),amount(initialB,'minecraft:iron_ingot')+4);assert.equal(amount(other,'minecraft:oak_log'),amount(initialB,'minecraft:oak_log')-6);return{done,other};
    });
    await stage('shallow water pauses real sea cargo until the second source layer is restored',async()=>{
      const beforeA=await snapshot(server,a),beforeB=await snapshot(server,b);const text=await botMessage(a,`/sw trade offer ${sea} minecraft:iron_ingot=3 minecraft:oak_log=5`,/已托管报价 [0-9a-f-]{36}/);waterOrder=text.match(/已托管报价 ([0-9a-f-]{36})/)[1];await botMessage(b,`/sw trade accept ${waterOrder}`,/货单 .* IN_TRANSIT/);await fixture(server,'exp-corridor-shallow',a);await botMessage(a,`/sw route inspect ${sea}`,/水路需两格深/);
      const paused=await poll(server,a,s=>cargo(s,waterOrder)?.status==='PAUSED','shallow sea pause');assert.match(cargo(paused,waterOrder).reason,/两格深/);await fixture(server,'exp-corridor-water-repair',a);const done=await poll(server,a,s=>cargo(s,waterOrder)?.status==='COMPLETED'&&amount(s,'minecraft:oak_log')===amount(beforeA,'minecraft:oak_log')+5,'sea delivery'),other=await poll(server,b,s=>cargo(s,waterOrder)?.status==='COMPLETED'&&amount(s,'minecraft:iron_ingot')===amount(beforeB,'minecraft:iron_ingot')+3,'destination sea stock');
      assert.equal(amount(done,'minecraft:oak_log'),amount(beforeA,'minecraft:oak_log')+5);assert.equal(amount(other,'minecraft:iron_ingot'),amount(beforeB,'minecraft:iron_ingot')+3);const later=await poll(server,b,s=>s.currentPeriod>other.currentPeriod,'later completed period');assert.equal(amount(later,'minecraft:iron_ingot'),amount(other,'minecraft:iron_ingot'));return{beforeA,beforeB,paused,done,other,later};
    });
    await stage('second corridor server saves both completed shipments normally',async()=>{await close();await server.stop();return{clean:true};});
    evidence.status='passed';evidence.finishedAt=new Date().toISOString();save();console.log(`PASS ${evidence.steps.length} corridor stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;evidence.finishedAt=new Date().toISOString();save();console.error(error.stack+'\n'+server.lines.slice(-25).join('\n'));process.exitCode=1;}
  finally{try{await close();}catch{}if(server.child)try{await server.stop();}catch(error){evidence.cleanupError=error.stack;evidence.status='failed';process.exitCode=1;save();}fs.writeFileSync(path.join(options.runtime,'corridor-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

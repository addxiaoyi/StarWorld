#!/usr/bin/env node
'use strict';

const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {Paper,connect,botMessage:sendCommand,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('./acceptance.cjs');
const lastCommand=new WeakMap();
async function command(bot,...args){const wait=1100-(Date.now()-(lastCommand.get(bot)||0));if(wait>0)await new Promise(resolve=>setTimeout(resolve,wait));lastCommand.set(bot,Date.now());return sendCommand(bot,...args);}
const read=file=>JSON.parse(fs.readFileSync(file,'utf8'));
async function fixture(server,action,bot,suffix=''){
  const from=server.lines.length;server.command(`swfixture ${action} ${bot.username}${suffix?' '+suffix:''}`);
  return waitUntil(()=>{server.check();if(bot.failure)throw bot.failure;for(const line of server.lines.slice(from)){
    assert(!line.includes('SW_FIXTURE_FAILED'),line);const at=line.indexOf('SW_FIXTURE_EXPANSION ');if(at<0)continue;
    const value=JSON.parse(line.slice(at+21));if(value.action===action&&value.player===bot.username)return value.observed;
  }return null;},20000,action);
}
const snapshot=(server,bot)=>fixture(server,'exp-progress',bot);
const amount=(state,item)=>state.city.stock[item]||0;
const IRON='minecraft:iron_ingot',PLANKS='minecraft:oak_planks';
async function poll(server,bot,predicate,label){return waitUntil(async()=>{const state=await snapshot(server,bot);return predicate(state)?state:null;},65000,label,()=>server.check());}
function refundIdentity(state,id){const row=state.refunds.find(r=>r.id===id);assert(row);return{id:row.id,purchaseId:row.purchaseId,transactionId:row.transactionId};}
async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log('node refund-acceptance.cjs --java <java21> --paper <paper.jar> --runtime <prepared-isolated-refund-runtime> --client-deps <mineflayer>\nThree Paper starts, two ordinary clients, verified StarCore settlement candidate, real payment/reversal and physical escrow.');return;}
  for(const name of ['java','paper','runtime'])assert(args[name],`Missing --${name}`);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),runtime:path.resolve(args.runtime),jvmArgs:['-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC']};
  const runtime=options.runtime,plugins=path.join(runtime,'plugins'),config=path.join(plugins,'StarWorld');
  assert.equal(fs.readFileSync(path.join(runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const props=properties(path.join(runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');assert.equal(properties(path.join(runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);
  assert.equal(configuredNumber(path.join(config,'config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(config,'config.yml'),'event-chance'),0);assert.equal(configuredNumber(path.join(config,'logistics.yml'),'blocks-per-period'),8);
  const unit=read(path.join(__dirname,'../../docs/evidence/unit-tests.json')),upstream=read(path.join(runtime,'STARCORE-PROVENANCE.json')),prep=read(path.join(runtime,'PREPARATION.json'));
  assert.match(unit.version,/^1\.(?:[8-9]|[1-9][0-9]+)\./);assert.equal(unit.artifact_sha256,hash(path.join(plugins,'StarWorld.jar')));assert(unit.totals.tests>0);assert.equal(unit.totals.failures+unit.totals.errors+unit.totals.skipped,0);
  assert.equal(upstream.artifact_sha256,hash(path.join(plugins,'StarCore.jar')));assert.equal(prep.targetPluginSha256,unit.artifact_sha256);assert.equal(prep.suite,'refund');assert.equal(prep.runnerSha256,hash(__filename));
  const requireClient=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=requireClient('mineflayer');
  const directory=path.join(runtime,'refund-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-market-refund',status:'running',version:unit.version,artifactSha256:unit.artifact_sha256,startedAt:new Date().toISOString(),minecraft:'1.21.11',node:process.version,mineflayer:requireClient('mineflayer/package.json').version,
    paperSha256:hash(options.paper),runnerSha256:hash(__filename),acceptanceHelperSha256:hash(path.join(__dirname,'acceptance.cjs')),upstream,preparationSha256:hash(path.join(runtime,'PREPARATION.json')),pluginJars:{},configSha256:{},steps:[],
    testTuning:{jvmArgs:options.jvmArgs,nodeExecArgv:process.execArgv,periodSeconds:10,blocksPerPeriod:8,eventChance:0,physicalSeedsAreProductionEvidence:false,treasuryInterventionsAreEarnedIncome:false}};
  for(const file of fs.readdirSync(plugins))if(file.endsWith('.jar'))evidence.pluginJars[file]=hash(path.join(plugins,file));
  for(const file of fs.readdirSync(config))if(file.endsWith('.yml'))evidence.configSha256[file]=hash(path.join(config,file));evidence.configSha256['StarCore/config.yml']=hash(path.join(plugins,'StarCore/config.yml'));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();try{step.observed=await work();step.status='passed';console.log(`[PASS] ${name}`);return step.observed;}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),seller,buyer,route,listing,purchase,refund,shipment,originalSeller,originalBuyer,held,identity;
  const players=async run=>{seller=await connect(mineflayer,{...options,username:'StarWorldFA'},directory,`${run}-a`);buyer=await connect(mineflayer,{...options,username:'StarWorldFB'},directory,`${run}-b`);};
  const close=async()=>{for(const bot of [seller,buyer])if(bot){bot.end('refund acceptance');await bot.finishTranscript();}seller=buyer=null;if(server.child)await server.stop();};
  const order=s=>s.orders.find(o=>o.id===shipment),refundRow=s=>s.refunds.find(r=>r.id===refund),purchaseRow=s=>s.purchases.find(p=>p.id===purchase);
  const replay=async()=>{const result=await fixture(server,'exp-refund-replay',buyer,refund);assert.equal(result.replay.status,'CONFIRMED');assert.equal(result.receipt.transactionId,identity.transactionId);assert.equal(result.receipt.originalTransactionId,purchaseRow(await snapshot(server,buyer)).transactionId);assert.equal(Number(result.payerBefore),1000);assert.equal(Number(result.payerAfter),1000);assert.equal(Number(result.receiverBefore),0);assert.equal(Number(result.receiverAfter),0);assert.deepEqual(Object.values(result.receipt.credits).map(Number),[17.5]);return result;};
  try{
    await stage('real Paper starts with the verified settlement contract and two clients',async()=>{await server.start();await players(1);return{pid:server.child.pid,players:[seller.username,buyer.username]};});
    await stage('fresh adjacent nations and physical road are established through public services',async()=>{
      const a=await fixture(server,'exp-corridor-setup',seller,'0'),b=await fixture(server,'exp-corridor-setup',buyer,'1'),terrain=await fixture(server,'exp-corridor-build',seller);server.command(`deop ${seller.username}`);server.command(`deop ${buyer.username}`);return{a,b,terrain};
    });
    await stage('ordinary inventory deposits and explicit treasury seed prepare conserved inputs',async()=>{
      const seeds=[];for(const bot of [seller,buyer]){const seed=await fixture(server,'exp-progress-seed',bot);seeds.push(seed);for(const[item,count]of Object.entries(seed.items))await command(bot,`/sw deposit ${item} ${count}`,new RegExp(`已存入 ${count} ${item}`));}
      const money=await fixture(server,'exp-fund',buyer,'1000');originalSeller=await snapshot(server,seller);originalBuyer=await snapshot(server,buyer);assert.equal(Number(originalSeller.treasury),0);assert.equal(Number(originalBuyer.treasury),1000);assert.equal(originalSeller.purchases.length,0);assert.equal(originalSeller.refunds.length,0);return{seeds,money,seller:originalSeller,buyer:originalBuyer};
    });
    await stage('ordinary founders bind the road and prepare a seven-unit purchase from a twenty-unit lot',async()=>{
      for(const[bot,slot]of[[seller,0],[buyer,1]]){const from=bot.messages.length;await fixture(server,'exp-corridor-node',bot,`${slot} land`);await waitUntil(()=>bot.messages.slice(from).some(m=>m.includes('已登记 ')),10000,'road registration');}
      const a=await snapshot(server,seller),b=await snapshot(server,buyer);const created=await command(seller,`/sw route create ${b.city.id} land ${a.nodes[0].id},${b.nodes[0].id}`,/商路 [0-9a-f-]{36} ·/);route=created.match(/商路 ([0-9a-f-]{36})/)[1];await command(seller,`/sw route bind ${route}`,/商路已绑定物理通道/);
      const listed=await command(seller,`/sw market sell ${IRON} 20 2.50`,/挂牌已托管真实商品/);listing=listed.match(/商品：[ ]*([0-9a-f-]{36})/)[1];
      const bought=await command(buyer,`/sw market buy ${listing} ${route} 7`,/购买已准备/);purchase=bought.match(/准备：[ ]*([0-9a-f-]{36})/)[1];return{route,listing,purchase};
    });
    await stage('real 17.50 payment ships seven units and explicit seller insolvency blocks reversal',async()=>{
      const a=await poll(server,seller,s=>purchaseRow(s)?.status==='SHIPPED'&&Number(s.treasury)===17.5,'paid physical shipment');shipment=purchaseRow(a).shipmentId;assert.equal(order(a).status,'IN_TRANSIT');assert.equal(amount(a,IRON),amount(originalSeller,IRON)-20);
      const money=await fixture(server,'exp-market-receipt',buyer,purchase);assert.equal(Number(money.receipt.amount),17.5);assert.equal(Number(money.payerBefore),982.5);assert.equal(Number(money.receiverBefore),17.5);
      const drain=await fixture(server,'exp-refund-drain',seller);assert.equal(Number(drain.before),17.5);assert.equal(Number(drain.after),0);
      const requested=await command(buyer,`/sw market refund ${purchase}`,/退款申请 [0-9a-f-]{36}/);refund=requested.match(/退款申请 ([0-9a-f-]{36})/)[1];
      await command(buyer,`/sw market refund-accept ${refund}`,/退款确认需要原卖方国家创始人权限/);
      const unauthorized=await snapshot(server,buyer);assert.equal(refundRow(unauthorized).status,'REQUESTED');assert.equal(purchaseRow(unauthorized).status,'SHIPPED');
      await command(seller,`/sw market refund-accept ${refund}`,/退款 .* PENDING/);
      const waiting=await poll(server,seller,s=>refundRow(s)?.status==='RETRY'&&order(s)?.status==='REFUNDING','insolvent reversal retry');identity=refundIdentity(waiting,refund);assert.equal(purchaseRow(waiting).status,'REFUNDING');
      return{a,money,drain,unauthorized,waiting};
    });
    await stage('frozen refund survives another transport period without goods progress upkeep or premature receipt',async()=>{
      const before=await snapshot(server,seller);held=await poll(server,seller,s=>s.currentPeriod>before.currentPeriod+1,'two frozen periods');
      assert.equal(order(held).status,'REFUNDING');assert.equal(order(held).progressedPeriods,order(before).progressedPeriods);assert.equal(amount(held,PLANKS),amount(before,PLANKS));assert.equal(amount(held,IRON),amount(originalSeller,IRON)-20);
      assert.equal(Number(held.treasury),0);const b=await snapshot(server,buyer);assert.equal(Number(b.treasury),982.5);assert.equal(amount(b,IRON),amount(originalBuyer,IRON));
      const proof=await fixture(server,'exp-refund-receipt',buyer,refund);assert(!proof.receipt);return{before,held,b,proof};
    });
    await stage('first normal shutdown saves the frozen goods and refund identity',async()=>{await close();return{identity,clean:true};});
    server=new Paper(options,directory,2);
    await stage('restart restores the same approved refund and original unpaid reverse balance',async()=>{
      await server.start();await players(2);const a=await snapshot(server,seller),b=await snapshot(server,buyer);assert.deepEqual(refundIdentity(a,refund),identity);assert.equal(order(a).status,'REFUNDING');assert.equal(order(a).progressedPeriods,order(held).progressedPeriods);assert.equal(amount(a,IRON),amount(held,IRON));assert.equal(Number(a.treasury),0);assert.equal(Number(b.treasury),982.5);return{a,b};
    });
    await stage('replenishing the seller and retrying the original refund returns money and exactly seven goods',async()=>{
      await poll(server,seller,s=>refundRow(s).status==='RETRY','retryable refund after restart');await command(seller,`/sw market refund-retry ${refund}`,/退款 .* PENDING/);
      const seed=await fixture(server,'exp-fund',seller,'17.50');
      const a=await poll(server,seller,s=>refundRow(s)?.status==='COMPLETED'&&amount(s,IRON)===amount(originalSeller,IRON)-13,'single cargo return');
      const b=await poll(server,buyer,s=>refundRow(s)?.status==='COMPLETED'&&Number(s.treasury)===1000,'single money return');
      assert.deepEqual(refundIdentity(a,refund),identity);assert.equal(purchaseRow(a).status,'REFUNDED');assert.equal(order(a).status,'CANCELLED');assert.equal(Number(a.treasury),0);assert.equal(amount(b,IRON),amount(originalBuyer,IRON));
      const lot=a.listings.find(l=>l.id===listing);assert.equal(lot.availableUnits,13);assert.equal(lot.reservedUnits,0);assert.equal(lot.shippedUnits,0);assert.equal(lot.returnedUnits,7);assert.equal(a.prices.length,1);return{seed,a,b};
    });
    await stage('durable reverse receipt replays without second debit credit or cargo release',async()=>{
      const proof=await replay();const a=await snapshot(server,seller);assert.equal(amount(a,IRON),amount(originalSeller,IRON)-13);return{proof,a};
    });
    await stage('seller cancels only the remaining thirteen units and both physical inventories conserve',async()=>{
      await command(seller,`/sw market cancel ${listing}`,/挂牌已关闭/);const a=await poll(server,seller,s=>amount(s,IRON)===amount(originalSeller,IRON),'remaining escrow return'),b=await snapshot(server,buyer);
      assert.equal(amount(b,IRON),amount(originalBuyer,IRON));const lot=a.listings.find(l=>l.id===listing);assert.equal(lot.returnedUnits,20);assert.equal(lot.status,'CANCELLED');assert.equal(lot.availableUnits+lot.reservedUnits+lot.shippedUnits,0);await close();return{a,b,clean:true};
    });
    server=new Paper(options,directory,3);
    await stage('third start preserves final money and cargo and both original receipts remain replay safe',async()=>{
      await server.start();await players(3);const proof=await replay(),payment=await fixture(server,'exp-market-replay',buyer,purchase);assert.equal(payment.replay.status,'CONFIRMED');assert.equal(Number(payment.payerBefore),Number(payment.payerAfter));assert.equal(Number(payment.receiverBefore),Number(payment.receiverAfter));
      const a=await snapshot(server,seller),b=await snapshot(server,buyer);assert.equal(amount(a,IRON),amount(originalSeller,IRON));assert.equal(amount(b,IRON),amount(originalBuyer,IRON));assert.deepEqual(refundIdentity(a,refund),identity);assert.equal(refundRow(a).status,'COMPLETED');assert.equal(order(a).status,'CANCELLED');return{proof,payment,a,b};
    });
    await stage('third server shuts down normally with no repeated refund or shipment',async()=>{await close();return{clean:true};});
    evidence.status='passed';console.log(`PASS ${evidence.steps.length} refund stages: ${directory}`);
  }catch(error){evidence.status='failed';evidence.error=error.stack;console.error(error.stack+'\n'+server.lines.slice(-25).join('\n'));process.exitCode=1;}
  finally{try{await close();}catch(error){evidence.cleanupError=error.stack;evidence.status='failed';process.exitCode=1;}evidence.finishedAt=new Date().toISOString();save();fs.writeFileSync(path.join(runtime,'refund-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

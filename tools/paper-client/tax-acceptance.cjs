#!/usr/bin/env node
'use strict';

const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {Paper,connect,botMessage:sendCommand,properties,configuredNumber,hash,requireFile,waitUntil,argumentsOf}=require('./acceptance.cjs');
const lastCommand=new WeakMap();
async function command(bot,...args){const delay=1100-(Date.now()-(lastCommand.get(bot)||0));if(delay>0)await new Promise(resolve=>setTimeout(resolve,delay));lastCommand.set(bot,Date.now());return sendCommand(bot,...args);}
const read=file=>JSON.parse(fs.readFileSync(file,'utf8'));
async function fixture(server,action,bot,suffix=''){
  const from=server.lines.length;server.command('swfixture '+action+' '+bot.username+(suffix?' '+suffix:''));
  return waitUntil(()=>{server.check();if(bot.failure)throw bot.failure;for(const line of server.lines.slice(from)){
    assert(!line.includes('SW_FIXTURE_FAILED'),line);const at=line.indexOf('SW_FIXTURE_EXPANSION ');if(at<0)continue;
    const value=JSON.parse(line.slice(at+21));if(value.action===action&&value.player===bot.username)return value.observed;
  }return null;},20000,action);
}
const snapshot=(server,bot)=>fixture(server,'exp-progress',bot),amount=(state,item)=>state.city.stock[item]||0,IRON='minecraft:iron_ingot';
async function poll(server,bot,predicate,label){return waitUntil(async()=>{const state=await snapshot(server,bot);return predicate(state)?state:null;},65000,label,()=>server.check());}
function terms(p,goods,tax){assert.equal(Number(p.goodsTotal),goods);assert.equal(Number(p.taxAmount),tax);assert.equal(Number(p.total),(Math.round(goods*100)+Math.round(tax*100))/100);}
async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log('node tax-acceptance.cjs --java <java21> --paper <paper.jar> --runtime <prepared-isolated-tax-runtime> --client-deps <mineflayer>');return;}
  for(const name of ['java','paper','runtime'])assert(args[name],'Missing --'+name);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),runtime:path.resolve(args.runtime),jvmArgs:['-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC']};
  const runtime=options.runtime,plugins=path.join(runtime,'plugins'),config=path.join(plugins,'StarWorld');
  assert.equal(fs.readFileSync(path.join(runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const props=properties(path.join(runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');assert.equal(properties(path.join(runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);
  assert.equal(configuredNumber(path.join(config,'config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(config,'config.yml'),'event-chance'),0);assert.equal(configuredNumber(path.join(config,'logistics.yml'),'blocks-per-period'),8);
  const unit=read(path.join(__dirname,'../../docs/evidence/unit-tests.json')),upstream=read(path.join(runtime,'STARCORE-PROVENANCE.json')),prep=read(path.join(runtime,'PREPARATION.json'));
  assert.match(unit.version,/^1\.(?:9|[1-9][0-9]+)\./);assert.equal(unit.artifact_sha256,hash(path.join(plugins,'StarWorld.jar')));assert(unit.totals.tests>0);assert.equal(unit.totals.failures+unit.totals.errors+unit.totals.skipped,0);
  assert.equal(upstream.artifact_sha256,hash(path.join(plugins,'StarCore.jar')));assert.equal(prep.targetPluginSha256,unit.artifact_sha256);assert.equal(prep.suite,'tax');assert.equal(prep.runnerSha256,hash(__filename));
  const requireClient=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=requireClient('mineflayer');
  const directory=path.join(runtime,'tax-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-market-tax',status:'running',version:unit.version,artifactSha256:unit.artifact_sha256,startedAt:new Date().toISOString(),minecraft:'1.21.11',node:process.version,mineflayer:requireClient('mineflayer/package.json').version,
    paperSha256:hash(options.paper),runnerSha256:hash(__filename),acceptanceHelperSha256:hash(path.join(__dirname,'acceptance.cjs')),upstream,preparationSha256:hash(path.join(runtime,'PREPARATION.json')),pluginJars:{},configSha256:{},steps:[],
    testTuning:{jvmArgs:options.jvmArgs,nodeExecArgv:process.execArgv,periodSeconds:10,blocksPerPeriod:8,eventChance:0,physicalSeedsAreProductionEvidence:false,treasuryInterventionsAreEarnedIncome:false,taxConfigurationsAreExplicitFixtureInterventions:true}};
  for(const file of fs.readdirSync(plugins))if(file.endsWith('.jar'))evidence.pluginJars[file]=hash(path.join(plugins,file));
  for(const file of fs.readdirSync(config))if(file.endsWith('.yml'))evidence.configSha256[file]=hash(path.join(config,file));evidence.configSha256['StarCore/config.yml']=hash(path.join(plugins,'StarCore/config.yml'));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();try{step.observed=await work();step.status='passed';console.log('[PASS] '+name);return step.observed;}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),seller,buyer,route,listing,first,second,refund,quote,competing,expired,originalSeller,originalBuyer;
  const players=async run=>{seller=await connect(mineflayer,{...options,username:'StarWorldTA'},directory,run+'-a');buyer=await connect(mineflayer,{...options,username:'StarWorldTB'},directory,run+'-b');};
  const close=async()=>{for(const bot of [seller,buyer])if(bot){bot.end('tax acceptance');await bot.finishTranscript();}seller=buyer=null;if(server.child)await server.stop();};
  const purchase=(s,id)=>s.purchases.find(p=>p.id===id),lot=s=>s.listings.find(l=>l.id===listing);
  const preview=async(units,op='quote')=>{
    const message=await command(buyer,'/sw market '+op+' '+listing+' '+route+' '+units,/市场报价 [0-9a-f-]{36}/);
    const id=message.match(/市场报价 ([0-9a-f-]{36})/)[1],proof=await fixture(server,'exp-market-quote',buyer,id);
    assert.equal(proof.quote.id,id);assert.equal(proof.quote.actor,buyer.player.uuid);assert(!proof.quote.purchaseId);return{message,...proof.quote};
  };
  const unchangedMoney=async(payer,receiver)=>{const a=await snapshot(server,seller),b=await snapshot(server,buyer);assert.equal(Number(a.treasury),receiver);assert.equal(Number(b.treasury),payer);return{a,b};};
  const replay=async(action,id,paid,received)=>{
    const proof=await fixture(server,action,buyer,id);assert.equal(proof.replay.status,'CONFIRMED');assert.equal(Number(proof.payerBefore),paid);assert.equal(Number(proof.payerAfter),paid);assert.equal(Number(proof.receiverBefore),received);assert.equal(Number(proof.receiverAfter),received);return proof;
  };
  try{
    await stage('real Paper starts with two ordinary clients and the public taxation settlement APIs',async()=>{await server.start();await players(1);return{pid:server.child.pid,players:[seller.username,buyer.username]};});
    await stage('adjacent nations physical road and inventory deposits establish explicit conserved inputs',async()=>{
      const a=await fixture(server,'exp-corridor-setup',seller,'0'),b=await fixture(server,'exp-corridor-setup',buyer,'1'),terrain=await fixture(server,'exp-corridor-build',seller);
      server.command('deop '+seller.username);server.command('deop '+buyer.username);const seeds=[];
      for(const bot of [seller,buyer]){const permission=await fixture(server,'exp-permissions',bot);assert.equal(permission.admin,false);assert.equal(permission.use,true);const seed=await fixture(server,'exp-progress-seed',bot);seeds.push(seed);for(const[item,count]of Object.entries(seed.items))await command(bot,'/sw deposit '+item+' '+count,new RegExp('已存入 '+count+' '+item));}
      const money=await fixture(server,'exp-fund',buyer,'1000');originalSeller=await snapshot(server,seller);originalBuyer=await snapshot(server,buyer);
      assert.equal(Number(originalSeller.treasury),0);assert.equal(Number(originalBuyer.treasury),1000);assert.equal(originalSeller.purchases.length,0);return{a,b,terrain,seeds,money,seller:originalSeller,buyer:originalBuyer};
    });
    await stage('founders bind a real road list twenty iron and publicly configure three percent export tariff',async()=>{
      for(const[bot,slot]of[[seller,0],[buyer,1]]){const from=bot.messages.length;await fixture(server,'exp-corridor-node',bot,slot+' land');await waitUntil(()=>bot.messages.slice(from).some(m=>m.includes('已登记 ')),10000,'road registration');}
      const a=await snapshot(server,seller),b=await snapshot(server,buyer),created=await command(seller,'/sw route create '+b.city.id+' land '+a.nodes[0].id+','+b.nodes[0].id,/商路 [0-9a-f-]{36} ·/);
      route=created.match(/商路 ([0-9a-f-]{36})/)[1];await command(seller,'/sw route bind '+route,/商路已绑定物理通道/);
      const listed=await command(seller,'/sw market sell '+IRON+' 20 2.50',/挂牌已托管真实商品/);listing=listed.match(/商品：[ ]*([0-9a-f-]{36})/)[1];
      const rate=await fixture(server,'exp-tariff',seller,'3');assert.equal(Number(rate.after.percentRate),3);return{route,listing,rate};
    });
    await stage('buy with positive tariff previews rounded terms without reserving goods or charging treasury',async()=>{
      quote=await preview(7,'buy');assert.equal(Number(quote.goodsTotal),17.5);assert.equal(Number(quote.taxAmount),0.52);assert.match(quote.message,/应付 18\.02/);assert.equal(quote.expiresAt-quote.createdAt,60000);
      const states=await unchangedMoney(1000,0);assert.equal(states.a.purchases.length,0);assert.equal(states.a.orders.length,0);assert.equal(lot(states.a).availableUnits,20);assert.equal(lot(states.a).reservedUnits,0);return{quote,...states};
    });
    await stage('tax rate changes before consent but the original quote pays exactly eighteen point zero two',async()=>{
      const rate=await fixture(server,'exp-tariff',seller,'10');competing=await preview(14);assert.equal(Number(competing.goodsTotal),35);assert.equal(Number(competing.taxAmount),3.5);
      await command(buyer,'/sw market buy-quote '+quote.id,/购买已准备.*原子支付 18\.02/);first=quote.id;
      const a=await poll(server,seller,s=>purchase(s,first)?.status==='SHIPPED'&&Number(s.treasury)===18.02,'frozen tariff payment');
      terms(purchase(a,first),17.5,0.52);assert.equal(purchase(a,first).taxQuote,quote.id);assert.equal(a.orders.find(o=>o.id===purchase(a,first).shipmentId).status,'IN_TRANSIT');
      const proof=await fixture(server,'exp-market-receipt',buyer,first);assert.equal(Number(proof.receipt.amount),18.02);assert.equal(Number(proof.payerBefore),981.98);assert.equal(Number(proof.receiverBefore),18.02);
      const saved=await fixture(server,'exp-market-quote',buyer,quote.id);assert.equal(saved.quote.purchaseId,first);assert.equal(Number(saved.quote.taxAmount),0.52);return{rate,competing,a,proof,saved};
    });
    await stage('competing preview cannot overreserve stock and generates neither another payment nor goods',async()=>{
      await command(buyer,'/sw market buy-quote '+competing.id,/可购买数量不足/);
      const states=await unchangedMoney(981.98,18.02);assert.equal(states.a.purchases.length,1);assert.equal(lot(states.a).availableUnits,13);assert.equal(lot(states.a).reservedUnits,0);
      expired=await preview(7);assert.equal(Number(expired.taxAmount),1.75);return{competing,expired,...states};
    });
    await stage('both founders refund the entire frozen goods plus tariff payment before physical delivery',async()=>{
      const request=await command(buyer,'/sw market refund '+first,/退款申请 [0-9a-f-]{36}/);refund=request.match(/退款申请 ([0-9a-f-]{36})/)[1];
      await command(seller,'/sw market refund-accept '+refund,/退款 .* PENDING/);
      const a=await poll(server,seller,s=>s.refunds.find(r=>r.id===refund)?.status==='COMPLETED'&&amount(s,IRON)===amount(originalSeller,IRON)-13,'full tax reversal and seven goods return');
      const b=await poll(server,buyer,s=>Number(s.treasury)===1000,'full buyer refund');
      terms(purchase(a,first),17.5,0.52);assert.equal(purchase(a,first).status,'REFUNDED');assert.equal(Number(a.treasury),0);assert.equal(amount(b,IRON),amount(originalBuyer,IRON));assert.equal(lot(a).returnedUnits,7);
      const proof=await replay('exp-refund-replay',refund,1000,0);assert.deepEqual(Object.values(proof.receipt.credits).map(Number),[18.02]);return{a,b,proof};
    });
    await stage('first normal shutdown saves accepted quote identity and completed tax refund',async()=>{await close();return{quote:quote.id,purchase:first,refund,clean:true};});
    server=new Paper(options,directory,2);
    await stage('restart restores frozen tax terms and payment refund replay leaves both balances unchanged',async()=>{
      await server.start();await players(2);const states=await unchangedMoney(1000,0);terms(purchase(states.a,first),17.5,0.52);
      assert.equal(purchase(states.a,first).status,'REFUNDED');const saved=await fixture(server,'exp-market-quote',buyer,quote.id);assert.equal(saved.quote.purchaseId,first);assert.equal(Number(saved.quote.taxAmount),0.52);
      await command(buyer,'/sw market buy-quote '+quote.id,/购买已准备.*原子支付 18\.02/);
      const payment=await replay('exp-market-replay',first,1000,0),reversal=await replay('exp-refund-replay',refund,1000,0);return{saved,payment,reversal,...states};
    });
    await stage('expired unaccepted quotes cannot charge or reserve even after restart and bounded cleanup',async()=>{
      await waitUntil(()=>{server.check();return Date.now()>expired.expiresAt+100;},61000,'real quote expiry');
      await command(buyer,'/sw market buy-quote '+expired.id,/报价不存在或已到期|报价已到期/);
      const states=await unchangedMoney(1000,0);assert.equal(states.a.purchases.length,1);assert.equal(lot(states.a).availableUnits,13);assert.equal(lot(states.a).reservedUnits,0);return{expiredId:expired.id,...states};
    });
    await stage('new consent uses persisted ten percent tariff and makes one nineteen point two five payment',async()=>{
      const current=await preview(7,'buy');assert.equal(Number(current.taxAmount),1.75);assert.match(current.message,/应付 19\.25/);
      await command(buyer,'/sw market buy-quote '+current.id,/购买已准备.*原子支付 19\.25/);second=current.id;
      const a=await poll(server,seller,s=>purchase(s,second)?.status==='SHIPPED'&&Number(s.treasury)===19.25,'new tariff payment');terms(purchase(a,second),17.5,1.75);
      const proof=await fixture(server,'exp-market-receipt',buyer,second);assert.equal(Number(proof.receipt.amount),19.25);assert.equal(Number(proof.payerBefore),980.75);assert.equal(Number(proof.receiverBefore),19.25);return{current,a,proof};
    });
    await stage('actual delivery transfers seven goods and statistics count goods subtotal without tariff or refunded sale',async()=>{
      const b=await poll(server,buyer,s=>s.orders.find(o=>o.id===purchase(s,second)?.shipmentId)?.status==='COMPLETED'&&amount(s,IRON)===amount(originalBuyer,IRON)+7,'taxed physical delivery');
      const stats=await waitUntil(async()=>{const value=await fixture(server,'exp-operations',seller,originalBuyer.city.id);return value.globalDeliveryStats.some(row=>row.item===IRON)?value:null;},20000,'goods-only delivery statistics');
      for(const rows of [stats.deliveryStats,stats.globalDeliveryStats]){assert.equal(rows.length,1);const row=rows[0];assert.equal(row.item,IRON);assert.equal(row.orders,1);assert.equal(row.units,7);assert.equal(Number(row.gross),17.5);assert.equal(Number(row.averageUnitPrice),2.5);}
      await command(buyer,'/sw market refund '+second,/已交付/);
      await command(seller,'/sw market cancel '+listing,/挂牌已关闭/);const a=await poll(server,seller,s=>amount(s,IRON)===amount(originalSeller,IRON)-7,'remaining six units returned');
      assert.equal(lot(a).returnedUnits,13);assert.equal(lot(a).shippedUnits,7);assert.equal(lot(a).availableUnits+lot(a).reservedUnits,0);assert.equal(a.refunds.length,1);return{a,b,stats};
    });
    await stage('second normal shutdown preserves goods tax totals and final balances',async()=>{const states=await unchangedMoney(980.75,19.25);await close();return{...states,clean:true};});
    server=new Paper(options,directory,3);
    await stage('third start replays both payments and refund without repeated tax goods or national money',async()=>{
      await server.start();await players(3);
      const firstPayment=await replay('exp-market-replay',first,980.75,19.25),secondPayment=await replay('exp-market-replay',second,980.75,19.25),reversal=await replay('exp-refund-replay',refund,980.75,19.25);
      const states=await unchangedMoney(980.75,19.25);terms(purchase(states.a,first),17.5,0.52);terms(purchase(states.a,second),17.5,1.75);assert.equal(states.a.purchases.length,2);
      assert.equal(amount(states.a,IRON),amount(originalSeller,IRON)-7);assert.equal(amount(states.b,IRON),amount(originalBuyer,IRON)+7);return{firstPayment,secondPayment,reversal,...states};
    });
    await stage('third server saves all worlds and stops normally',async()=>{await close();return{clean:true};});
    evidence.status='passed';console.log('PASS '+evidence.steps.length+' tax stages: '+directory);
  }catch(error){evidence.status='failed';evidence.error=error.stack;console.error(error.stack+'\n'+server.lines.slice(-25).join('\n'));process.exitCode=1;}
  finally{try{await close();}catch(error){evidence.cleanupError=error.stack;evidence.status='failed';process.exitCode=1;}evidence.finishedAt=new Date().toISOString();save();fs.writeFileSync(path.join(runtime,'tax-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

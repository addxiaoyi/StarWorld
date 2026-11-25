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
async function poll(server,bot,predicate,label){return waitUntil(async()=>{const state=await snapshot(server,bot);return predicate(state)?state:null;},120000,label,()=>server.check());}
function terms(p,goods,tax,toll){assert.equal(Number(p.goodsTotal),goods);assert.equal(Number(p.taxAmount),tax);assert.equal(Number(p.total),(Math.round(goods*100)+Math.round(tax*100)+Math.round(toll*100))/100);}
async function main(){
  const args=argumentsOf(process.argv.slice(2));if(args.help){console.log('node transit-acceptance.cjs --java <java21> --paper <paper.jar> --runtime <prepared-isolated-transit-runtime> --client-deps <mineflayer>');return;}
  for(const name of ['java','paper','runtime'])assert(args[name],'Missing --'+name);
  const options={java:requireFile(args.java),paper:requireFile(args.paper),runtime:path.resolve(args.runtime),jvmArgs:['-Xms128m','-Xmx512m','-XX:ActiveProcessorCount=2','-XX:+UseSerialGC']};
  const runtime=options.runtime,plugins=path.join(runtime,'plugins'),config=path.join(plugins,'StarWorld');
  assert.equal(fs.readFileSync(path.join(runtime,'.starworld-smoke-runtime'),'utf8').trim(),'StarWorld isolated Paper smoke runtime v1');
  const props=properties(path.join(runtime,'server.properties'));assert.equal(props['server-ip'],'127.0.0.1');assert.equal(props['online-mode'],'false');assert.equal(properties(path.join(runtime,'eula.txt')).eula,'true');options.port=Number(props['server-port']);
  assert.equal(configuredNumber(path.join(config,'config.yml'),'period-seconds'),10);assert.equal(configuredNumber(path.join(config,'config.yml'),'event-chance'),0);assert.equal(configuredNumber(path.join(config,'logistics.yml'),'blocks-per-period'),8);
  const unit=read(path.join(__dirname,'../../docs/evidence/unit-tests.json')),upstream=read(path.join(runtime,'STARCORE-PROVENANCE.json')),prep=read(path.join(runtime,'PREPARATION.json'));
  assert.match(unit.version,/^1\.(?:1[0-9]|[2-9][0-9]+)\./);assert.equal(unit.artifact_sha256,hash(path.join(plugins,'StarWorld.jar')));assert(unit.totals.tests>0);assert.equal(unit.totals.failures+unit.totals.errors+unit.totals.skipped,0);
  assert.equal(upstream.artifact_sha256,hash(path.join(plugins,'StarCore.jar')));assert.equal(prep.targetPluginSha256,unit.artifact_sha256);assert.equal(prep.suite,'transit');assert.equal(prep.runnerSha256,hash(__filename));
  const requireClient=args['client-deps']?createRequire(path.resolve(args['client-deps'],'package.json')):require,mineflayer=requireClient('mineflayer');
  const directory=path.join(runtime,'transit-acceptance',new Date().toISOString().replace(/[:.]/g,'-'));fs.mkdirSync(directory,{recursive:true});
  const evidence={schema:1,suite:'real-paper-third-country-transit',status:'running',version:unit.version,artifactSha256:unit.artifact_sha256,startedAt:new Date().toISOString(),minecraft:'1.21.11',node:process.version,mineflayer:requireClient('mineflayer/package.json').version,
    paperSha256:hash(options.paper),runnerSha256:hash(__filename),acceptanceHelperSha256:hash(path.join(__dirname,'acceptance.cjs')),upstream,preparationSha256:hash(path.join(runtime,'PREPARATION.json')),pluginJars:{},configSha256:{},steps:[],
    testTuning:{jvmArgs:options.jvmArgs,nodeExecArgv:process.execArgv,periodSeconds:10,blocksPerPeriod:8,eventChance:0,physicalSeedsAreProductionEvidence:false,treasuryInterventionsAreEarnedIncome:false,taxConfigurationsAreExplicitFixtureInterventions:true}};
  for(const file of fs.readdirSync(plugins))if(file.endsWith('.jar'))evidence.pluginJars[file]=hash(path.join(plugins,file));
  for(const file of fs.readdirSync(config))if(file.endsWith('.yml'))evidence.configSha256[file]=hash(path.join(config,file));evidence.configSha256['StarCore/config.yml']=hash(path.join(plugins,'StarCore/config.yml'));
  const save=()=>fs.writeFileSync(path.join(directory,'evidence.json'),JSON.stringify(evidence,null,2)+'\n');
  const stage=async(name,work)=>{const step={name,status:'running',startedAt:new Date().toISOString()};evidence.steps.push(step);save();try{step.observed=await work();step.status='passed';console.log('[PASS] '+name);return step.observed;}catch(error){step.status='failed';step.error=error.stack;throw error;}finally{step.finishedAt=new Date().toISOString();save();}};
  let server=new Paper(options,directory,1),seller,buyer,transit,route,alternate,listing,first,second,refund,approved,originalSeller,originalBuyer,originalTransit,ids;
  const players=async run=>{seller=await connect(mineflayer,{...options,username:'StarWorldXA'},directory,run+'-a');buyer=await connect(mineflayer,{...options,username:'StarWorldXB'},directory,run+'-b');transit=await connect(mineflayer,{...options,username:'StarWorldXC'},directory,run+'-c');};
  const close=async()=>{for(const bot of [seller,buyer,transit])if(bot){bot.end('transit acceptance');await bot.finishTranscript();}seller=buyer=transit=null;if(server.child)await server.stop();};
  const purchase=(s,id)=>s.purchases.find(p=>p.id===id),order=(s,id)=>s.orders.find(o=>o.id===purchase(s,id)?.shipmentId),lot=s=>s.listings.find(l=>l.id===listing);
  const states=async()=>({a:await snapshot(server,seller),b:await snapshot(server,buyer),c:await snapshot(server,transit)});
  const money=async(payer,receiver,toll)=>{const s=await states();assert.equal(Number(s.a.treasury),receiver);assert.equal(Number(s.b.treasury),payer);assert.equal(Number(s.c.treasury),toll);return s;};
  const permit=()=>fixture(server,'exp-transit-permit',transit,route);
  const propose=async(routeId,fee)=>{const message=await command(transit,'/sw route transit-propose '+routeId+' '+fee+' 24',/许可草案 [0-9a-f-]{36}/);return message.match(/许可草案 ([0-9a-f-]{36})/)[1];};
  const approve=async(routeId,fee)=>{const draft=await propose(routeId,fee);await command(transit,'/sw route transit-approve '+draft,/已核对批准回执/);return draft;};
  const preview=async()=>{const message=await command(buyer,'/sw market buy '+listing+' '+route+' 7',/市场报价 [0-9a-f-]{36}/);const id=message.match(/市场报价 ([0-9a-f-]{36})/)[1];return{message,...(await fixture(server,'exp-market-quote',buyer,id)).quote};};
  const replay=async(action,id)=>{const proof=await fixture(server,action,buyer,id);assert.equal(proof.replay.status,'CONFIRMED');assert.deepEqual(proof.balancesBefore,proof.balancesAfter);assert.equal(Object.keys(proof.balancesBefore).length,3);return proof;};
  try{
    await stage('isolated Paper starts with three ordinary clients and public transit authority',async()=>{await server.start();await players(1);return{pid:server.child.pid,players:[seller.username,buyer.username,transit.username]};});
    await stage('three adjacent nations build a finite real road and deposit explicit physical inputs',async()=>{
      const setups=[];for(const[bot,slot]of[[seller,0],[transit,1],[buyer,2]])setups.push(await fixture(server,'exp-transit-setup',bot,String(slot)));
      const terrain=await fixture(server,'exp-transit-build',seller),seeds=[];
      for(const bot of [seller,buyer,transit]){server.command('deop '+bot.username);const permission=await fixture(server,'exp-permissions',bot);assert.equal(permission.admin,false);assert.equal(permission.use,true);const seed=await fixture(server,'exp-progress-seed',bot);seeds.push(seed);for(const[item,count]of Object.entries(seed.items))await command(bot,'/sw deposit '+item+' '+count,new RegExp('已存入 '+count+' '+item));}
      const fund=await fixture(server,'exp-fund',buyer,'1000');
      for(const[bot,slot]of[[seller,0],[transit,1],[buyer,2]]){const from=bot.messages.length;await fixture(server,'exp-transit-node',bot,String(slot));await waitUntil(()=>bot.messages.slice(from).some(m=>m.includes('已登记 ')),10000,'transit road registration');}
      originalSeller=await snapshot(server,seller);originalBuyer=await snapshot(server,buyer);originalTransit=await snapshot(server,transit);ids={seller:originalSeller.city.nationId,buyer:originalBuyer.city.nationId,transit:originalTransit.city.nationId};
      return{setups,terrain,seeds,fund,seller:originalSeller,buyer:originalBuyer,transit:originalTransit};
    });
    await stage('foreign node declaration binds a corridor but cannot grant passage or charge money',async()=>{
      const created=await command(seller,'/sw route create '+originalBuyer.city.id+' land '+[originalSeller.nodes[0].id,originalTransit.nodes[0].id,originalBuyer.nodes[0].id].join(','),/商路 [0-9a-f-]{36} ·/);route=created.match(/商路 ([0-9a-f-]{36})/)[1];
      const listed=await command(seller,'/sw market sell '+IRON+' 20 2.50',/挂牌已托管真实商品/);listing=listed.match(/商品：[ ]*([0-9a-f-]{36})/)[1];await fixture(server,'exp-tariff',seller,'3');
      await command(buyer,'/sw market quote '+listing+' '+route+' 7',/第三国尚未批准/);await command(buyer,'/sw route transit-propose '+route+' 0.10 24',/本国不在已声明过境路线/);
      const proof=await permit();assert(!proof.permit);assert.equal(proof.route.corridorRequired,true);assert(proof.route.transitKey);const s=await money(1000,0,0);assert.equal(s.a.purchases.length,0);assert.equal(lot(s.a).availableUnits,20);return{route,listing,proof,...s};
    });
    await stage('only the transit founder may approve the exact corridor and refundable per unit toll',async()=>{
      const draft=await propose(route,'0.10');await command(buyer,'/sw route transit-approve '+draft,/仅许可所属国家/);assert(!(await permit()).permit);
      await command(transit,'/sw route transit-approve '+draft,/已核对批准回执/);const proof=await permit();assert.equal(proof.permit.revision,1);assert.equal(proof.evaluation.allowed,true);assert.equal(proof.permit.terms.parcels.length,2);assert.equal(Number(proof.permit.terms.tollPerUnit),0.10);return{draft,proof};
    });
    await stage('quotes reserve nothing and changed permit revisions require renewed buyer consent',async()=>{
      const old=await preview();assert.match(old.message,/应付 18\.72/);assert.equal(Number(Object.values(old.transit)[0].terms.tollPerUnit),0.10);await money(1000,0,0);
      const changed=await approve(route,'0.20');await command(buyer,'/sw market buy-quote '+old.id,/过境许可或收费已变化/);
      approved=await approve(route,'0.10');const proof=await permit();assert.equal(proof.permit.revision,3);const s=await money(1000,0,0);assert.equal(lot(s.a).availableUnits,20);assert.equal(s.a.purchases.length,0);return{old,changed,approved,proof,...s};
    });
    await stage('one public atomic settlement pays seller goods plus tax and the third country toll',async()=>{
      const q=await preview();await command(buyer,'/sw market buy-quote '+q.id,/购买已准备.*原子支付 18\.72/);first=q.id;
      const a=await poll(server,seller,s=>purchase(s,first)?.status==='SHIPPED'&&Number(s.treasury)===18.02,'three party paid shipment');terms(purchase(a,first),17.5,0.52,0.70);
      const proof=await fixture(server,'exp-market-receipt',buyer,first);assert.equal(proof.entryPoint,'NationSettlementService.settlementReceipt');assert.equal(Number(proof.receipt.amount),18.72);assert.deepEqual(Object.values(proof.receipt.credits).map(Number).sort((a,b)=>a-b),[0.70,18.02]);const s=await money(981.28,18.02,0.70);return{quote:q,proof,...s};
    });
    await stage('revocation pauses paid cargo and replaying the former approval cannot restore permission',async()=>{
      await command(transit,'/sw route transit-revoke '+route+' 3',/已核对撤销回执/);const a=await poll(server,seller,s=>order(s,first)?.status==='PAUSED','revoked transit pause');
      await command(transit,'/sw route transit-approve '+approved,/原操作版本 3.*当前版本 4.*已撤销/);const proof=await permit();assert.equal(proof.permit.revision,4);assert.equal(proof.permit.revoked,true);assert.equal(proof.evaluation.allowed,false);
      const s=await money(981.28,18.02,0.70);assert.equal(amount(s.c,'minecraft:oak_planks'),amount(originalTransit,'minecraft:oak_planks'));return{paused:a,proof,...s};
    });
    await stage('one insolvent toll beneficiary freezes the whole refund and keeps physical goods escrowed',async()=>{
      const drain=await fixture(server,'exp-refund-drain',transit);assert.equal(Number(drain.before),0.70);const requested=await command(buyer,'/sw market refund '+first,/退款申请 [0-9a-f-]{36}/);refund=requested.match(/退款申请 ([0-9a-f-]{36})/)[1];await command(seller,'/sw market refund-accept '+refund,/退款 .* PENDING/);
      const a=await poll(server,seller,s=>s.refunds.find(r=>r.id===refund)?.status==='RETRY','atomic refund insolvency');assert.equal(order(a,first).status,'REFUNDING');assert.equal(amount(a,IRON),amount(originalSeller,IRON)-20);const s=await money(981.28,18.02,0);return{drain,...s};
    });
    await stage('restoring the exact withdrawn toll permits one full reversal and returns seven goods',async()=>{
      const restored=await fixture(server,'exp-fund',transit,'0.70');await command(buyer,'/sw market refund-retry '+refund,/退款 .* PENDING/);
      await poll(server,seller,s=>s.refunds.find(r=>r.id===refund)?.status==='COMPLETED'&&amount(s,IRON)===amount(originalSeller,IRON)-13,'all beneficiaries reversed and goods returned');const s=await money(1000,0,0);assert.equal(purchase(s.a,first).status,'REFUNDED');assert.equal(amount(s.b,IRON),amount(originalBuyer,IRON));const proof=await replay('exp-refund-replay',refund);assert.deepEqual(Object.values(proof.receipt.credits).map(Number).sort((a,b)=>a-b),[0.70,18.02]);return{restored,proof,...s};
    });
    await stage('first normal shutdown preserves revoked permission and the completed atomic refund',async()=>{await close();return{first,refund,clean:true};});
    server=new Paper(options,directory,2);
    await stage('restart restores revocation and replays all original receipts without any new money',async()=>{
      await server.start();await players(2);const proof=await permit();assert.equal(proof.permit.revision,4);assert.equal(proof.evaluation.allowed,false);const payment=await replay('exp-market-replay',first),reverse=await replay('exp-refund-replay',refund);const s=await money(1000,0,0);assert.equal(amount(s.a,IRON),amount(originalSeller,IRON)-13);return{proof,payment,reverse,...s};
    });
    await stage('fresh approval and consent create the second paid shipment at the same frozen prices',async()=>{
      const draft=await approve(route,'0.10'),proof=await permit();assert.equal(proof.permit.revision,5);const q=await preview();await command(buyer,'/sw market buy-quote '+q.id,/购买已准备.*原子支付 18\.72/);second=q.id;
      await poll(server,seller,s=>purchase(s,second)?.status==='SHIPPED','second paid transit');const s=await money(981.28,18.02,0.70);terms(purchase(s.a,second),17.5,0.52,0.70);return{draft,proof,quote:q,...s};
    });
    await stage('actual core war with an endpoint denies the existing third country permit and pauses delivery',async()=>{
      const war=await fixture(server,'exp-war',transit,seller.username);const proof=await permit();assert.equal(proof.evaluation.allowed,false);const a=await poll(server,seller,s=>order(s,second)?.status==='PAUSED','wartime transit blocked');return{war,proof,a};
    });
    await stage('peace still cannot restore passage through a missing national claim or a physical road gap',async()=>{
      const peace=await fixture(server,'exp-peace',transit,seller.username),removed=await fixture(server,'exp-transit-unclaim',transit);assert.equal((await permit()).evaluation.allowed,false);
      await command(seller,'/sw route inspect '+route,/双层领土未被路线声明城市覆盖/);const restored=await fixture(server,'exp-transit-reclaim',transit),gap=await fixture(server,'exp-transit-gap',transit);assert.equal((await permit()).evaluation.allowed,true);
      await command(seller,'/sw route inspect '+route,/道路路面缺失/);const a=await poll(server,seller,s=>order(s,second)?.status==='PAUSED'&&order(s,second).reason.includes('路面缺失'),'real road gap pause');return{peace,removed,restored,gap,a};
    });
    await stage('rerouting to a different charged corridor cannot reuse the original route payment',async()=>{
      const repair=await fixture(server,'exp-transit-repair',transit);const created=await command(seller,'/sw route create '+originalBuyer.city.id+' land '+[originalSeller.nodes[0].id,originalTransit.nodes[0].id,originalBuyer.nodes[0].id].join(','),/商路 [0-9a-f-]{36} ·/);alternate=created.match(/商路 ([0-9a-f-]{36})/)[1];const draft=await approve(alternate,'0.10');
      const a=await snapshot(server,seller);await command(seller,'/sw trade reroute '+purchase(a,second).shipmentId+' '+alternate,/当前过境收费与原付款不符/);const s=await money(981.28,18.02,0.70);assert.equal(order(s.a,second).routeId,route);return{repair,alternate,draft,...s};
    });
    await stage('both endpoints explicitly accept a free alternative without additional national payment',async()=>{
      const draft=await approve(alternate,'0.00'),a=await snapshot(server,seller);const message=await command(seller,'/sw trade reroute '+purchase(a,second).shipmentId+' '+alternate,/改道提议 [0-9a-f-]{36}/);const request=message.match(/改道提议 ([0-9a-f-]{36})/)[1];await command(buyer,'/sw trade reroute-accept '+request,/改道.*APPLIED|已确认改道|改道已生效/);
      const s=await money(981.28,18.02,0.70);assert.equal(order(s.a,second).routeId,alternate);assert.equal(amount(s.c,'minecraft:oak_planks'),amount(originalTransit,'minecraft:oak_planks'));return{draft,request,...s};
    });
    await stage('second normal shutdown preserves the rerouted cargo and original payment allocation',async()=>{await close();return{second,alternate,clean:true};});
    server=new Paper(options,directory,3);
    await stage('third start replays original allocations and resumes the free alternative after restart',async()=>{await server.start();await players(3);const payment=await replay('exp-market-replay',second),reverse=await replay('exp-refund-replay',refund),s=await money(981.28,18.02,0.70);assert.equal(order(s.a,second).routeId,alternate);return{payment,reverse,...s};});
    await stage('actual delivery conserves physical goods and counts only the goods subtotal as trade volume',async()=>{
      const b=await poll(server,buyer,s=>order(s,second)?.status==='COMPLETED'&&amount(s,IRON)===amount(originalBuyer,IRON)+7,'rerouted actual delivery');const stats=await waitUntil(async()=>{const value=await fixture(server,'exp-operations',seller,originalBuyer.city.id);return value.globalDeliveryStats.some(row=>row.item===IRON)?value:null;},20000,'transit goods-only statistics');
      for(const rows of [stats.deliveryStats,stats.globalDeliveryStats]){assert.equal(rows.length,1);assert.equal(rows[0].orders,1);assert.equal(rows[0].units,7);assert.equal(Number(rows[0].gross),17.5);assert.equal(Number(rows[0].averageUnitPrice),2.5);}
      await command(buyer,'/sw market refund '+second,/已交付/);await command(seller,'/sw market cancel '+listing,/挂牌已关闭/);await poll(server,seller,s=>amount(s,IRON)===amount(originalSeller,IRON)-7,'unsold goods returned');const s=await money(981.28,18.02,0.70);
      assert.equal(amount(s.c,'minecraft:oak_planks'),amount(originalTransit,'minecraft:oak_planks'));assert.equal(lot(s.a).returnedUnits,13);assert.equal(lot(s.a).shippedUnits,7);assert.equal(lot(s.a).availableUnits+lot(s.a).reservedUnits,0);return{b,stats,...s};
    });
    await stage('third server saves all worlds and stops normally',async()=>{await close();return{clean:true};});
    evidence.status='passed';console.log('PASS '+evidence.steps.length+' transit stages: '+directory);
  }catch(error){evidence.status='failed';evidence.error=error.stack;console.error(error.stack+'\n'+server.lines.slice(-25).join('\n'));process.exitCode=1;}
  finally{try{await close();}catch(error){evidence.cleanupError=error.stack;evidence.status='failed';process.exitCode=1;}evidence.finishedAt=new Date().toISOString();save();fs.writeFileSync(path.join(runtime,'transit-acceptance','latest.json'),JSON.stringify({directory,status:evidence.status},null,2)+'\n');}
}
if(require.main===module)main().catch(error=>{console.error(error.stack);process.exitCode=1;});

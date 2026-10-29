const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname,'../../main/resources/monitor/monitor.js'),'utf8');
const styles = fs.readFileSync(path.resolve(__dirname,'../../main/resources/monitor/monitor.css'),'utf8');
function harness() {
  const nodes = new Map();
  class Node {
    constructor(tag='div') {this.tagName=tag.toUpperCase();this.children=[];this.dataset={};this.value='';this.textContent='';}
    append(...nodes) {this.children.push(...nodes);}
    replaceChildren(...nodes) {this.children=nodes; if(this.tagName==='SELECT') this.value=nodes[0]?.value || '';}
    removeAttribute(name) {delete this[name];}
    addEventListener(name, fn) {(this.listeners ||= {})[name]=fn;}
    get cells() {return this.children;}
  }
  const radios=['synthetic','boutique'].map(value=>Object.assign(new Node('input'),{value,checked:value==='synthetic'}));
  const get=id=>{if(!nodes.has(id))nodes.set(id,new Node(id==='scenario'?'select':'div'));return nodes.get(id);};
  const document={getElementById:get,createElement:tag=>new Node(tag),querySelector:selector=>selector.startsWith('meta')?{content:'secret'}:radios.find(r=>r.checked),querySelectorAll:selector=>selector.startsWith('#source-toggle')?radios:[]};
  const requests=[];
  const context=vm.createContext({document,console,setTimeout(){},fetch:async(url,options)=>{requests.push([url,options]);return {ok:false,text:async()=> 'Injection refused'};}});
  vm.runInContext(source,context);
  return {get,radios,requests,run:code=>vm.runInContext(code,context)};
}
test('source filters exact scenario ids and exposes fault control only for fault modes',()=>{
 const h=harness();h.run("selectSource('boutique'); renderBoutique({})");
 assert.deepEqual(h.get('scenario').children.map(n=>n.value),['boutique-live','boutique-deadlock','boutique-spare-capacity']);
 assert.equal(h.get('inject').hidden,true);
 h.get('scenario').value='boutique-deadlock';h.run("renderBoutique({application:'BOUTIQUE',injectable:true})");
 assert.equal(h.get('inject').hidden,false);assert.equal(h.get('inject').disabled,false);
});
test('store URLs are allowlisted and response text is never HTML',()=>{
 const h=harness();h.run(`selectSource('boutique'); renderBoutique({application:'BOUTIQUE',store:{store_url:'javascript:alert(1)',recent_ads:[{redirect_url:'/product/ABCDEFGHIJ',text:'<img src=x onerror=alert(1)>'},{redirect_url:'//evil.test',text:'unsafe'}]}})`);
 assert.equal(h.get('store-link').hidden,true);
 const ads=h.get('store-ads').children;
 assert.equal(ads[0].href,'http://localhost:8080/product/ABCDEFGHIJ');assert.equal(ads[0].textContent,'<img src=x onerror=alert(1)>');assert.equal(ads[1].tagName,'P');
 h.run("renderBoutique({application:'BOUTIQUE',store:{store_url:'http://127.0.0.1:8080'}})");assert.equal(h.get('store-link').hidden,false);
 h.run("renderBoutique({application:'CHAIN'})");assert.equal(h.get('store-panel').hidden,true);assert.equal(h.get('store-link').href,undefined);assert.equal(h.get('store-ads').children.length,0);
});
test('render accepts idle Chain snapshot without new contract fields',()=>{
 const h=harness();h.run("render({phase:'IDLE',manifest:null,observed_total:0})");assert.equal(h.get('start').disabled,false);assert.match(h.get('source').textContent,/Chain\/Call/);
});
test('slots use bounded latest map even when display observations were truncated',()=>{
 const h=harness();h.run(`render({phase:'RUNNING',application:'BOUTIQUE',application_alive:true,observed_total:10000,discarded_observations:9000,observations:[],progress:[],manifest:{run_id:'test',instances:[{instance_id:'a',service_binding:'ads-a'}],pools:[{instance_id:'a',unit_ids:['slot-0']}]},service_latest:[{instance_id:'a',local_seq:140,blocked:2,queued:1}],unit_latest:[{instance_id:'a',unit_id:'slot-0',state:'held',execution_id:'exec',local_seq:100}]})`);
 assert.equal(h.get('services').children[0].children.at(-1).children[0].className,'slot held');
 assert.match(h.get('services').children[0].children[1].textContent,/seq 140/);
 assert.equal(h.get('services').children[0].children[2].textContent,'2 blocked · 1 queued');
});
test('active snapshot follows source and prevents source switching; Chain resets Boutique state',()=>{
 const h=harness();h.run("render({phase:'RUNNING',application:'BOUTIQUE',application_alive:true,manifest:null})");
 assert.equal(h.radios[1].checked,true);assert.ok(h.radios.every(r=>r.disabled));
 h.run("render({phase:'RUNNING',application:'CHAIN',application_alive:true,manifest:null})");
 assert.equal(h.radios[0].checked,true);assert.equal(h.get('store-panel').hidden,true);assert.equal(h.get('inject').hidden,true);
});
test('inject uses control token, blocks duplicate requests and renders rejection text',async()=>{
 const h=harness();
 h.run("offline=false; latestState={application:'BOUTIQUE',injectable:true};");
 let resolve;
 const pending=new Promise(r=>resolve=r);
 const global=h.run('globalThis');global.fetch=async(url,options)=>{h.requests.push([url,options]);return pending;};
 const handler=h.get('inject').listeners.click;
 const first=handler();await handler();
 assert.equal(h.requests.filter(([url])=>url==='/api/inject').length,1);
 assert.equal(h.requests.at(-1)[1].headers['X-SpanLease-Control'],'secret');
 resolve({ok:false,text:async()=>'<img src=x> refused'});await first;
 assert.equal(h.get('control-note').textContent,'<img src=x> refused');
});
test('reload of existing Boutique fault run selects its declared scenario',()=>{
 for (const id of ['boutique-deadlock','boutique-spare-capacity']) {
  const h=harness();h.run(`render({phase:'RUNNING',application:'BOUTIQUE',application_alive:true,injectable:true,observations:[],progress:[],manifest:{run_id:'${id}-123',instances:[],pools:[]}})`);
  assert.equal(h.get('scenario').value,id);assert.equal(h.get('inject').hidden,false);
 }
});
test('old snapshots never reconstruct slot state in the browser',()=>{
 const h=harness();h.run(`render({phase:'RUNNING',application_alive:true,observations:[{event:{instance_id:'a',local_seq:1,payload:{event_type:'resource.init',resource_id:'app',unit_ids:['slot-0']}}}],progress:[],manifest:{run_id:'closed-ring-123',instances:[{instance_id:'a',service_binding:'svcA'}],pools:[{instance_id:'a',resource_id:'app',unit_ids:['slot-0']}]}})`);
 assert.equal(h.get('services').children[0].children.at(-1).children[0].className,'slot unknown');
});
test('successful inject remains disabled across stale injectable snapshots',async()=>{
 const h=harness();h.run("offline=false; selectSource('boutique'); $('scenario').value='boutique-deadlock'; latestState={application:'BOUTIQUE',injectable:true};");
 h.run('globalThis').fetch=async()=>({ok:true});
 await h.get('inject').listeners.click();
 h.run("renderBoutique({application:'BOUTIQUE',injectable:true})");
 assert.equal(h.get('inject').disabled,true);
});
test('flat theme styles every semantic class emitted by the monitor',()=>{
 for (const selector of ['.service.stopped','.verdict.resolved','.result-good','.result-confirmed','.badge.queued']) {
  assert.ok(styles.includes(`${selector}{`),`missing ${selector} style`);
 }
});

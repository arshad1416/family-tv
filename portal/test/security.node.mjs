// Offline fallback: run with Node 22 against tsc-emitted production src + test/d1.js.
// PORTAL_COMPILED_DIR points at disposable output with relative .js imports and migrations.
import test from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { runInNewContext } from 'node:vm';
const compiled = process.env.PORTAL_COMPILED_DIR;
if (!compiled) throw new Error('PORTAL_COMPILED_DIR required');
const load = (path) => import(pathToFileURL(resolve(compiled, path)).href);
const { default: worker, normalizeGuideStatus } = await load('src/index.js');
const { ADMIN_PAGE } = await load('src/admin-page.js');
const { sealJson, openJson, sha256Hex } = await load('src/crypto.js');
const { memoryD1 } = await load('test/d1.js');
const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(11)));
const FAKE = 'fixture-only-not-a-real-rd-key-123';
const NEXT = 'fixture-only-replacement-rd-key-456';
async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: 'admin' };
  for (const p of ['ab12', 'cd34']) await env.DB.prepare('INSERT INTO profiles(id,name,data_enc,created_at,updated_at) VALUES (?,?,?,0,0)').bind(p,p,await sealJson(KEY,{sources:[]})).run();
  for (const [id,p] of [['lounge','ab12'],['bedroom','ab12'],['away','cd34'],['new',null]]) await env.DB.prepare('INSERT INTO devices(id,token_hash,profile_id,created_at) VALUES(?,?,?,0)').bind(id,await sha256Hex(id+'-token'),p).run();
  return env;
}
const req = (env, device='lounge', method='GET', body, profileId='ab12') => worker.fetch(new Request('https://portal.test/api/device/realdebrid/api-key'+(method==='GET'?'?profileId='+profileId:''),{method,headers:{authorization:'Bearer '+device+'-token','content-type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)}),env);
const put = (env,revision,apiKey=FAKE,device='lounge') => req(env,device,'PUT',{profileId:'ab12',revision,apiKey});
const del = (env,revision) => req(env,'lounge','DELETE',{profileId:'ab12',revision});

test('API key reads and mutations require HTTPS without changing credential state',async()=>{
  const env=await setup();await put(env,0);
  for(const method of ['GET','PUT','DELETE']){
    const response=await worker.fetch(new Request('http://portal.test/api/device/realdebrid/api-key?profileId=ab12',{method,headers:{authorization:'Bearer lounge-token','content-type':'application/json'},body:method==='GET'?undefined:JSON.stringify({profileId:'ab12',revision:1,apiKey:NEXT})}),env);
    assert.equal(response.status,426);assert.deepEqual(await response.json(),{error:'HTTPS required'});
  }
  const current=await(await req(env,'bedroom')).json();assert.equal(current.revision,1);assert.equal(current.apiKey,FAKE);
});

test('absent state, same-profile sharing, no-store and no secret write response', async()=>{
  const env=await setup();
  assert.deepEqual(await(await req(env)).json(),{profileId:'ab12',revision:0,configured:false,apiKey:null});
  assert.deepEqual(await(await put(env,0)).json(),{profileId:'ab12',revision:1,configured:true});
  const r=await req(env,'bedroom'); assert.equal(r.headers.get('cache-control'),'no-store');
  assert.deepEqual(await r.json(),{profileId:'ab12',revision:1,configured:true,apiKey:FAKE});
});
test('unknown/admin/unpaired/cross-profile cannot access all methods',async()=>{
  const env=await setup();
  for(const device of ['unknown','new','away']) for(const method of ['GET','PUT','DELETE']) assert.equal((await req(env,device,method,method==='GET'?undefined:{profileId:'ab12',revision:0,apiKey:FAKE})).status,device==='unknown'?401:403);
  assert.equal((await worker.fetch(new Request('https://portal.test/api/device/realdebrid/api-key?profileId=ab12',{headers:{authorization:'Bearer admin'}}),env)).status,401);
  assert.equal((await req(env,'lounge','GET',undefined,'cd34')).status,403);
  assert.equal((await req(env,'lounge','PUT',{profileId:'cd34',revision:0,apiKey:FAKE})).status,403);
});
test('encrypted profile-bound envelope and swapped ciphertext rejected',async()=>{
  const env=await setup();await put(env,0);
  const row=await env.DB.prepare("SELECT key_enc FROM profile_rd_api_keys WHERE profile_id='ab12'").first();
  assert.ok(!row.key_enc.includes(FAKE)); assert.deepEqual(await openJson(KEY,row.key_enc),{profileId:'ab12',apiKey:FAKE});
  await env.DB.prepare("INSERT INTO profile_rd_api_keys VALUES('cd34',1,?,0)").bind(row.key_enc).run();
  const r=await req(env,'away','GET',undefined,'cd34');assert.equal(r.status,500);assert.ok(!(await r.text()).includes(FAKE));
});
test('no keys in routine config/admin/heartbeat/commands',async()=>{
  const env=await setup();await put(env,0);
  for(const path of ['/api/device/config','/api/admin/profiles','/api/admin/devices']){
    const r=await worker.fetch(new Request('https://portal.test'+path,{headers:{authorization:'Bearer '+(path.includes('/admin/')?'admin':'lounge-token')}}),env);
    assert.equal(r.status,200);assert.ok(!(await r.text()).includes(FAKE));
  }
  const r=await worker.fetch(new Request('https://portal.test/api/device/heartbeat',{method:'POST',headers:{authorization:'Bearer lounge-token'},body:'{}'}),env);assert.equal(r.status,200);assert.ok(!(await r.text()).includes(FAKE));
  assert.deepEqual((await env.DB.prepare('SELECT * FROM commands').all()).results,[]);
});
test('remove tombstone blocks stale replace and old remove cannot erase replacement',async()=>{
  const env=await setup();await put(env,0);assert.deepEqual(await(await del(env,1)).json(),{profileId:'ab12',revision:2,configured:false});
  assert.equal((await put(env,1)).status,409);assert.equal((await put(env,0)).status,409);
  assert.equal((await put(env,2,NEXT)).status,200);assert.equal((await del(env,2)).status,409);
  assert.equal((await(await req(env)).json()).apiKey,NEXT);
  const fresh=await setup();assert.equal((await del(fresh,0)).status,200);assert.equal((await put(fresh,0)).status,409);
});
test('concurrent revision updates have exactly one winner',async()=>{
  const env=await setup();const replies=await Promise.all([put(env,0),put(env,0,NEXT,'bedroom')]);assert.deepEqual(replies.map(r=>r.status).sort(),[200,409]);
});
test('current-profile/token predicate rejects mid-encrypt reassignment and revocation',async()=>{
  for(const sql of ["UPDATE devices SET profile_id='cd34' WHERE id='lounge'","DELETE FROM devices WHERE id='lounge'"]){
    const env=await setup();const encrypt=crypto.subtle.encrypt;
    crypto.subtle.encrypt=async function(...args){const result=await encrypt.apply(this,args);await env.DB.prepare(sql).run();return result;};
    try{assert.equal((await put(env,0)).status,403);assert.equal(await env.DB.prepare('SELECT * FROM profile_rd_api_keys').first(),null);}finally{crypto.subtle.encrypt=encrypt;}
  }
});
test('recheck after decrypt rejects moved device and revoked key',async()=>{
  for(const sql of ["UPDATE devices SET profile_id='cd34' WHERE id='lounge'","UPDATE profile_rd_api_keys SET revision=2,key_enc=NULL WHERE profile_id='ab12'"]){
    const env=await setup();await put(env,0);const decrypt=crypto.subtle.decrypt;
    crypto.subtle.decrypt=async function(...args){const result=await decrypt.apply(this,args);await env.DB.prepare(sql).run();return result;};
    try{const r=await req(env);assert.ok([403,409].includes(r.status));assert.ok(!(await r.text()).includes(FAKE));}finally{crypto.subtle.decrypt=decrypt;}
  }
});
test('validation refuses malformed keys/revisions without upstream requests',async()=>{
  const env=await setup();const fetch=globalThis.fetch;globalThis.fetch=()=>{throw new Error('network forbidden');};
  try{
    for(const key of ['short','spaces are invalid here','x'.repeat(513),'é'.repeat(32),null,42])assert.equal((await put(env,0,key)).status,400);
    for(const revision of [-1,0.5,'0',Number.MAX_SAFE_INTEGER])assert.equal((await del(env,revision)).status,400);
    assert.equal((await put(env,0)).status,200);
  }finally{globalThis.fetch=fetch;}
});
const esc=ADMIN_PAGE.match(/^const esc = .*;$/m)[0];
const guideLine=ADMIN_PAGE.match(/^function guideLine\(g\) \{[\s\S]*?^\}/m)[0];
const render=input=>runInNewContext(esc+'\n'+guideLine+'\nguideLine(input)',{input});
const hostile='<img src="data:,x" onerror="alert(1)">';
test('all old stored numeric-guide siblings reject markup strings/objects/arrays',()=>{
  for(const field of ['switched','thin','unmatchedCount','linked'])for(const value of [hostile,[hostile],{x:hostile}]){
    const html=render({switched:1,thin:2,unmatchedCount:3,linked:{direct:4},unmatched:[],[field]:value});assert.ok(!html.includes('<img'));assert.ok(!html.includes('onerror'));
  }
});
test('old stored names/errors escape and malformed list fields do not crash',()=>{
  assert.ok(render({unmatchedCount:3,unmatched:[hostile],errors:[hostile]}).includes('&lt;img'));
  assert.ok(!render({unmatchedCount:3,unmatched:{},errors:{}}).includes('<img'));
  assert.ok(render({switched:2,thin:3,linked:{a:4,b:5}}).includes('9 channels linked'));
  const html=render({unmatchedCount:3,unmatched:[{toString:null},'Good channel',['ignored'],null],errors:[{toString:1},'Good error',42]});
  assert.ok(html.includes('Good channel'));assert.ok(html.includes('Good error'));assert.ok(!html.includes('ignored'));assert.ok(!html.includes('[object Object]'));
  assert.ok(render({unmatchedCount:1,unmatched:['x'.repeat(2000)],errors:Array(101).fill('bounded')}).length<3000);
});
test('new telemetry normalization independent of safe old-data renderer',()=>{
  assert.deepEqual(normalizeGuideStatus({playing:{title:hostile},guide:{switched:hostile,thin:[],unmatchedCount:{},linked:{a:hostile,b:2}}}),{playing:{title:hostile},guide:{switched:0,thin:0,unmatchedCount:0,linked:{a:0,b:2}}});
  assert.deepEqual(normalizeGuideStatus({guide:[hostile]}),{guide:null});assert.equal(normalizeGuideStatus(null),null);
});

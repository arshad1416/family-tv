import { describe, expect, it } from "vitest";
import worker, { latestApp, type Env } from "../src/index";
import { sealJson, sha256Hex } from "../src/crypto";
import { memoryD1 } from "./d1";
const KEY = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));
async function setup() {
  const env = { DB: memoryD1(), CONFIG_KEY: KEY, ADMIN_TOKEN: "admin" } as unknown as Env;
  await env.DB.prepare("INSERT INTO profiles (id,name,data_enc,created_at,updated_at) VALUES ('aa','aa',?,0,0)").bind(await sealJson(KEY,{sources:[]})).run();
  await env.DB.prepare("INSERT INTO devices (id,token_hash,profile_id,created_at) VALUES ('abc1',?,'aa',0)").bind(await sha256Hex("tv-token")).run();
  return env;
}
const call = (env: Env, path: string, payload?: unknown) => worker.fetch(new Request('https://portal.test'+path, {method:payload===undefined?'GET':'POST', headers:{authorization:'Bearer '+(path.includes('/admin/')?'admin':'tv-token'),'content-type':'application/json'},body:payload===undefined?undefined:JSON.stringify(payload)}),env);
describe('portal resilience',()=>{
  it('round-trips a diagnostic report longer than 64000 characters',async()=>{
    const env=await setup(); const report={summary:'x'.repeat(70000)};
    expect((await call(env,'/api/device/diagnostics',{verdict:'OK',report})).status).toBe(200);
    const detail=await call(env,'/api/admin/devices/abc1'); expect(detail.status).toBe(200);
    expect((await detail.json<any>()).diagnostics[0].report).toEqual(report);
  });
  it('keeps legacy truncated diagnostics from breaking device details',async()=>{
    const env=await setup();
    await env.DB.prepare("INSERT INTO diagnostics (id,device_id,created_at,verdict,report_json) VALUES ('bad','abc1',1,'UNKNOWN',?)").bind('{"summary":"cut').run();
    const detail=await call(env,'/api/admin/devices/abc1'); expect(detail.status).toBe(200);
    const data=await detail.json<any>(); expect(data.diagnostics[0].report).toBeNull(); expect(data.suggestions).toEqual([]);
  });
  it.each(['/api/device/config','/api/device/heartbeat'])('delivers commands during R2 outage: %s',async(path)=>{
    const env=await setup(); env.APPS={get:async()=>{throw new Error('R2 unavailable');}} as unknown as R2Bucket;
    await call(env,'/api/admin/devices/abc1/commands',{type:'refresh'});
    const res=await call(env,path,path.endsWith('heartbeat')?{}:undefined); expect(res.status).toBe(200);
    const data=await res.json<any>(); expect(data.commands.map((c:any)=>c.type)).toEqual(['refresh']); expect(data.latestApp).toBeNull();
  });
  it('uses configured fallback and briefly caches failed update reads',async()=>{
    let reads=0; const env={APPS:{get:async()=>{reads++;throw new Error('R2 unavailable');}},LATEST_APP_VERSION_CODE:'3',LATEST_APP_URL:'https://example.test/app.apk'} as unknown as Env;
    for(let i=0;i<2;i++) expect(await latestApp(env,new Request('https://portal.test'))).toEqual({versionCode:3,versionName:null,url:env.LATEST_APP_URL});
    expect(reads).toBe(1);
  });
});

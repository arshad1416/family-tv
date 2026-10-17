import { beforeAll, describe, expect, it, vi } from "vitest";
import worker, { type Env } from "../src/index";
import { memoryD1 } from "./d1";
import { sha256Hex } from "../src/crypto";

const ORIGIN = "https://familytv.arshadkazi.ca";
const LATEST = "stremio-lab/latest.json";
const KEY = "stremio-lab/builds/7/app.apk";
const APK = new TextEncoder().encode("APK fixture");
const TOKEN = "only-lab-publisher-fixture";
const digest = async (bytes: Uint8Array) => crypto.subtle.digest("SHA-256", bytes);
const hex = (bytes: ArrayBuffer) => [...new Uint8Array(bytes)].map(x => x.toString(16).padStart(2,"0")).join("");
// Node has no native Workers FixedLengthStream. This stand-in enforces the documented
// length contract; real Workers/R2 streaming still requires a pre-activation smoke test.
beforeAll(() => {
  vi.stubGlobal("FixedLengthStream", class extends TransformStream<Uint8Array, Uint8Array> {
    constructor(expected: number) {
      let count = 0;
      super({ transform(chunk, controller) {
        count += chunk.byteLength;
        if (count > expected) throw new Error("too long");
        controller.enqueue(chunk);
      }, flush() { if (count !== expected) throw new Error("too short"); } });
    }
  });
});

class MemoryBucket {
  objects = new Map<string, { bytes: Uint8Array; etag: string; checksum?: ArrayBuffer }>();
  calls: string[] = [];
  serial = 0;
  beforeFeedPut?: () => void;
  failPut = false;
  conditionFails = false;
  async seed(key: string, value: Uint8Array | string, checksum = true) {
    const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
    this.objects.set(key, { bytes, etag: String(++this.serial), checksum: checksum ? await digest(bytes) : undefined });
  }
  object(key: string) {
    const row = this.objects.get(key);
    if (!row) return null;
    return { key, size: row.bytes.length, etag: row.etag, httpEtag: '"'+row.etag+'"',
      checksums: { sha256: row.checksum }, body: new Blob([row.bytes]).stream() };
  }
  async head(key: string) { this.calls.push("head:"+key); return this.object(key); }
  async get(key: string) { this.calls.push("get:"+key); return this.object(key); }
  async put(key: string, value: ReadableStream | string, options: R2PutOptions) {
    this.calls.push("put:"+key);
    if (key === LATEST) this.beforeFeedPut?.();
    const old = this.objects.get(key);
    const condition = options.onlyIf as R2Conditional;
    if (this.conditionFails || (condition.etagDoesNotMatch === "*" && old) || (condition.etagMatches !== undefined && old?.etag !== condition.etagMatches)) return null;
    if (this.failPut) throw new Error("R2 failed before consuming stream");
    const bytes = new Uint8Array(await new Response(value).arrayBuffer());
    const checksum = await digest(bytes);
    if (options.sha256 && options.sha256 !== hex(checksum)) throw new Error("checksum rejected");
    await this.seed(key, bytes);
    return this.object(key);
  }
  binding() { return this as unknown as R2Bucket; }
}
async function setup() {
  const bucket = new MemoryBucket();
  const DB = memoryD1();
  await DB.prepare("INSERT INTO profiles (id, name, data_enc, created_at, updated_at) VALUES (?, ?, ?, 0, 0)").bind("profile","Lab test","fixture").run();
  for (const [id,token,profile] of [["a","paired","profile"],["b","unpaired",null]]) {
    await DB.prepare("INSERT INTO devices (id, token_hash, profile_id, created_at) VALUES (?, ?, ?, ?)").bind(id,await sha256Hex(token ?? ""),profile,1).run();
  }
  // Same real schema and nonnull shared profile assignment as existing devices.
  const env = { DB, APPS: bucket.binding(), ADMIN_TOKEN: "admin-fixture", STREMIO_LAB_PUBLISH_TOKEN: TOKEN } as Env;
  return { bucket, env };
}
const feed = async (code = 7) => ({ applicationId: "app.familytv.stremio", channel: "stremio-lab", versionCode: code,
  versionName: `0.1.${code}-stremio-lab`, url: `${ORIGIN}/stremio-lab/builds/${code}/app.apk`, sha256: hex(await digest(APK)), size: APK.length });
const request = (path: string, method = "GET", token?: string, body?: BodyInit, headers: Record<string,string> = {}) => new Request(ORIGIN+path, {
  method, body, headers: { ...(token ? { authorization: "Bearer "+token } : {}), ...headers } });
const run = (env: Env, path: string, method = "GET", token?: string, body?: BodyInit, headers?: Record<string,string>) => worker.fetch(request(path,method,token,body,headers),env);
const publishApk = (env: Env, body: Uint8Array = APK, headers: Record<string,string> = {}) => run(env,"/api/stremio-lab/publish/builds/7/app.apk","PUT",TOKEN,body,{
  "content-length": String(body.length), "x-content-sha256": "b5a077ba227ec303d1acb9d0bd571db11be467eaceb063914fe70aa8a3924aaf", ...headers });
const publishFeed = async (env: Env, value?: unknown) => run(env,"/api/stremio-lab/publish/latest.json","PUT",TOKEN,JSON.stringify(value === undefined ? await feed() : value));

describe("shared portal isolated Lab delivery", () => {
  it("requires a paired shared device before any storage access", async () => {
    const {bucket,env} = await setup();
    await bucket.seed(KEY,APK);
    for (const path of ["/stremio-lab/latest.json","/stremio-lab/builds/7/app.apk"]) {
      for (const [token,status] of [[undefined,401],["foreign",401],["unpaired",403],[TOKEN,401]] as const) {
        bucket.calls=[];
        expect((await run(env,path,"GET",token)).status).toBe(status);
        expect(bucket.calls).toEqual([]);
      }
    }
    const response = await run(env,"/stremio-lab/builds/7/app.apk","GET","paired");
    expect(response.status).toBe(200);
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(APK);
    expect((await run(env,"/stremio-lab/builds/7/app.apk","HEAD","paired")).body).toBeNull();
    expect((await run(env,"/stremio-lab/latest.json","GET","paired")).status).toBe(404);
  });
  it("rejects unset, admin, device and wrong publish tokens before storage", async () => {
    const {bucket,env}=await setup();
    for (const method of ["GET","HEAD","PUT"]) for (const token of [undefined,"admin-fixture","paired","wrong"]) {
      expect((await run(env,"/api/stremio-lab/publish/latest.json",method,token,method==="PUT"?"{}":undefined)).status).toBe(401);
      expect(bucket.calls).toEqual([]);
    }
    delete env.STREMIO_LAB_PUBLISH_TOKEN;
    expect((await run(env,"/api/stremio-lab/publish/latest.json","GET",TOKEN)).status).toBe(401);
    expect(bucket.calls).toEqual([]);
  });
  it("publishes immutable checksummed APK before an exact increasing feed", async () => {
    const {bucket,env}=await setup();
    expect((await publishApk(env,APK,{"x-content-sha256":hex(await digest(APK))})).status).toBe(201);
    expect((await publishFeed(env)).status).toBe(200);
    expect(JSON.parse(new TextDecoder().decode(bucket.objects.get(LATEST)!.bytes))).toEqual(await feed());
    const response=await run(env,"/api/stremio-lab/publish/latest.json","GET",TOKEN);
    expect(await response.json()).toEqual(await feed());
    expect((await run(env,"/stremio-lab/latest.json","HEAD","paired")).body).toBeNull();
    expect((await publishApk(env,APK,{"x-content-sha256":hex(await digest(APK))})).status).toBe(409);
    expect((await publishFeed(env)).status).toBe(409);
  });
  it("bounds and verifies upload streams without advertising failed bodies", async () => {
    const invalidHeaders: Record<string,string>[] = [{"content-length":"0"},{"content-length":"67108865"},{"content-length":"2"},{"content-length":"30"},{"content-length":"bogus"},{"x-content-sha256":"0".repeat(64)},{"x-content-sha256":"bad"}];
    for (const headers of invalidHeaders) {
      const {bucket,env}=await setup();
      const response=await publishApk(env,APK,headers);
      expect(response.status).toBeGreaterThanOrEqual(400);
      expect(bucket.objects.size).toBe(0);
    }
    const {bucket,env}=await setup();
    expect((await run(env,"/api/stremio-lab/publish/builds/7/app.apk","PUT",TOKEN,APK,{"x-content-sha256":hex(await digest(APK))})).status).toBe(411);
    bucket.failPut=true;
    expect((await publishApk(env,APK,{"x-content-sha256":hex(await digest(APK))})).status).toBe(400);
    expect(bucket.objects.size).toBe(0);
  });
  it("aborts failed request streams and failed absent-only puts without hanging", async () => {
    const {bucket,env}=await setup();
    const body=new ReadableStream({ start(controller) { controller.error(new Error("request aborted")); } });
    const init=Object.assign({method:"PUT",body,headers:{authorization:"Bearer "+TOKEN,"content-length":String(APK.length),"x-content-sha256":hex(await digest(APK))}},{duplex:"half"});
    expect((await worker.fetch(new Request(ORIGIN+"/api/stremio-lab/publish/builds/7/app.apk",init),env)).status).toBe(400);
    bucket.conditionFails=true;
    expect((await publishApk(env)).status).toBe(409);
    expect(bucket.objects.size).toBe(0);
  });
  it("advances a valid existing feed and serves its paired-device bytes", async () => {
    const {bucket,env}=await setup();
    await bucket.seed(KEY,APK);
    await bucket.seed(LATEST,JSON.stringify(await feed(6)));
    expect((await publishFeed(env)).status).toBe(200);
    expect(await (await run(env,"/stremio-lab/latest.json","GET","paired")).json()).toEqual(await feed());
    expect((await run(env,"/api/stremio-lab/publish/builds/7/app.apk","HEAD",TOKEN)).body).toBeNull();
    expect(bucket.calls.every(call=>call.split(":").slice(1).join(":").startsWith("stremio-lab/"))).toBe(true);
  });
  it("rejects feed identity, URL, hash, size, code, shape and missing APK", async () => {
    const {bucket,env}=await setup();
    expect((await publishFeed(env)).status).toBe(409);
    await bucket.seed(KEY,APK);
    for (const [key,value] of [["applicationId","app.familytv.rust"],["channel","main"],["versionName","0.1.7"],["versionCode",true],["versionCode",0],["versionCode",2100000001],["url",ORIGIN+"/tvrust"],["url","https://other.test/stremio-lab/builds/7/app.apk"],["sha256","0".repeat(64)],["size",1],["size",67108865],["extra","value"]]) {
      expect((await publishFeed(env,{...await feed(),[key as string]:value})).status).toBeGreaterThanOrEqual(400);
      expect(bucket.objects.has(LATEST)).toBe(false);
    }
    for (const value of [[],null,{},"text"]) expect((await publishFeed(env,value)).status).toBe(400);
    expect((await run(env,"/api/stremio-lab/publish/latest.json","PUT",TOKEN,"x".repeat(65537))).status).toBe(413);
    await bucket.seed(KEY,APK,false);
    expect((await publishFeed(env)).status).toBe(409);
  });
  it("uses atomic feed conditions for absent and existing races and rejects invalid current feeds", async () => {
    for (const previous of [null,await feed(6)]) {
      const {bucket,env}=await setup();
      await bucket.seed(KEY,APK);
      if (previous) await bucket.seed(LATEST,JSON.stringify(previous));
      const newer=JSON.stringify(await feed(8));
      bucket.beforeFeedPut=()=>bucket.objects.set(LATEST,{bytes:new TextEncoder().encode(newer),etag:"racer"});
      expect((await publishFeed(env)).status).toBe(409);
      expect(new TextDecoder().decode(bucket.objects.get(LATEST)!.bytes)).toBe(newer);
    }
    for (const current of [await feed(7),await feed(8),{}, {...await feed(6),channel:"old"}]) {
      const {bucket,env}=await setup(); await bucket.seed(KEY,APK); await bucket.seed(LATEST,JSON.stringify(current));
      expect((await publishFeed(env)).status).toBe(409);
    }
  });
  it("rejects namespace escapes, aliases, queries and noncanonical hosts without storage", async () => {
    const {bucket,env}=await setup();
    for (const path of ["/api/stremio-lab/publish/family-tv.apk","/api/stremio-lab/publish/../latest.json","/api/stremio-lab/publish/builds/01/app.apk","/api/stremio-lab/publish/builds/7/%61pp.apk","/api/stremio-lab/publish/latest.json?key=latest.json","/stremio-lab/latest.json?x=1","/stremio-lab/builds/0/app.apk","/stremio-lab/","/api/stremio-lab/publish/latest.json/"]) {
      expect((await run(env,path,"GET",TOKEN)).status).toBeGreaterThanOrEqual(400);
    }
    for (const origin of ["https://family-tv-portal.test.workers.dev","http://familytv.arshadkazi.ca","https://familytv.arshadkazi.ca."]) {
      expect((await worker.fetch(new Request(origin+"/api/stremio-lab/publish/latest.json",{headers:{authorization:"Bearer "+TOKEN}}),env)).status).toBe(404);
    }
    // Preserve a raw incoming URL to verify encoded/dot traversal rejection before
    // WHATWG URL normalization; Requests constructed from text normalize dot segments.
    for (const path of ["/api/stremio-lab/publish/builds/%2e%2e/latest.json","/api/stremio-lab/publish/builds/../latest.json","/stremio-lab/builds/%2e%2e/latest.json"]) {
      const req=request("/api/stremio-lab/publish/latest.json","GET",TOKEN);
      Object.defineProperty(req,"url",{value:ORIGIN+path});
      expect((await worker.fetch(req,env)).status).toBe(404);
    }
    expect(bucket.calls).toEqual([]);
    expect((await run(env,"/api/admin/devices","GET",TOKEN)).status).toBe(401);
  });
  it("leaves legacy download keys, feed and latestApp behavior intact", async () => {
    const {bucket,env}=await setup();
    for (const key of ["family-tv.apk","family-tv-rust.apk","family-tv-rust-latest.json","latest.json"]) await bucket.seed(key,"legacy");
    const original=new Map(bucket.objects);
    await bucket.seed(KEY,APK);
    expect((await publishFeed(env)).status).toBe(200);
    for (const [key,value] of original) expect(bucket.objects.get(key)).toBe(value);
    for (const path of ["/tv","/app.apk","/tvrust","/tvrust/","/tvrust/latest.json"]) expect((await run(env,path)).status).toBe(200);
  });
});

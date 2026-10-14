import { describe, expect, it } from "vitest";
import { handle, type Env } from "../src/index";

const apk = new Uint8Array([0x50, 0x4b, 3, 4]);
const bucket = (has: boolean) =>
  ({ get: async (key: string) => (has && key === "family-tv.apk" ? { size: apk.length, body: new Blob([apk]).stream() } : null) }) as unknown as R2Bucket;
const env = (apps?: R2Bucket) => ({ APPS: apps }) as unknown as Env;

describe("app download", () => {
  it("serves the uploaded APK at /tv", async () => {
    const res = await handle(new Request("https://portal.test/tv"), env(bucket(true)));
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toBe("application/vnd.android.package-archive");
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(apk);
  });

  it("404s before an APK is uploaded or without the bucket", async () => {
    expect((await handle(new Request("https://portal.test/app.apk"), env(bucket(false)))).status).toBe(404);
    expect((await handle(new Request("https://portal.test/tv"), env())).status).toBe(404);
  });
});

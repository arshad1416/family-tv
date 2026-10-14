export interface ReachResult {
  url: string;
  ok: boolean;
  status?: number;
  ms?: number;
  error?: string;
}

const PRIVATE_HOST = /^(localhost|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?|\[?f[cd][0-9a-f]{2}:)/i;

/**
 * Probe a provider URL from Cloudflare's edge. If the edge reaches it but the TV can't, the
 * problem is on the TV's network (filter or ISP), not the provider.
 */
export async function probe(url: string, fetcher: typeof fetch = fetch, timeoutMs = 6000): Promise<ReachResult> {
  let u: URL;
  try {
    u = new URL(url);
  } catch {
    return { url, ok: false, error: "bad url" };
  }
  if (!/^https?:$/.test(u.protocol) || PRIVATE_HOST.test(u.hostname)) {
    return { url, ok: false, error: "not probeable" };
  }
  // Probe the server root: no credentials leave the portal, and any HTTP answer proves it is up.
  const target = `${u.protocol}//${u.host}/`;
  const started = Date.now();
  try {
    const res = await fetcher(target, {
      method: "GET",
      redirect: "manual",
      signal: AbortSignal.timeout(timeoutMs),
      headers: { "User-Agent": "FamilyTV-Portal/1 reachability" },
    });
    await res.body?.cancel();
    return { url: target, ok: res.status < 500, status: res.status, ms: Date.now() - started };
  } catch (e) {
    return { url: target, ok: false, ms: Date.now() - started, error: (e as Error).name || "error" };
  }
}

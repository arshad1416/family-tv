// Turns a TV's last check (the report its Diagnoser uploads) into concrete suggestions for the
// admin page: how the TV is connected, which of the profile's servers suits it best, and what to
// do about each finding. When the check points at an ISP block, the advice names the cause and
// what was ruled out, but never steers the TV to another server to get around it.

import type { ProfileData } from "./model";

export interface Suggestion {
  severity: number; // 0 info, 1 worth doing, 2 likely the fix, 3 must fix
  title: string;
  detail: string;
  /** A one-click fix on the admin page. */
  action?: { type: "primary-server"; sourceId: string; url: string; label: string };
}

interface HostFacts {
  url?: string;
  host?: string;
  systemDns?: string[] | null;
  publicDns?: string[] | null;
  tcpOk?: boolean | null;
  portalReachable?: boolean | null;
  httpStatus?: number | null;
  connectMs?: number | null;
}

interface Report {
  verdict?: string;
  findings?: { verdict?: string; host?: string | null; detail?: string }[];
  hosts?: HostFacts[];
  stream?: { httpStatus?: number | null; bytes?: number; millis?: number; error?: string | null } | null;
  device?: {
    network?: string;
    downMbps?: number | null;
    wifiRssi?: number | null;
    wifiMbps?: number | null;
    wifiGhz?: number | null;
    dnsServers?: string[] | null;
    privateDns?: boolean | null;
  };
}

/** An HD channel needs about 5 Mbit/s steady; below this the TV will buffer. */
const HD_KBPS = 5000;
/** Below about -70 dBm Wi-Fi drops packets and slows down. */
const WEAK_RSSI = -70;

const hostKey = (url: string): string | null => {
  try {
    const u = new URL(url);
    return `${u.hostname}:${u.port || (u.protocol === "https:" ? "443" : "80")}`;
  } catch {
    return null;
  }
};

/** Usable from this TV: it connects, and the address isn't a filter's sinkhole. */
const usable = (f: HostFacts | undefined) =>
  !!f && f.tcpOk === true && !(f.systemDns ?? []).some((a) => a === "0.0.0.0" || a.startsWith("127.") || a === "::" || a === "::1");

export function suggestions(report: Report, profile: ProfileData | null): Suggestion[] {
  const out: Suggestion[] = [];
  const d = report.device ?? {};
  const findings = report.findings ?? [];
  const has = (v: string) => findings.some((f) => f.verdict === v);
  const facts = new Map((report.hosts ?? []).filter((h) => h.host).map((h) => [h.host!, h]));
  const blockedHosts = new Set(findings.filter((f) => f.verdict === "NETWORK_BLOCKED" || f.verdict === "DNS_FILTERED").map((f) => f.host).filter(Boolean));

  if (report.verdict === "NO_INTERNET") {
    out.push({ severity: 3, title: "The TV is offline", detail: "It couldn't reach the internet at all. Check its Wi-Fi or cable and that the router is online." });
    return out;
  }

  // How the TV is connected.
  const kbps = report.stream && report.stream.millis ? Math.round(((report.stream.bytes ?? 0) * 8) / report.stream.millis) : null;
  const slow = kbps != null && kbps > 0 && kbps < HD_KBPS;
  if (d.network === "wifi") {
    const weak = d.wifiRssi != null && d.wifiRssi < WEAK_RSSI;
    if (weak || slow) {
      const signal = d.wifiRssi != null ? ` Signal ${d.wifiRssi} dBm${d.wifiGhz ? ` on ${d.wifiGhz} GHz` : ""}.` : "";
      out.push({
        severity: slow ? 2 : 1,
        title: weak ? "Weak Wi-Fi" : "Wi-Fi too slow for HD",
        detail: `${signal} Plug the TV into the router with an Ethernet cable (or a powerline adapter), or move the router or a mesh point closer.` +
          (d.wifiGhz && d.wifiGhz < 3 ? " It's on 2.4 GHz: if the router has a 5 GHz network, join that instead." : ""),
      });
    } else if (d.wifiGhz && d.wifiGhz < 3) {
      out.push({ severity: 0, title: "On 2.4 GHz Wi-Fi", detail: "Fine for now. If live TV ever stutters, join the router's 5 GHz network or use Ethernet." });
    }
  }
  if (slow && d.network !== "wifi") {
    out.push({
      severity: 2,
      title: "Connection too slow for HD",
      detail: `The stream came in at ${(kbps! / 1000).toFixed(1)} Mbit/s; HD needs about 5. Check the home internet speed and what else is downloading.`,
    });
  }

  // DNS filter on the home network: allowlisting the host is the fix.
  for (const f of findings.filter((x) => x.verdict === "DNS_FILTERED")) {
    out.push({
      severity: 3,
      title: "A DNS filter at home blocks the server",
      detail: `Allowlist ${f.host ?? "the server"} on the filter the TV uses` +
        (d.dnsServers?.length ? ` (DNS ${d.dnsServers.join(", ")})` : "") +
        (d.privateDns ? ". The TV also has Private DNS on (Android settings, Network), which can apply its own filtering." : "."),
    });
  }

  // ISP block: name the cause and what was ruled out; no way around it is offered.
  for (const f of findings.filter((x) => x.verdict === "NETWORK_BLOCKED")) {
    const h = f.host ? facts.get(f.host) : undefined;
    const ruledOut = ["the TV's internet works", h?.publicDns?.length ? "the address resolves normally" : null, h?.portalReachable ? "the server answers from outside this network" : null]
      .filter(Boolean)
      .join(", ");
    out.push({
      severity: 3,
      title: "The ISP blocks this server",
      detail: `Checked: ${ruledOut}. Only this network's path to ${f.host ?? "the server"} fails, which points at the internet provider. ` +
        "Nothing on the TV or router will change that; it's for the IPTV provider to resolve with their service, or ask the ISP why it is blocked.",
    });
  }

  // Server choice per Xtream line: suggest a backup only when the main one is down for everyone or clearly slower.
  for (const s of profile?.sources ?? []) {
    const urls = s.type === "xtream" ? s.serverUrls ?? [] : [];
    if (urls.length < 2) continue;
    const ranked = urls
      .map((url, i) => ({ url, i, key: hostKey(url), f: facts.get(hostKey(url) ?? "") }))
      .filter((x) => x.key && !blockedHosts.has(x.key));
    const main = ranked.find((x) => x.i === 0);
    const working = ranked.filter((x) => usable(x.f)).sort((a, b) => (a.f!.connectMs ?? 1e9) - (b.f!.connectMs ?? 1e9));
    const best = working[0];
    if (!best || best.i === 0 || blockedHosts.has(hostKey(urls[0]) ?? "")) continue;
    const mainDownEverywhere = main?.f && !usable(main.f) && main.f.portalReachable === false;
    const mainSlow = main?.f && usable(main.f) && main.f.connectMs != null && best.f!.connectMs != null &&
      main.f.connectMs > 300 && main.f.connectMs > 2 * best.f!.connectMs;
    if (!mainDownEverywhere && !mainSlow) continue;
    const host = new URL(best.url).host;
    out.push({
      severity: mainDownEverywhere ? 3 : 1,
      title: mainDownEverywhere ? `${s.name}: main server is down` : `${s.name}: a backup server answers faster`,
      detail: mainDownEverywhere
        ? `Neither this TV nor the portal can reach the main server. ${host} works from here.`
        : `${host} connects in ${best.f!.connectMs} ms against ${main!.f!.connectMs} ms for the main server.`,
      action: { type: "primary-server", sourceId: s.id, url: best.url, label: `Use ${host} first` },
    });
  }

  if (has("EXPIRED")) out.push({ severity: 3, title: "Line expired or switched off", detail: "Renew or re-enable it on the Lines page." });
  if (has("BAD_LOGIN")) out.push({ severity: 3, title: "Login refused", detail: "Check the line's username and password in this profile against the panel." });
  if (has("MAX_CONNECTIONS")) {
    const f = findings.find((x) => x.verdict === "MAX_CONNECTIONS");
    out.push({ severity: 2, title: "Too many screens on this line", detail: `${f?.detail ?? "The line is at its limit."} Stop playback on another TV, or give this TV its own line.` });
  }

  return out.sort((a, b) => b.severity - a.severity);
}

/** Moves [url] to the front of a source's server list, so the TVs try it first. */
export function preferServer(profile: ProfileData, sourceId: string, url: string): ProfileData {
  const s = profile.sources.find((x) => x.id === sourceId);
  if (!s || s.type !== "xtream" || !s.serverUrls?.includes(url)) throw new Error("that server isn't in this line's list");
  s.serverUrls = [url, ...s.serverUrls.filter((u) => u !== url)];
  return profile;
}

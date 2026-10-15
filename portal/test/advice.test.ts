import { describe, expect, it } from "vitest";
import { preferServer, suggestions } from "../src/advice";
import type { ProfileData } from "../src/model";

const profile = (): ProfileData => ({
  sources: [{ id: "s1", type: "xtream", name: "Main line", serverUrls: ["http://main.example", "http://backup.example:8080"], username: "u", password: "p" }],
});
const host = (h: string, over: object = {}) => ({ host: h, systemDns: ["1.2.3.4"], publicDns: ["1.2.3.4"], tcpOk: true, portalReachable: true, connectMs: 40, ...over });

describe("suggestions", () => {
  it("suggests Ethernet for weak, slow Wi-Fi", () => {
    const s = suggestions({ verdict: "SLOW_CONNECTION", stream: { bytes: 1_000_000, millis: 4000 }, device: { network: "wifi", wifiRssi: -78, wifiGhz: 2.4 } }, null);
    expect(s[0].title).toBe("Weak Wi-Fi");
    expect(s[0].detail).toMatch(/Ethernet/);
    expect(s[0].detail).toMatch(/5 GHz/);
  });

  it("offers the backup when the main server is down for everyone", () => {
    const s = suggestions({
      verdict: "SERVER_DOWN",
      hosts: [host("main.example:80", { tcpOk: false, portalReachable: false }), host("backup.example:8080")],
    }, profile());
    expect(s[0].action).toEqual({ type: "primary-server", sourceId: "s1", url: "http://backup.example:8080", label: "Use backup.example:8080 first" });
  });

  it("explains an ISP block and never suggests another server around it", () => {
    const s = suggestions({
      verdict: "NETWORK_BLOCKED",
      findings: [{ verdict: "NETWORK_BLOCKED", host: "main.example:80", detail: "" }],
      hosts: [host("main.example:80", { tcpOk: false, portalReachable: true }), host("backup.example:8080")],
    }, profile());
    expect(s.map((x) => x.title)).toEqual(["The ISP blocks this server"]);
    expect(s[0].detail).toMatch(/answers from outside this network/);
    expect(s.some((x) => x.action)).toBe(false);
  });

  it("names the host to allowlist for a home DNS filter", () => {
    const s = suggestions({ verdict: "DNS_FILTERED", findings: [{ verdict: "DNS_FILTERED", host: "main.example:80" }], device: { dnsServers: ["192.168.8.1"] } }, profile());
    expect(s[0].detail).toBe("Allowlist main.example:80 on the filter the TV uses (DNS 192.168.8.1).");
  });

  it("prefers a much faster backup, gently", () => {
    const s = suggestions({ verdict: "OK", hosts: [host("main.example:80", { connectMs: 900 }), host("backup.example:8080", { connectMs: 60 })] }, profile());
    expect(s[0]).toMatchObject({ severity: 1, action: { url: "http://backup.example:8080" } });
  });
});

describe("preferServer", () => {
  it("moves the chosen server to the front", () =>
    expect(preferServer(profile(), "s1", "http://backup.example:8080").sources[0].serverUrls).toEqual(["http://backup.example:8080", "http://main.example"]));
  it("refuses a server that isn't on the line", () => expect(() => preferServer(profile(), "s1", "http://elsewhere.example")).toThrow());
});

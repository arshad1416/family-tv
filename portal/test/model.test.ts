import { describe, expect, it } from "vitest";
import { DEFAULT_EXTRA_GUIDES, forDevice, MASK, maskSecrets, mergeSecrets, profileHosts, validateProfile, type ProfileData } from "../src/model";
import { openJson, pairCode, sealJson } from "../src/crypto";
import { probe } from "../src/reachability";

const profile: ProfileData = {
  sources: [
    { id: "a", type: "xtream", name: "Main", serverUrls: ["http://line.example.com:8080", "http://backup.example.net"], username: "u", password: "secret" },
    { id: "b", type: "m3u", name: "Extra", m3uUrl: "https://lists.example.org/get.m3u", epgUrl: "https://epg.example.org/guide.xml.gz" },
  ],
  parentalPin: "1234",
};

describe("profile secrets", () => {
  it("masks passwords and PIN for the admin page", () => {
    const m = maskSecrets(profile);
    expect(m.sources[0].password).toBe(MASK);
    expect(m.parentalPin).toBe(MASK);
    expect(m.sources[1].password).toBeUndefined();
  });

  it("keeps stored secrets when the page sends the mask back", () => {
    const merged = mergeSecrets(maskSecrets(profile), profile);
    expect(merged.sources[0].password).toBe("secret");
    expect(merged.parentalPin).toBe("1234");
  });

  it("takes a new password when one is typed", () => {
    const edited = maskSecrets(profile);
    edited.sources[0].password = "new";
    expect(mergeSecrets(edited, profile).sources[0].password).toBe("new");
  });
});

describe("validateProfile", () => {
  it("accepts a valid profile", () => expect(validateProfile(structuredClone(profile)).sources).toHaveLength(2));
  it("rejects an Xtream source without a server", () =>
    expect(() => validateProfile({ sources: [{ id: "x", name: "X", type: "xtream", username: "u" }] })).toThrow(/server URL/));
  it("rejects non-http URLs", () =>
    expect(() => validateProfile({ sources: [{ id: "x", name: "X", type: "m3u", m3uUrl: "file:///etc/passwd" }] })).toThrow(/http/));
});

describe("profileHosts", () => {
  it("collects every host a TV may use", () => {
    expect([...profileHosts(profile)].sort()).toEqual(["backup.example.net", "epg.example.org", "epgshare01.online", "line.example.com:8080", "lists.example.org"]);
    expect([...profileHosts({ ...profile, extraGuides: [] })]).not.toContain("epgshare01.online");
  });
});

describe("extra guides", () => {
  it("gives TVs epgshare01 until the admin sets the list, and nothing once it is emptied", () => {
    expect(forDevice(profile).extraGuides).toEqual(DEFAULT_EXTRA_GUIDES);
    expect(forDevice({ ...profile, extraGuides: [] }).extraGuides).toEqual([]);
    expect(forDevice({ ...profile, extraGuides: ["https://g.example/a.xml"] }).extraGuides).toEqual(["https://g.example/a.xml"]);
  });

  it("checks guide URLs and tidies pins", () => {
    const ok = validateProfile({ ...profile, extraGuides: [" https://g.example/a.xml ", "https://g.example/a.xml"], guidePins: { " CA| CTV HD ": " CTV.Toronto.HD.ca2 ", empty: "" } });
    expect(ok.extraGuides).toEqual(["https://g.example/a.xml"]);
    expect(ok.guidePins).toEqual({ "CA| CTV HD": "CTV.Toronto.HD.ca2" });
    expect(() => validateProfile({ ...profile, extraGuides: ["ftp://g.example/a.xml"] })).toThrow();
    expect(() => validateProfile({ ...profile, extraGuides: ["https://a.x/1", "https://a.x/2", "https://a.x/3", "https://a.x/4", "https://a.x/5"] })).toThrow();
    expect(() => validateProfile({ ...profile, guidePins: ["x"] })).toThrow();
  });
});

describe("crypto", () => {
  const key = btoa(String.fromCharCode(...new Uint8Array(32).fill(7)));
  it("round-trips sealed JSON", async () => {
    const sealed = await sealJson(key, profile);
    expect(sealed.startsWith("v1.")).toBe(true);
    expect(sealed).not.toContain("secret");
    expect(await openJson(key, sealed)).toEqual(profile);
  });
  it("makes readable pairing codes", () => {
    for (let i = 0; i < 50; i++) expect(pairCode()).toMatch(/^[A-HJ-NP-Z2-9]{6}$/);
  });
});

describe("probe", () => {
  it("refuses private addresses", async () => {
    expect((await probe("http://192.168.0.10:8080/x")).error).toBe("not probeable");
    expect((await probe("http://localhost/")).error).toBe("not probeable");
  });
  it("probes the server root without credentials", async () => {
    let seen = "";
    const fake = (async (u: string) => {
      seen = u;
      return new Response(null, { status: 401 });
    }) as unknown as typeof fetch;
    const r = await probe("http://line.example.com:8080/player_api.php?username=u&password=p", fake);
    expect(seen).toBe("http://line.example.com:8080/");
    expect(r.ok).toBe(true);
  });
  it("reports a timeout as unreachable", async () => {
    const fake = (async () => {
      throw Object.assign(new Error("t"), { name: "TimeoutError" });
    }) as unknown as typeof fetch;
    const r = await probe("http://line.example.com/", fake);
    expect(r).toMatchObject({ ok: false, error: "TimeoutError" });
  });
});

describe("package sorter link", () => {
  const withSorter = (sorterUrl: string) => ({ sources: [{ ...profile.sources[0], sorterUrl }] });

  it("keeps an http(s) sorter link on the source, so TVs get it", () => {
    const p = validateProfile(withSorter("https://sorter.example.com/qr_edit?token=abc"));
    expect(forDevice(p).sources[0].sorterUrl).toBe("https://sorter.example.com/qr_edit?token=abc");
  });

  it("refuses a sorter link that isn't http(s)", () => {
    expect(() => validateProfile(withSorter("javascript:alert(1)"))).toThrow();
  });
});

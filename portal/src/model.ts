export interface Source {
  id: string;
  type: "xtream" | "m3u";
  name: string;
  /** Xtream: one or more server base URLs, tried in order (providers often give backups). */
  serverUrls?: string[];
  username?: string;
  password?: string;
  /** M3U: playlist URL; epgUrl optional for both types. */
  m3uUrl?: string;
  epgUrl?: string;
  /** The provider's page where the subscriber picks and orders this line's channels; the app shows it as a QR. */
  sorterUrl?: string;
  /**
   * Only on the way in from the admin page: the username of the portal's line this source was
   * copied from, so its password (never sent to the page) is taken from there on save.
   */
  copyOf?: string;
}

export const CATEGORY_KINDS = ["live", "movie", "series"] as const;
export type CategoryKind = (typeof CATEGORY_KINDS)[number];

export interface ProfileData {
  sources: Source[];
  parentalPin?: string;
  hiddenCategories?: string[];
  /** Category names per kind in the order the TVs list them; ones not named keep the provider's order after these. */
  categoryOrder?: Partial<Record<CategoryKind, string[]>>;
  /**
   * XMLTV guides, by priority, for channels the provider's own guide leaves thin. Unset means
   * DEFAULT_EXTRA_GUIDES; an empty list turns them off.
   */
  extraGuides?: string[];
  /** Provider channel name → an extra guide's channel id, for channels the TV can't link by name. */
  guidePins?: Record<string, string>;
  /** Startup video on the TVs: "the-nook" on the Airbnb guest profile; unset plays Family TV's. */
  splash?: Splash;
  /** TVs open the app by themselves when they start or wake (the Airbnb guest TV); unset leaves that to the TV. */
  openOnStart?: boolean;
  /** TVs send anonymous feature use (screens and buttons, never what's watched or searched); unset sends nothing. */
  usageStats?: boolean;
}

export const SPLASHES = ["the-nook"] as const;
export type Splash = (typeof SPLASHES)[number];

/** epgshare01's Canada, US and India guides (free; Gracenote listings, about 6.5 MB each). */
export const DEFAULT_EXTRA_GUIDES = [
  "https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz",
  "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
  "https://epgshare01.online/epgshare01/epg_ripper_IN1.xml.gz",
];
const MAX_EXTRA_GUIDES = 4;
const MAX_PINS = 500;

/** The profile as TVs get it: extra guides resolved to the defaults when the admin never set them. */
export function forDevice(p: ProfileData): ProfileData {
  return { ...p, extraGuides: p.extraGuides ?? DEFAULT_EXTRA_GUIDES };
}

const MAX_CATEGORIES = 1000;

/** Category names as a TV reports or the admin page saves them: strings, trimmed, de-duplicated, capped. */
export function cleanNames(v: unknown): string[] {
  if (!Array.isArray(v)) return [];
  const out: string[] = [];
  const seen = new Set<string>();
  for (const x of v) {
    if (typeof x !== "string") continue;
    const n = x.trim().slice(0, 120);
    if (!n || seen.has(n.toLowerCase())) continue;
    seen.add(n.toLowerCase());
    out.push(n);
    if (out.length >= MAX_CATEGORIES) break;
  }
  return out;
}

export const MASK = "••••••";

/** Hosts a device on this profile legitimately talks to (used to scope the reachability check). */
export function profileHosts(p: ProfileData): Set<string> {
  const hosts = new Set<string>();
  for (const s of p.sources) {
    for (const u of [...(s.serverUrls ?? []), s.m3uUrl, s.epgUrl]) addHost(hosts, u);
  }
  for (const u of p.extraGuides ?? DEFAULT_EXTRA_GUIDES) addHost(hosts, u);
  return hosts;
}

function addHost(hosts: Set<string>, u: string | undefined) {
  if (!u) return;
  try {
    hosts.add(new URL(u).host.toLowerCase());
  } catch {
    /* ignore malformed */
  }
}

/** Passwords never leave the portal towards the admin page; the page sends MASK back to mean "unchanged". */
export function maskSecrets(p: ProfileData): ProfileData {
  return {
    ...p,
    parentalPin: p.parentalPin ? MASK : undefined,
    sources: p.sources.map((s) => ({ ...s, password: s.password ? MASK : undefined })),
  };
}

/**
 * Puts stored secrets back where the page sent MASK. A source copied from one of the portal's lines
 * ([Source.copyOf]) gets that line's password from [passwordOf]; copyOf itself is never stored.
 */
export function mergeSecrets(incoming: ProfileData, existing: ProfileData | null, passwordOf?: (username: string) => string | undefined): ProfileData {
  const old = new Map((existing?.sources ?? []).map((s) => [s.id, s]));
  return {
    ...incoming,
    parentalPin: incoming.parentalPin === MASK ? existing?.parentalPin : incoming.parentalPin,
    sources: incoming.sources.map(({ copyOf, ...s }) => ({
      ...s,
      password: s.password === MASK ? (old.get(s.id)?.password ?? (copyOf ? passwordOf?.(copyOf) : undefined)) : s.password,
    })),
  };
}

export function validateProfile(p: unknown): ProfileData {
  if (!p || typeof p !== "object") throw new Error("profile must be an object");
  const d = p as ProfileData;
  if (!Array.isArray(d.sources)) throw new Error("sources must be a list");
  for (const s of d.sources) {
    if (!s.id || !s.name) throw new Error("each source needs an id and a name");
    if (s.type === "xtream") {
      if (!s.serverUrls?.length || !s.username) throw new Error(`${s.name}: Xtream needs a server URL and username`);
      s.serverUrls.forEach(assertHttpUrl);
    } else if (s.type === "m3u") {
      if (!s.m3uUrl) throw new Error(`${s.name}: M3U needs a playlist URL`);
      assertHttpUrl(s.m3uUrl);
    } else {
      throw new Error(`${s.name}: type must be xtream or m3u`);
    }
    if (s.epgUrl) assertHttpUrl(s.epgUrl);
    if (s.sorterUrl) assertHttpUrl(s.sorterUrl);
  }
  if (d.hiddenCategories !== undefined) d.hiddenCategories = cleanNames(d.hiddenCategories);
  if (d.categoryOrder !== undefined) {
    if (!d.categoryOrder || typeof d.categoryOrder !== "object") throw new Error("categoryOrder must be an object");
    const order: Partial<Record<CategoryKind, string[]>> = {};
    for (const k of CATEGORY_KINDS) {
      const names = cleanNames(d.categoryOrder[k]);
      if (names.length) order[k] = names;
    }
    d.categoryOrder = order;
  }
  if (d.extraGuides !== undefined) {
    if (!Array.isArray(d.extraGuides)) throw new Error("extraGuides must be a list");
    const urls = [...new Set(d.extraGuides.map((u) => String(u).trim()).filter(Boolean))];
    if (urls.length > MAX_EXTRA_GUIDES) throw new Error(`at most ${MAX_EXTRA_GUIDES} extra guides`);
    urls.forEach(assertHttpUrl);
    d.extraGuides = urls;
  }
  if (d.guidePins !== undefined) {
    if (!d.guidePins || typeof d.guidePins !== "object" || Array.isArray(d.guidePins)) throw new Error("guidePins must be an object");
    const pins: Record<string, string> = {};
    for (const [name, id] of Object.entries(d.guidePins)) {
      const n = name.trim().slice(0, 120);
      const g = String(id ?? "").trim().slice(0, 200);
      if (n && g) pins[n] = g;
    }
    if (Object.keys(pins).length > MAX_PINS) throw new Error(`at most ${MAX_PINS} guide pins`);
    d.guidePins = pins;
  }
  if (d.splash === undefined || d.splash === null || (d.splash as string) === "") delete d.splash;
  else if (!SPLASHES.includes(d.splash)) throw new Error(`splash must be one of ${SPLASHES.join(", ")} or unset`);
  if (d.openOnStart === true) d.openOnStart = true;
  else if (d.openOnStart === undefined || d.openOnStart === null || d.openOnStart === false) delete d.openOnStart;
  else throw new Error("openOnStart must be true, false or unset");
  if (d.usageStats === true) d.usageStats = true;
  else if (d.usageStats === undefined || d.usageStats === null || d.usageStats === false) delete d.usageStats;
  else throw new Error("usageStats must be true, false or unset");
  // Viewers are added on each TV now; a list from an older save is dropped.
  delete (d as { viewers?: unknown }).viewers;
  return d;
}

export function assertHttpUrl(u: string) {
  const url = new URL(u);
  if (url.protocol !== "http:" && url.protocol !== "https:") throw new Error(`not an http(s) URL: ${u}`);
}

/** What the TV computed, mapped to plain words and a next step for the admin page. */
export const VERDICTS: Record<string, { label: string; advice: string; severity: number }> = {
  OK: { label: "Working", advice: "Nothing to fix.", severity: 0 },
  SLOW_CONNECTION: {
    label: "Slow connection",
    advice: "The stream works but the TV's internet is too slow for smooth playback. Move the TV closer to Wi-Fi, use Ethernet, or pick a lower-quality channel.",
    severity: 1,
  },
  DNS_FILTERED: {
    label: "Blocked by a DNS filter on their network",
    advice: "The home network's DNS (AdGuard, Pi-hole, NextDNS, router parental controls) refuses this host while public DNS resolves it. Allowlist the host shown in the report on that filter.",
    severity: 3,
  },
  NETWORK_BLOCKED: {
    label: "Blocked on their network or by their ISP",
    advice: "The server answers from the internet but not from this TV's network. If no filter on the home network explains it, the ISP is blocking it; contact the provider.",
    severity: 3,
  },
  DNS_NOT_FOUND: {
    label: "Server address no longer exists",
    advice: "Public DNS can't find this host either. The provider has probably changed its server URL; get the new one and update the profile.",
    severity: 3,
  },
  SERVER_DOWN: {
    label: "Provider server down",
    advice: "Neither the TV nor the portal can reach the server. Wait, or switch the profile to a backup server URL.",
    severity: 3,
  },
  SERVER_ERROR: { label: "Provider server error", advice: "The server is reachable but returning errors. Usually temporary; contact the provider if it persists.", severity: 2 },
  BAD_LOGIN: { label: "Wrong username or password", advice: "The provider rejected the login. Check the line in your reseller panel and update the profile.", severity: 3 },
  EXPIRED: { label: "Line expired or disabled", advice: "Renew or re-enable this line in your reseller panel.", severity: 3 },
  MAX_CONNECTIONS: {
    label: "Too many devices on this line",
    advice: "The line is already at its connection limit. Stop playback elsewhere or raise the limit in your reseller panel.",
    severity: 2,
  },
  NO_INTERNET: { label: "TV has no internet", advice: "The TV can't reach anything. Check its Wi-Fi/Ethernet and the router.", severity: 3 },
};

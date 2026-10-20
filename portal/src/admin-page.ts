// Single-file admin UI, served by the Worker. No build step, no external requests.
import { DEFAULT_EXTRA_GUIDES, MASK } from "./model";

export const ADMIN_PAGE = /* html */ `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Family TV</title>
<style>
:root{--bg:#f6f7f9;--card:#fff;--ink:#14171c;--muted:#5d6673;--line:#e3e6eb;--accent:#2563eb;--ok:#15803d;--warn:#b45309;--bad:#b91c1c}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#171a20;--ink:#e8eaee;--muted:#9aa3af;--line:#2a2f38;--accent:#60a5fa;--ok:#4ade80;--warn:#fbbf24;--bad:#f87171}}
*{box-sizing:border-box}body{margin:0;font:15px/1.45 system-ui,-apple-system,Segoe UI,sans-serif;background:var(--bg);color:var(--ink)}
header{display:flex;gap:16px;align-items:center;padding:14px 16px;border-bottom:1px solid var(--line);background:var(--card);position:sticky;top:0}
header h1{font-size:17px;margin:0;margin-right:auto}
nav button{background:none;border:0;color:var(--muted);font:inherit;padding:6px 10px;border-radius:6px;cursor:pointer}
nav button.on{color:var(--ink);background:var(--bg)}
main{max-width:1000px;margin:0 auto;padding:16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px;margin-bottom:12px}
.row{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
.dot{flex:none;width:10px;height:10px;margin-right:2px;border-radius:50%;display:inline-block;background:var(--muted)}
.dot.on{background:var(--ok)}.dot.warn{background:var(--warn)}.dot.bad{background:var(--bad)}
.muted{color:var(--muted)}.small{font-size:13px}
button.b{font:inherit;padding:7px 12px;border-radius:7px;border:1px solid var(--line);background:var(--card);color:var(--ink);cursor:pointer}
button.p{background:var(--accent);border-color:var(--accent);color:#fff}
input,select,textarea{font:inherit;padding:7px 9px;border:1px solid var(--line);border-radius:7px;background:var(--bg);color:var(--ink);width:100%}
label{display:block;font-size:13px;color:var(--muted);margin:8px 0 3px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:10px}
.verdict{border-left:4px solid var(--muted);padding:8px 12px;margin:8px 0;background:var(--bg);border-radius:6px}
.verdict.s0{border-color:var(--ok)}.verdict.s1,.verdict.s2{border-color:var(--warn)}.verdict.s3{border-color:var(--bad)}
pre{white-space:pre-wrap;word-break:break-word;font-size:12px;background:var(--bg);padding:8px;border-radius:6px;max-height:300px;overflow:auto}
.err{color:var(--bad)}
table{width:100%;border-collapse:collapse}td,th{text-align:left;padding:7px 6px;border-top:1px solid var(--line);vertical-align:middle}th{font-size:13px;color:var(--muted);font-weight:500;border-top:0}
.ok{color:var(--ok)}.warn{color:var(--warn)}
.grip{cursor:grab;touch-action:none;user-select:none;padding:2px 8px;color:var(--muted);font-size:18px;line-height:1}
.cat.dragging{opacity:.85;background:var(--bg);position:relative;z-index:1}
@media (max-width:700px){.wide{display:none}}
</style>
</head>
<body>
<header><h1>Family TV</h1><nav><button data-tab="devices" class="on">Devices</button><button data-tab="profiles">Profiles</button><button data-tab="lines">Lines</button><button data-tab="addons">Add-ons</button></nav></header>
<main id="app"></main>
<script>
const $ = (s, el = document) => el.querySelector(s);
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
let token = null;
try { token = localStorage.getItem("ftv_admin"); } catch {}
let tab = "devices", verdicts = {}, profiles = [];
const DEFAULT_GUIDES = ${JSON.stringify(DEFAULT_EXTRA_GUIDES)};
const MASK = ${JSON.stringify(MASK)};

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { "content-type": "application/json", authorization: "Bearer " + token, ...(opts.headers || {}) } });
  // Forget the rejected token so render()'s error handler leaves the sign-in form on screen.
  if (res.status === 401) {
    token = null;
    try { localStorage.removeItem("ftv_admin"); } catch {}
    askToken("That token didn't work. Check it and try again.");
    throw new Error("Sign in again");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || res.statusText);
  return data;
}

function askToken(message) {
  $("#app").innerHTML = '<div class="card"><h3>Sign in</h3>' + (message ? '<p class="err">' + esc(message) + "</p>" : "") +
    '<label>Admin token</label><input id="tok" type="password" autocomplete="current-password"><p><button class="b p" id="go">Continue</button></p></div>';
  const go = () => { token = $("#tok").value.trim(); if (!token) return; try { localStorage.setItem("ftv_admin", token); } catch {} render(); };
  $("#go").onclick = go;
  $("#tok").onkeydown = (e) => { if (e.key === "Enter") go(); };
  $("#tok").focus();
}

const ago = (t) => { if (!t) return "never"; const s = (Date.now() - t) / 1000; if (s < 90) return "just now"; if (s < 3600) return Math.round(s / 60) + " min ago"; if (s < 86400) return Math.round(s / 3600) + " h ago"; return Math.round(s / 86400) + " d ago"; };

async function renderDevices() {
  const [{ devices }, p] = await Promise.all([api("/api/admin/devices"), api("/api/admin/profiles")]);
  profiles = p.profiles;
  const opts = profiles.map((x) => '<option value="' + esc(x.id) + '">' + esc(x.name) + "</option>").join("");
  let html = '<div class="card"><h3 style="margin-top:0">Add a TV</h3><p class="muted small">Open Family TV on the TV and type the code it shows.</p><div class="grid">' +
    '<div><label>Code on the TV</label><input id="pc" maxlength="6" style="text-transform:uppercase"></div>' +
    '<div><label>Name (e.g. Mom living room)</label><input id="pn"></div>' +
    '<div><label>Profile</label><select id="pp">' + (opts || '<option value="">Create a profile first</option>') + "</select></div></div>" +
    '<p><button class="b p" id="pair">Add TV</button> <span id="pairmsg"></span></p></div>';
  if (!devices.length) html += '<div class="card muted">No TVs yet.</div>';
  for (const d of devices) {
    const online = d.last_seen && Date.now() - d.last_seen < 3 * 60 * 1000;
    const v = currentCheck(d);
    const cls = v ? (v.severity >= 3 ? "bad" : v.severity >= 1 ? "warn" : "on") : online ? "on" : "";
    const playing = d.status && d.status.playing ? "Watching " + esc(d.status.playing.title) : "";
    const err = d.status && d.status.lastError ? '<span class="err">Last error: ' + esc(d.status.lastError) + "</span>" : "";
    html += '<div class="card"><div class="row"><span class="dot ' + cls + '"></span><strong>' + esc(d.nickname) + '</strong><span class="muted small">' +
      esc(d.manufacturer) + " " + esc(d.model) + " · " + appName(d) + " " + esc(d.app_version) + " · " + esc(d.profile_name) + " · seen " + ago(d.last_seen) +
      '</span><span style="margin-left:auto"><button class="b" data-open="' + esc(d.id) + '">Details</button></span></div>' +
      (playing || err ? '<div class="small" style="margin-top:6px">' + playing + " " + err + "</div>" : "") + guideLine(d.status && d.status.guide) +
      (v ? '<div class="small" style="margin-top:6px">Last check: <strong>' + esc(v.label) + "</strong> (" + ago(d.last_verdict_at) + ")</div>" : "") +
      '<div id="d-' + esc(d.id) + '"></div></div>';
  }
  $("#app").innerHTML = html;
  const fromQr = (location.hash.match(/pair=([A-Z0-9]{6})/i) || [])[1];
  if (fromQr) { $("#pc").value = fromQr.toUpperCase(); $("#pn").focus(); }
  $("#pair").onclick = async () => {
    try {
      await api("/api/admin/pair", { method: "POST", body: JSON.stringify({ code: $("#pc").value, nickname: $("#pn").value, profileId: $("#pp").value }) });
      history.replaceState(null, "", location.pathname);
      render();
    } catch (e) { $("#pairmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  };
  document.querySelectorAll("[data-open]").forEach((b) => (b.onclick = () => openDevice(b.dataset.open, devices.find((x) => x.id === b.dataset.open))));
}

// Which app the TV runs, so two TVs with one name (the Kotlin and Rust apps) are told apart.
// TVs say so themselves; older builds are known by their version line.
function appName(d) {
  const app = d.status && typeof d.status.app === "string" ? d.status.app.toLowerCase() : "";
  if (app === "kotlin" || app === "rust" || app === "apple") return app[0].toUpperCase() + app.slice(1);
  if (d.status && d.status.platform) return "Apple";
  const ver = String(d.app_version || "");
  return ver.startsWith("0.2.") ? "Kotlin" : ver.startsWith("0.1.") ? "Rust" : "app";
}

// The TV's last check, while it still says something: not after 6 hours, and not once the TV is
// playing again after a check that found a problem.
function currentCheck(d) {
  const v = d.last_verdict ? verdicts[d.last_verdict] : null;
  if (!v || !d.last_verdict_at || Date.now() - d.last_verdict_at > 6 * 3600 * 1000) return null;
  if (v.severity > 0 && d.status && d.status.playing) return null;
  return v;
}

// How the TV's last refresh used the extra guides; unmatched thin channels are candidates for a pin.
function guideLine(g) {
  if (!g) return "";
  // Stored telemetry predates validation too: never treat device-supplied counts as markup.
  const count = (v) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? Math.min(v, 1000000000) : 0;
  const linked = Object.values(g.linked || {}).reduce((a, b) => Math.min(a + count(b), 1000000000), 0);
  const textList = (v) => Array.isArray(v) ? v.filter((s) => typeof s === "string").slice(0, 100).map((s) => s.slice(0, 1000)) : [];
  const errors = textList(g.errors);
  const why = errors.length ? '<details class="muted"><summary>Why</summary>' + esc(errors.join("; ")) + "</details>" : "";
  // No extra guide loaded: every channel would read as "no match", which says nothing.
  if (errors.length && !linked && !count(g.switched)) return '<div class="small muted" style="margin-top:6px">Extra guide unavailable' + why + "</div>";
  const unmatchedCount = count(g.unmatchedCount);
  let html = '<div class="small" style="margin-top:6px">Guide: ' + count(g.switched) + " of " + count(g.thin) + " thin channels now use an extra guide; " +
    linked + " channels linked in all";
  const unmatched = textList(g.unmatched);
  if (unmatchedCount) html += '<details><summary>' + unmatchedCount + " thin channels with no match (pin them in the profile)</summary>" + esc(unmatched.join(", ")) +
    (unmatchedCount > unmatched.length ? " …" : "") + "</details>";
  if (errors.length) html += '<div class="muted">Some extra guides did not load' + why + "</div>";
  return html + "</div>";
}

async function openDevice(id, dev) {
  const box = $("#d-" + id);
  if (box.innerHTML) { box.innerHTML = ""; return; }
  const { diagnostics, commands, suggestions } = await api("/api/admin/devices/" + id);
  const popts = profiles.map((x) => '<option value="' + esc(x.id) + '"' + (x.id === dev.profile_id ? " selected" : "") + ">" + esc(x.name) + "</option>").join("");
  let html = '<div class="row" style="margin-top:10px">' +
    '<button class="b p" data-cmd="diagnose">Run check</button><button class="b" data-cmd="refresh">Reload channels</button>' +
    '<button class="b" data-cmd="clear_cache">Clear cache</button><button class="b" data-cmd="restart">Restart app</button>' +
    '<button class="b" data-cmd="message">Show a message</button><button class="b" data-cmd="update">Update app</button>' +
    '<select id="mv-' + esc(id) + '" style="width:auto">' + popts + '</select><button class="b" data-move>Change profile</button>' +
    '<button class="b" data-del style="margin-left:auto">Remove TV</button></div><div class="small muted" id="cm-' + esc(id) + '"></div>';
  if (suggestions && suggestions.length) {
    html += "<h4>Suggestions from the last check</h4>" + suggestions.map((s, i) => '<div class="verdict s' + s.severity + '"><strong>' + esc(s.title) + "</strong><div>" + esc(s.detail) + "</div>" +
      (s.action ? '<p style="margin:6px 0 0"><button class="b" data-fix="' + i + '">' + esc(s.action.label) + "</button></p>" : "") + "</div>").join("");
  }
  html += "<h4>Checks</h4>";
  if (!diagnostics.length) html += '<p class="muted small">No checks yet. Press Run check; the TV answers within a minute while it is on.</p>';
  for (const r of diagnostics) {
    html += '<div class="verdict s' + (r.severity ?? 2) + '"><strong>' + esc(r.label) + '</strong> <span class="muted small">' + ago(r.created_at) + "</span><div>" + esc(r.advice) + "</div>" +
      (r.report && r.report.summary ? '<div class="small" style="margin-top:4px">' + esc(r.report.summary) + "</div>" : "") +
      "<details><summary class=\\"small\\">Full report</summary><pre>" + esc(JSON.stringify(r.report, null, 2)) + "</pre></details></div>";
  }
  html += "<h4>Recent actions</h4>" + (commands.length ? commands.map((c) => '<div class="small">' + esc(c.type) + " · sent " + ago(c.created_at) + " · " +
    (c.acked_at ? "done" : c.delivered_at ? "delivered" : "waiting for TV") + "</div>").join("") : '<p class="muted small">None.</p>');
  box.innerHTML = html;
  box.querySelectorAll("[data-cmd]").forEach((b) => (b.onclick = async () => {
    let payload = null;
    if (b.dataset.cmd === "message") { const t = prompt("Message to show on the TV"); if (!t) return; payload = { text: t }; }
    if (b.dataset.cmd === "restart" && !confirm("Restart the app on this TV?")) return;
    try { await api("/api/admin/devices/" + id + "/commands", { method: "POST", body: JSON.stringify({ type: b.dataset.cmd, payload }) });
      $("#cm-" + id).textContent = "Sent. The TV picks it up within a minute if it is on."; } catch (e) { $("#cm-" + id).textContent = e.message; }
  }));
  box.querySelectorAll("[data-fix]").forEach((b) => (b.onclick = async () => {
    const a = suggestions[+b.dataset.fix].action;
    if (!confirm(a.label + " for every TV on this profile?")) return;
    try { await api("/api/admin/profiles/" + dev.profile_id + "/primary-server", { method: "POST", body: JSON.stringify({ sourceId: a.sourceId, url: a.url }) });
      b.replaceWith(Object.assign(document.createElement("span"), { className: "small ok", textContent: "Done. The TVs reload within a minute." })); }
    catch (e) { $("#cm-" + id).textContent = e.message; }
  }));
  box.querySelector("[data-move]").onclick = async () => { await api("/api/admin/devices/" + id, { method: "PATCH", body: JSON.stringify({ profileId: $("#mv-" + id).value }) }); render(); };
  box.querySelector("[data-del]").onclick = async () => { if (confirm("Remove this TV? It will show a new pairing code.")) { await api("/api/admin/devices/" + id, { method: "DELETE" }); render(); } };
}

// What the panel last said about a line, shown on its source card.
function lineFacts(l) {
  if (!l) return "";
  const bits = [l.expire ? "expires " + esc(l.expire) + " (" + leftText(l) + ")" : leftText(l)];
  if (l.enabled === false) bits.push('<span class="err">disabled on the panel</span>');
  if (l.maxConnections) bits.push(plural(l.maxConnections, "screen"));
  if (l.note) bits.push(esc(l.note));
  return '<p class="small" style="margin:0 0 8px">8K line: ' + bits.join(" · ") + "</p>";
}

function sourceForm(s, i, line) {
  const urls = (s.serverUrls || []).join("\\n");
  return '<div class="card" data-src="' + i + '">' + lineFacts(line) + '<div class="grid">' +
    '<div><label>Name</label><input name="name" value="' + esc(s.name) + '"></div>' +
    '<div><label>Type</label><select name="type"><option value="xtream"' + (s.type === "xtream" ? " selected" : "") + '>Xtream login</option><option value="m3u"' + (s.type === "m3u" ? " selected" : "") + ">M3U link</option></select></div>" +
    '<div><label>Username</label><input name="username" value="' + esc(s.username) + '"></div>' +
    '<div><label>Password</label><input name="password" type="password" value="' + esc(s.password) + '"></div></div>' +
    '<label>Server URLs (Xtream, one per line, backups after the main one)</label><textarea name="serverUrls" rows="2">' + esc(urls) + "</textarea>" +
    '<label>M3U playlist URL</label><input name="m3uUrl" value="' + esc(s.m3uUrl) + '">' +
    '<label>Guide (XMLTV) URL, optional</label><input name="epgUrl" value="' + esc(s.epgUrl) + '">' +
    '<p class="muted small">Package sorter link: set it for this line on the Lines page.</p><input type="hidden" name="sorterUrl" value="' + esc(s.sorterUrl) + '">' +
    '<input type="hidden" name="id" value="' + esc(s.id) + '"><input type="hidden" name="copyOf" value="' + esc(s.copyOf) + '">' +
    '<p><button class="b" data-rm>Remove source</button>' + (s.copyOf ? ' <span class="muted small">Added from your lines; saved when you click Save.</span>' : "") + "</p></div>";
}

// Who uses a line, for pickers and the Lines page.
const usedByText = (l) => l.profiles.length ? "in " + l.profiles.map((x) => x.name).join(", ") : "not on a profile";

// Add a line to the profile: one from the Lines page, or a new one.
function addLineCard(draft, lines, panel) {
  if (!lines) return '<div class="card muted small">Loading your lines…</div>';
  const mine = new Set(draft.data.sources.map((s) => (s.username || "").toLowerCase()));
  const active = [], other = [];
  for (const l of lines.lines || []) {
    if (mine.has(l.username.toLowerCase())) continue;
    const opt = '<option value="' + esc(l.username) + '">' + esc(l.name + " · " + l.username + " · " + (l.daysLeft == null ? "status unknown" : l.daysLeft < 0 ? "expired" : plural(l.daysLeft, "day") + " left") + " · " + usedByText(l)) + "</option>";
    (l.enabled === false || (l.daysLeft != null && l.daysLeft < 0) ? other : active).push(opt);
  }
  let html = '<div class="card"><h3 style="margin-top:0">Add a line</h3>';
  if (active.length || other.length) {
    html += '<div class="row"><select id="pickline" style="flex:1">' + active.join("") + (other.length ? '<optgroup label="Expired or disabled">' + other.join("") + "</optgroup>" : "") + "</select>" +
      '<button class="b" id="useline">Add to this profile</button></div>' +
      '<p class="muted small">Copies that line here with its login and details; nothing changes until you click Save. A line used by two profiles shares its screens.</p>';
  } else html += '<p class="muted small">No other lines yet. Add the lines you have on the Lines page.</p>';
  if (lines.configured && panel && !panel.error) {
    const months = (panel.months || [1, 3, 6, 12]).map((m) => '<option value="' + m + '">' + plural(m, "month") + "</option>").join("");
    const pkgs = (panel.packages || []).map((x) => '<option value="' + esc(x.id) + '">' + esc(x.name) + "</option>").join("");
    html += '<h4 style="margin:14px 0 4px">Or create a new line</h4>' + (draft.id ?
      '<div class="grid"><div><label>Package</label><select id="pnpkg">' + (pkgs || '<option value="">No packages from the panel</option>') + "</select></div>" +
      '<div><label>Length</label><select id="pnmon">' + months + "</select></div>" +
      '<div><label>Name in the profile</label><input id="pnname" value="8K line"></div></div>' +
      '<p><button class="b" id="pncreate">Create line</button> <span class="muted small">' + esc(panel.reseller.credits) + " credits left. Uses credits; the line is added to this profile right away.</span></p>"
      : '<p class="muted small">Save the profile first, then you can create a new line for it here.</p>');
  } else if (panel && panel.error) html += '<p class="small err">Panel: ' + esc(panel.error) + "</p>";
  return html + '<div id="plmsg" class="small"></div></div>';
}

async function renderProfiles(editId) {
  const { profiles: list } = await api("/api/admin/profiles");
  profiles = list;
  if (editId !== undefined) return editProfile(list.find((p) => p.id === editId) || { name: "", data: { sources: [] } });
  let html = '<div class="card row"><span class="muted">A profile is a set of IPTV lines one household uses.</span><button class="b p" id="newp" style="margin-left:auto">New profile</button></div>';
  for (const p of list) html += '<div class="card row"><strong>' + esc(p.name) + '</strong><span class="muted small">' + p.data.sources.length + " source(s) · updated " + ago(p.updated_at) +
    '</span><button class="b" style="margin-left:auto" data-edit="' + esc(p.id) + '">Edit</button></div>';
  $("#app").innerHTML = html;
  $("#newp").onclick = () => editProfile({ name: "", data: { sources: [] } });
  document.querySelectorAll("[data-edit]").forEach((b) => (b.onclick = () => renderProfiles(b.dataset.edit)));
}

// Extra guides fill channels the provider's guide leaves thin; pins link channels the TV can't match by name.
function guidesCard(d) {
  const urls = (d.extraGuides || DEFAULT_GUIDES).join("\\n");
  const pins = Object.entries(d.guidePins || {}).map(([n, g]) => n + " = " + g).join("\\n");
  return '<div class="card"><h3 style="margin-top:0">Extra guides</h3>' +
    '<p class="muted small">Used only for channels the provider guide leaves thin, first one first. One URL per line; empty turns them off.</p>' +
    '<textarea id="xg" rows="3">' + esc(urls) + "</textarea>" +
    '<label>Channel pins, one per line: channel name = guide channel id (for example CA| CTV HD = CTV.Toronto.HD.ca2)</label>' +
    '<textarea id="gp" rows="3">' + esc(pins) + "</textarea>" +
    '<p class="muted small">Each epgshare01 guide lists its channel ids in a .txt file next to it, such as epg_ripper_CA2.txt.</p></div>';
}

// The profile's Real-Debrid account: linked from Settings on any of its TVs, shared by all of them.
function debridCard(draft) {
  if (!draft.id) return "";
  const rd = draft.realDebrid;
  return '<div class="card row"><span><strong>Real-Debrid:</strong> ' +
    (rd ? "connected as " + esc(rd.username || "an account") + '</span><button class="b" id="rdoff" style="margin-left:auto">Disconnect</button>'
      : '<span class="muted">not connected. Connect it in Settings on any TV in this profile; every TV here then uses it.</span></span>') + "</div>";
}

// This card updates itself: profile form drafts and credentials never enter each other's state.
function mountRdApiKey(draft, card) {
  card.innerHTML = '<h3 style="margin-top:0">Real-Debrid API key</h3><p class="muted small">Shared with TVs on this profile. Saved separately from the profile and from the connected account above.</p>' +
    '<p data-preview aria-label="Saved API key preview"></p><label for="rdkey">API key (paste to add or replace)</label><input id="rdkey" type="password" autocomplete="off" spellcheck="false" autocapitalize="none" maxlength="512">' +
    '<p class="row"><button class="b p" data-save disabled>Save API key</button><button class="b" data-remove disabled>Remove API key</button><button class="b" data-refresh>Refresh status</button></p><span data-status role="status" aria-live="polite">Loading API key status…</span>';
  const input = card.querySelector("input"), status = card.querySelector("[data-status]"), preview = card.querySelector("[data-preview]");
  const save = card.querySelector("[data-save]"), remove = card.querySelector("[data-remove]"), refresh = card.querySelector("[data-refresh]");
  const path = "/api/admin/profiles/" + draft.id + "/realdebrid/api-key";
  let revision = null, configured = false, busy = false;
  const controls = () => { save.disabled = busy || revision === null; remove.disabled = busy || revision === null || !configured; refresh.disabled = busy; input.disabled = busy; };
  const show = (result, expectedRevision = null) => {
    if (!result || result.profileId !== draft.id || !Number.isSafeInteger(result.revision) || result.revision < 0 ||
        (expectedRevision !== null && result.revision !== expectedRevision) || (revision !== null && result.revision < revision) ||
        typeof result.configured !== "boolean" || (result.configured
          ? typeof result.maskedPreview !== "string" || !/^[\\x21-\\x7e]{4}\\*{8}[\\x21-\\x7e]{4}$/.test(result.maskedPreview)
          : result.maskedPreview !== null)) throw new Error("Invalid credential status");
    revision = result.revision; configured = result.configured;
    preview.textContent = configured ? result.maskedPreview : "";
    status.textContent = configured ? "API key saved. Only the first and last 4 characters are shown." : "No API key saved.";
  };
  const load = async () => {
    if (busy) return;
    busy = true; preview.textContent = ""; controls();
    try { const result = await api(path); if (card.isConnected) show(result); }
    catch { if (card.isConnected) { revision = null; preview.textContent = ""; status.textContent = "Could not load API key status. Refresh to try again."; } }
    finally { busy = false; if (card.isConnected) controls(); }
  };
  const mutate = async (method) => {
    if (busy || revision === null) return;
    if (method === "DELETE" && !confirm("Remove the API key from every TV on this profile?")) return;
    const value = method === "PUT" ? input.value.trim() : null;
    input.value = "";
    if (method === "PUT" && !/^[\\x21-\\x7e]{16,512}$/.test(value)) { status.textContent = "Enter an API key with 16 to 512 printable non-space ASCII characters."; return; }
    busy = true; preview.textContent = ""; controls();
    try {
      const result = await api(path, { method, body: JSON.stringify(method === "PUT" ? { revision, apiKey: value } : { revision }) });
      if (card.isConnected) show(result, revision + 1);
    } catch {
      // A failed write may have reached storage. Require a fresh revision and explicit retry.
      if (card.isConnected) { revision = null; preview.textContent = ""; status.textContent = "Could not update the API key. Refresh status before trying again."; }
    } finally { busy = false; if (card.isConnected) controls(); }
  };
  save.onclick = () => mutate("PUT"); remove.onclick = () => mutate("DELETE"); refresh.onclick = load;
  void load();
}

// Category order and visibility, per kind. Rows are what the profile's TVs reported, saved order first.
const KINDS = [["live", "Live TV"], ["movie", "Movies"], ["series", "TV Shows"]];
let catKind = "live";
// Which kinds' lists are open; closed by default, remembered in this browser.
let catOpen = {};
try { catOpen = JSON.parse(localStorage.getItem("ftv_cats_open") || "{}") || {}; } catch {}
const toggleCats = () => {
  catOpen = { ...catOpen, [catKind]: !catOpen[catKind] };
  try { localStorage.setItem("ftv_cats_open", JSON.stringify(catOpen)); } catch {}
};
function categoryRows(draft, reported) {
  const saved = (draft.data.categoryOrder || {})[catKind] || [];
  const seen = new Set(), rows = [];
  for (const n of [...saved, ...((reported[catKind] || {}).names || [])]) { const k = n.toLowerCase(); if (!seen.has(k)) { seen.add(k); rows.push(n); } }
  return rows;
}
function categoriesCard(draft, reported) {
  if (!draft.id) return '<div class="card muted small">Save the profile and open Family TV on one of its TVs; its categories then show up here to arrange.</div>';
  const rows = categoryRows(draft, reported);
  const hidden = new Set((draft.data.hiddenCategories || []).map((n) => n.toLowerCase()));
  let html = '<div class="card"><h3 style="margin-top:0">Categories</h3><div class="row" style="margin-bottom:8px">' +
    KINDS.map(([k, label]) => '<button class="b' + (k === catKind ? " p" : "") + '" data-kind="' + k + '">' + label + "</button>").join("") +
    '<span class="muted small">Drag by the handle (or use the buttons) to order, and tick to show or hide what the TVs on this profile list. Saved with the profile.</span></div>';
  if (!rows.length) return html + '<p class="muted small">No categories reported yet. Open Family TV on a TV with this profile and come back in a minute.</p></div>';
  const open = !!catOpen[catKind];
  html += '<button class="b" data-catfold aria-expanded="' + open + '">' + (open ? "▾ " : "▸ ") + KINDS.find(([k]) => k === catKind)[1] + ": " + rows.length + (rows.length === 1 ? " category" : " categories") + "</button>";
  if (!open) return html + "</div>";
  html += '<div id="catlist">' + rows.map((n, i) => '<div class="row cat" data-cat="' + i + '" style="gap:6px;padding:4px 0;border-top:1px solid var(--line)">' +
    '<span class="grip" data-drag title="Drag to move">⠿</span>' +
    '<input type="checkbox" style="width:auto" data-show="' + i + '"' + (hidden.has(n.toLowerCase()) ? "" : " checked") + ">" +
    '<span style="flex:1">' + esc(n) + "</span>" +
    '<button class="b" data-top="' + i + '" title="Move to top">Top</button><button class="b" data-up="' + i + '" title="Move up">▲</button><button class="b" data-down="' + i + '" title="Move down">▼</button></div>').join("") + "</div>";
  return html + "</div>";
}

function editProfile(p) {
  const draft = JSON.parse(JSON.stringify(p));
  let reported = {}, lines = null, panel = null;
  if (draft.id) api("/api/admin/profiles/" + draft.id + "/categories").then((r) => { reported = r.reported; collect(); paint(); }).catch(() => {});
  api("/api/admin/lines").then(async (l) => {
    if (l.configured) panel = await api("/api/admin/panel").catch((e) => ({ error: e.message }));
    lines = l; collect(); paint();
  }).catch(() => { lines = { lines: [] }; collect(); paint(); });
  const lineOf = (s) => lines && (lines.lines || []).find((l) => s.username && (l.username || "").toLowerCase() === s.username.toLowerCase());
  const moveCat = (i, to) => {
    collect();
    const rows = categoryRows(draft, reported);
    const [n] = rows.splice(i, 1);
    rows.splice(Math.max(0, Math.min(rows.length, to)), 0, n);
    draft.data.categoryOrder = { ...(draft.data.categoryOrder || {}), [catKind]: rows };
    paint();
  };
  // Drag a category row by its handle (mouse or touch); the row follows the pointer and drops between rows.
  const dragCat = (e, handle) => {
    e.preventDefault();
    const row = handle.closest("[data-cat]"), from = +row.dataset.cat;
    const els = [...document.querySelectorAll("#catlist [data-cat]")];
    const startY = e.clientY;
    let to = from;
    handle.setPointerCapture(e.pointerId);
    row.classList.add("dragging");
    handle.onpointermove = (ev) => {
      row.style.transform = "translateY(" + (ev.clientY - startY) + "px)";
      // The row whose middle the pointer is above is where it drops; past the last one, it goes last.
      const i = els.findIndex((r) => r !== row && ev.clientY < r.getBoundingClientRect().top + r.offsetHeight / 2);
      to = i < 0 ? els.length - 1 : i > from ? i - 1 : i;
      els.forEach((r, k) => (r.style.boxShadow = r === row ? "" : k === (to >= from ? to + 1 : to) ? "inset 0 2px 0 var(--accent)" : ""));
      if (to === els.length - 1 && to !== from) els[els.length - 1].style.boxShadow = "inset 0 -2px 0 var(--accent)";
      if (ev.clientY < 60) window.scrollBy(0, -12); else if (ev.clientY > innerHeight - 60) window.scrollBy(0, 12);
    };
    const end = (drop) => {
      handle.onpointermove = handle.onpointerup = handle.onpointercancel = null;
      if (drop && to !== from) moveCat(from, to); else paint();
    };
    handle.onpointerup = () => end(true);
    handle.onpointercancel = () => end(false);
  };
  const paint = () => {
    $("#app").innerHTML = '<div class="card"><label>Profile name</label><input id="pname" value="' + esc(draft.name) + '">' +
      '<label>Parental PIN (optional, asked before Adult Content opens on the Categories page)</label><input id="ppin" type="password" value="' + esc(draft.data.parentalPin) + '">' +
      '<label>Startup video</label><select id="psplash"><option value="">Family TV</option>' +
      '<option value="the-nook"' + (draft.data.splash === "the-nook" ? " selected" : "") + '>The Nook (Airbnb guests)</option></select>' +
      '<label><input type="checkbox" style="width:auto" id="popen"' + (draft.data.openOnStart ? " checked" : "") + '> Open when the TV turns on (each TV needs the Display over other apps permission once; its Settings page shows how)</label>' +
      '<label><input type="checkbox" style="width:auto" id="pusage"' + (draft.data.usageStats ? " checked" : "") + '> Usage stats (anonymous): the TVs report which screens and buttons get used, never what is watched or searched. Kept 30 days</label></div>' +
      draft.data.sources.map((s, i) => sourceForm(s, i, lineOf(s))).join("") + addLineCard(draft, lines, panel) + guidesCard(draft.data) + debridCard(draft) + (draft.id ? '<div class="card" id="rd-api-key"></div>' : '<div class="card muted small">Save the profile to add a Real-Debrid API key.</div>') + categoriesCard(draft, reported) +
      '<div class="row"><button class="b" id="adds">Add source</button><button class="b p" id="save">Save</button>' +
      (draft.id ? '<button class="b" id="delp">Delete profile</button>' : "") + '<button class="b" id="back">Back</button><span id="perr" class="err"></span></div>';
    if (draft.id) mountRdApiKey(draft, $("#rd-api-key"));
    $("#adds").onclick = () => { collect(); draft.data.sources.push({ id: crypto.randomUUID(), type: "xtream", name: "Line " + (draft.data.sources.length + 1) }); paint(); };
    document.querySelectorAll("[data-rm]").forEach((b) => (b.onclick = () => { collect(); draft.data.sources.splice(+b.closest("[data-src]").dataset.src, 1); paint(); }));
    $("#back").onclick = () => renderProfiles();
    if ($("#rdoff")) $("#rdoff").onclick = async () => {
      if (!confirm("Disconnect Real-Debrid from every TV on this profile?")) return;
      collect();
      await api("/api/admin/profiles/" + draft.id + "/realdebrid", { method: "DELETE" });
      draft.realDebrid = null;
      paint();
    };
    if ($("#useline")) $("#useline").onclick = () => {
      collect();
      const l = (lines.lines || []).find((x) => x.username === $("#pickline").value);
      if (!l) return;
      draft.data.sources.push({ id: crypto.randomUUID(), type: "xtream", name: l.name, serverUrls: l.serverUrls, username: l.username, password: MASK, copyOf: l.username });
      paint();
    };
    if ($("#pncreate")) $("#pncreate").onclick = async () => {
      const packageId = $("#pnpkg").value, m = +$("#pnmon").value;
      if (!packageId) return;
      if (!confirm("Create a new " + plural(m, "month") + " line (" + $("#pnpkg").selectedOptions[0].textContent + ") for " + draft.name + "? This uses panel credits.")) return;
      collect();
      $("#pncreate").disabled = true; $("#plmsg").textContent = "Creating the line…";
      try {
        const r = await api("/api/admin/lines/new", { method: "POST", body: JSON.stringify({ profileId: draft.id, packageId, months: m, name: $("#pnname").value }) });
        if (!r.ok) { $("#pncreate").disabled = false; $("#plmsg").innerHTML = '<span class="err">' + esc(r.error) + "</span> Link: " + esc(r.url); return; }
        draft.data.sources.push(r.source);
        lines = await api("/api/admin/lines");
        panel = await api("/api/admin/panel").catch((e) => ({ error: e.message }));
        paint();
        $("#plmsg").innerHTML = '<span class="ok">Line ' + esc(r.username) + " created and saved to this profile.</span>";
      } catch (e) { $("#pncreate").disabled = false; $("#plmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
    };
    document.querySelectorAll("[data-kind]").forEach((b) => (b.onclick = () => { collect(); catKind = b.dataset.kind; paint(); }));
    document.querySelectorAll("[data-catfold]").forEach((b) => (b.onclick = () => { collect(); toggleCats(); paint(); }));
    document.querySelectorAll("[data-drag]").forEach((h) => (h.onpointerdown = (e) => dragCat(e, h)));
    document.querySelectorAll("[data-top]").forEach((b) => (b.onclick = () => moveCat(+b.dataset.top, 0)));
    document.querySelectorAll("[data-up]").forEach((b) => (b.onclick = () => moveCat(+b.dataset.up, +b.dataset.up - 1)));
    document.querySelectorAll("[data-down]").forEach((b) => (b.onclick = () => moveCat(+b.dataset.down, +b.dataset.down + 1)));
    if ($("#delp")) $("#delp").onclick = async () => { if (confirm("Delete this profile? TVs on it stop playing until moved.")) { await api("/api/admin/profiles/" + draft.id, { method: "DELETE" }); renderProfiles(); } };
    $("#save").onclick = async () => {
      collect();
      try {
        await api(draft.id ? "/api/admin/profiles/" + draft.id : "/api/admin/profiles", { method: draft.id ? "PUT" : "POST", body: JSON.stringify({ name: draft.name, data: draft.data }) });
        renderProfiles();
      } catch (e) { $("#perr").textContent = e.message; }
    };
  };
  const collect = () => {
    draft.name = $("#pname").value;
    draft.data.parentalPin = $("#ppin").value || undefined;
    draft.data.splash = $("#psplash").value || undefined;
    draft.data.openOnStart = $("#popen").checked || undefined;
    draft.data.usageStats = $("#pusage").checked || undefined;
    draft.data.extraGuides = $("#xg").value.split(/\\s+/).filter(Boolean);
    const pins = {};
    for (const line of $("#gp").value.split("\\n")) {
      const at = line.lastIndexOf("=");
      if (at > 0 && line.slice(at + 1).trim()) pins[line.slice(0, at).trim()] = line.slice(at + 1).trim();
    }
    draft.data.guidePins = pins;
    // Hidden names of the kind on screen are re-read from the checkboxes; other kinds' stay as they were.
    const rows = categoryRows(draft, reported);
    if (document.querySelector("[data-show]")) {
      const onScreen = new Set(rows.map((n) => n.toLowerCase()));
      const kept = (draft.data.hiddenCategories || []).filter((n) => !onScreen.has(n.toLowerCase()));
      document.querySelectorAll("[data-show]").forEach((c) => { if (!c.checked) kept.push(rows[+c.dataset.show]); });
      draft.data.hiddenCategories = kept;
    }
    document.querySelectorAll("[data-src]").forEach((el) => {
      const g = (n) => el.querySelector('[name="' + n + '"]').value.trim();
      const s = draft.data.sources[+el.dataset.src];
      Object.assign(s, { id: g("id"), name: g("name"), type: g("type"), username: g("username") || undefined, password: g("password") || undefined,
        serverUrls: g("serverUrls").split(/\\s+/).filter(Boolean), m3uUrl: g("m3uUrl") || undefined, epgUrl: g("epgUrl") || undefined, sorterUrl: g("sorterUrl") || undefined, copyOf: g("copyOf") || undefined });
    });
  };
  paint();
}

// ---------- 8K lines ----------

const plural = (n, w) => n + " " + w + (n === 1 ? "" : "s");
function leftText(l) {
  if (l.daysLeft == null) return '<span class="muted">unknown</span>';
  if (l.daysLeft < 0) return '<span class="err">expired ' + plural(-l.daysLeft, "day") + " ago</span>";
  const cls = l.daysLeft <= 7 ? "err" : l.daysLeft <= 30 ? "warn" : "ok";
  return '<span class="' + cls + '">' + plural(l.daysLeft, "day") + "</span>";
}

async function renderLines(refresh) {
  $("#app").innerHTML = '<div class="card muted">' + (refresh ? "Asking the panel about every line…" : "Loading lines…") + "</div>";
  const [panel, data, p] = await Promise.all([api("/api/admin/panel"), api("/api/admin/lines" + (refresh ? "?refresh=1" : "")), api("/api/admin/profiles")]);
  profiles = p.profiles;
  if (!panel.configured) {
    $("#app").innerHTML = '<div class="card"><h3 style="margin-top:0">Connect the 8K panel</h3><p>Add your reseller API key to the portal as the secret <code>IPTV_PANEL_API_KEY</code>, then redeploy it. ' +
      "This page then shows all your lines, how long each has left, and lets you renew or create lines.</p></div>";
    return;
  }
  const months = (panel.months || [1, 3, 6, 12]).map((m) => '<option value="' + m + '">' + plural(m, "month") + "</option>").join("");
  let html = '<div class="card row">' + (panel.error ? '<span class="err">Panel: ' + esc(panel.error) + "</span>" :
    "<span><strong>" + esc(panel.reseller.credits) + '</strong> credits</span><span class="muted small">reseller ' + esc(panel.reseller.username) + "</span>") +
    '<button class="b" id="lrefresh" style="margin-left:auto">Check all lines now</button></div>';
  html += '<div class="card"><h3 style="margin-top:0">Lines</h3>';
  if (!data.lines.length) html += '<p class="muted">No lines yet. Add the lines you already have below, or create a new one.</p>';
  else {
    html += '<table><tr><th class="wide">Line</th><th>Used by</th><th>Expires</th><th>Left</th><th class="wide">Status</th><th>Renew</th></tr>';
    data.lines.forEach((l, i) => {
      const status = l.error ? '<span class="err">' + esc(l.error) + "</span>" : l.enabled === false ? '<span class="err">disabled on the panel</span>' :
        l.enabled ? "active" + (l.maxConnections ? " · " + plural(l.maxConnections, "screen") : "") : "";
      const used = l.profiles.length ? l.profiles.map((x) => "<strong>" + esc(x.name) + "</strong>").join(", ") :
        '<span class="muted">not on a profile</span><div><button class="b" data-forget="' + i + '" style="padding:2px 8px;margin-top:4px">Remove from list</button></div>';
      html += '<tr><td class="wide">' + esc(l.name) + '<div class="muted small">' + esc(l.username) + (l.note && l.note !== l.name ? " · " + esc(l.note) : "") + "</div>" +
        '<div class="row" style="gap:6px;flex-wrap:nowrap;margin-top:6px"><input data-sorter="' + i + '" placeholder="Package sorter link" title="The provider page where this subscriber picks and orders channels; the app shows it as a QR code in Settings" value="' + esc(l.sorterUrl || "") + '">' +
        '<button class="b" data-ssave="' + i + '" style="padding:2px 8px">Save</button></div></td><td>' + used + "</td>" +
        "<td>" + esc(l.expire || "") + "</td><td>" + leftText(l) + '</td><td class="wide small">' + status +
        (l.checkedAt ? '<div class="muted">checked ' + ago(l.checkedAt) + "</div>" : "") + "</td>" +
        '<td><div class="row" style="gap:6px;flex-wrap:nowrap"><select style="width:auto" data-m="' + i + '">' + months + "</select>" +
        '<button class="b" data-renew="' + i + '">Renew</button></div></td></tr>';
    });
    html += "</table>";
  }
  html += '<div id="lmsg" class="small"></div></div>';
  html += '<div class="card"><h3 style="margin-top:0">Add lines you already have</h3><p class="muted small">The panel has no way to list your lines, so add each one here once. ' +
    "Paste its playlist (M3U) link from the panel, one per line; you can put a name before it, like <code>Grandma http://…</code>. Each is looked up on the panel before it is added. Nothing is renewed or charged.</p>" +
    '<textarea id="addlinks" rows="4" placeholder="Living room http://server:port/get.php?username=…&amp;password=…&amp;type=m3u_plus"></textarea>' +
    '<p><button class="b p" id="addlines">Add lines</button> <span id="amsg" class="small"></span></p></div>';
  const popts = '<option value="">No profile yet</option>' + profiles.map((x) => '<option value="' + esc(x.id) + '">' + esc(x.name) + "</option>").join("");
  const pkgs = (panel.packages || []).map((x) => '<option value="' + esc(x.id) + '">' + esc(x.name) + "</option>").join("");
  html += '<div class="card"><h3 style="margin-top:0">New line</h3><p class="muted small">Creates a line on the panel (uses credits). With a profile, it is added there and its TVs pick it up within a minute.</p><div class="grid">' +
    '<div><label>Profile</label><select id="nprof">' + popts + "</select></div>" +
    '<div><label>Package</label><select id="npkg">' + (pkgs || '<option value="">No packages from the panel</option>') + "</select></div>" +
    '<div><label>Length</label><select id="nmon">' + months + "</select></div>" +
    '<div><label>Name</label><input id="nname" value="8K line"></div></div>' +
    '<p><button class="b p" id="ncreate">Create line</button> <span id="nmsg" class="small"></span></p></div>';
  $("#app").innerHTML = html;
  $("#lrefresh").onclick = () => renderLines(true);
  document.querySelectorAll("[data-renew]").forEach((b) => (b.onclick = async () => {
    const l = data.lines[+b.dataset.renew];
    const m = +document.querySelector('[data-m="' + b.dataset.renew + '"]').value;
    if (!confirm("Renew " + l.name + " (" + l.username + ") for " + plural(m, "month") + "? This uses panel credits.")) return;
    b.disabled = true; $("#lmsg").textContent = "Renewing…";
    try { await api("/api/admin/lines/renew", { method: "POST", body: JSON.stringify({ username: l.username, months: m }) }); renderLines(); }
    catch (e) { b.disabled = false; $("#lmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  }));
  document.querySelectorAll("[data-ssave]").forEach((b) => (b.onclick = async () => {
    const l = data.lines[+b.dataset.ssave];
    const sorterUrl = document.querySelector('[data-sorter="' + b.dataset.ssave + '"]').value.trim();
    b.disabled = true;
    try {
      await api("/api/admin/lines/sorter", { method: "POST", body: JSON.stringify({ username: l.username, sorterUrl }) });
      $("#lmsg").innerHTML = '<span class="ok">' + (sorterUrl ? "Sorter link saved for " : "Sorter link cleared for ") + esc(l.name) + ". Its TVs and phones show it within a minute.</span>";
    } catch (e) { $("#lmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
    b.disabled = false;
  }));
  document.querySelectorAll("[data-forget]").forEach((b) => (b.onclick = async () => {
    const l = data.lines[+b.dataset.forget];
    if (!confirm("Remove " + l.name + " (" + l.username + ") from this list? The line stays on the panel; you can add it again later.")) return;
    try { await api("/api/admin/lines/forget", { method: "POST", body: JSON.stringify({ username: l.username }) }); renderLines(); }
    catch (e) { $("#lmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  }));
  $("#addlines").onclick = async () => {
    const inputs = $("#addlinks").value.split("\\n").map((x) => x.trim()).filter(Boolean);
    const rows = inputs.map((x) => {
      const at = x.search(/https?:\\/\\//);
      return at < 0 ? { link: x } : { name: x.slice(0, at).trim(), link: x.slice(at).trim() };
    });
    if (!rows.length) return;
    $("#addlines").disabled = true; $("#amsg").textContent = "Looking up " + plural(rows.length, "line") + " on the panel…";
    try {
      const r = await api("/api/admin/lines/add", { method: "POST", body: JSON.stringify({ lines: rows }) });
      const bad = r.results.filter((x) => !x.ok);
      if (!bad.length) return renderLines();
      $("#addlines").disabled = false;
      $("#amsg").innerHTML = (r.results.length - bad.length ? '<span class="ok">' + plural(r.results.length - bad.length, "line") + " added.</span> " : "") +
        bad.map((x) => '<span class="err">' + esc(x.username || "A link") + ": " + esc(x.error) + "</span>").join("<br>");
      // Keep only the ones that failed in the box, to fix and try again.
      $("#addlinks").value = inputs.filter((_, i) => !r.results[i].ok).join("\\n");
    } catch (e) { $("#addlines").disabled = false; $("#amsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  };
  $("#ncreate").onclick = async () => {
    const profileId = $("#nprof").value, packageId = $("#npkg").value, m = +$("#nmon").value;
    if (!packageId) return;
    const pname = profileId ? profiles.find((x) => x.id === profileId).name : "no profile yet", pkg = $("#npkg").selectedOptions[0].textContent;
    if (!confirm("Create a new " + plural(m, "month") + " line (" + pkg + ") for " + pname + "? This uses panel credits.")) return;
    $("#ncreate").disabled = true; $("#nmsg").textContent = "Creating…";
    try {
      const r = await api("/api/admin/lines/new", { method: "POST", body: JSON.stringify({ profileId, packageId, months: m, name: $("#nname").value }) });
      if (r.ok) renderLines();
      else { $("#ncreate").disabled = false; $("#nmsg").innerHTML = '<span class="err">' + esc(r.error) + "</span> Link: " + esc(r.url); }
    } catch (e) { $("#ncreate").disabled = false; $("#nmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  };
}

// Paid add-ons: features already in the TV apps, switched on per profile. No checkout yet, so a
// purchase is granted here by hand.
const ADDON_STATUS = [["draft", "Draft (hidden from TVs)"], ["available", "Available"], ["retired", "Retired (no longer sold; owners keep it)"]];
function addonStatusSelect(attr, value) {
  return '<select ' + attr + ">" + ADDON_STATUS.map(([v, l]) => '<option value="' + v + '"' + (v === value ? " selected" : "") + ">" + l + "</option>").join("") + "</select>";
}

async function renderAddons() {
  const [data, p] = await Promise.all([api("/api/admin/addons"), api("/api/admin/profiles")]);
  profiles = p.profiles;
  const owned = (pid, aid) => data.purchases.find((x) => x.profile_id === pid && x.addon_id === aid);
  let html = '<div class="card"><h3 style="margin-top:0">Add-ons</h3><p class="muted small">Extra features already built into the TV apps, bought once per profile: ' +
    "one purchase covers every TV on that profile. Drafts stay hidden from TVs. Buying isn't set up yet, so use Grant to give a profile an add-on by hand.</p>" +
    (data.addons.length ? "" : '<p class="muted">No add-ons yet.</p>') + "</div>";
  // Membership: past its end date a profile's add-ons pause (still owned) and come back free when it's renewed.
  const day = (t) => { if (!t) return ""; const d = new Date(t); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0"); };
  if (profiles.length) {
    html += '<div class="card"><h3 style="margin-top:0">Membership</h3><p class="muted small">After the end date, the add-ons on that profile pause; they work again, with no new charge, once the date is moved forward. Leave it empty for no end.</p>' +
      '<table><tr><th>Profile</th><th>Member until</th><th></th></tr>';
    for (const pr of profiles) {
      const until = data.memberships[pr.id];
      html += "<tr><td>" + esc(pr.name) + (until && until <= Date.now() ? ' <span class="err small">lapsed</span>' : "") + '</td><td><input type="date" style="width:auto" data-mu="' + esc(pr.id) + '" value="' + day(until) + '"></td>' +
        '<td><button class="b" data-musave="' + esc(pr.id) + '">Save</button></td></tr>';
    }
    html += "</table></div>";
  }
  data.addons.forEach((a, i) => {
    html += '<div class="card"><div class="row"><strong>' + esc(a.name) + '</strong><span class="muted small">' + esc(a.id) + " · updated " + ago(a.updated_at) + "</span></div>" +
      '<div class="grid"><div><label>Name</label><input data-an="' + i + '" value="' + esc(a.name) + '"></div><div><label>Status</label>' + addonStatusSelect('data-as="' + i + '"', a.status) + "</div></div>" +
      '<label>Description</label><input data-ad="' + i + '" value="' + esc(a.description) + '">' +
      '<p><button class="b" data-asave="' + i + '">Save</button></p>';
    if (profiles.length) {
      html += '<table><tr><th>Profile</th><th>Owns it</th><th></th></tr>';
      for (const pr of profiles) {
        const o = owned(pr.id, a.id), has = o && o.status === "owned";
        html += "<tr><td>" + esc(pr.name) + "</td><td>" + (has ? '<span class="ok">Yes</span>' : o ? '<span class="muted">' + esc(o.status) + "</span>" : '<span class="muted">No</span>') +
          '</td><td><button class="b" data-grant="' + i + '" data-pid="' + esc(pr.id) + '" data-owned="' + (has ? "0" : "1") + '">' + (has ? "Revoke" : "Grant") + "</button></td></tr>";
      }
      html += "</table>";
    }
    html += "</div>";
  });
  html += '<div class="card"><h3 style="margin-top:0">New add-on</h3><p class="muted small">The id must match the one the TV apps know the feature by; it cannot be changed later.</p><div class="grid">' +
    '<div><label>Id</label><input id="naid" placeholder="multiview"></div><div><label>Name</label><input id="naname"></div>' +
    "<div><label>Status</label>" + addonStatusSelect('id="nastatus"', "draft") + "</div></div>" +
    '<label>Description</label><input id="nadesc"><p><button class="b p" id="nacreate">Add</button></p></div><div id="admsg" class="small"></div>';
  $("#app").innerHTML = html;
  const fail = (e) => { $("#admsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; };
  document.querySelectorAll("[data-asave]").forEach((b) => (b.onclick = async () => {
    const i = b.dataset.asave, a = data.addons[+i];
    const body = { name: $('[data-an="' + i + '"]').value, description: $('[data-ad="' + i + '"]').value, status: $('[data-as="' + i + '"]').value };
    try { await api("/api/admin/addons/" + encodeURIComponent(a.id), { method: "PUT", body: JSON.stringify(body) }); renderAddons(); } catch (e) { fail(e); }
  }));
  document.querySelectorAll("[data-musave]").forEach((b) => (b.onclick = async () => {
    const v = $('[data-mu="' + b.dataset.musave + '"]').value;
    // The end of that day on this computer's clock.
    const until = v ? new Date(v + "T23:59:59").getTime() : null;
    try { await api("/api/admin/profiles/" + b.dataset.musave + "/membership", { method: "PUT", body: JSON.stringify({ until }) }); renderAddons(); } catch (e) { fail(e); }
  }));
  document.querySelectorAll("[data-grant]").forEach((b) => (b.onclick = async () => {
    const a = data.addons[+b.dataset.grant], pr = profiles.find((x) => x.id === b.dataset.pid), give = b.dataset.owned === "1";
    if (!confirm((give ? "Give " : "Take ") + a.name + (give ? " to " : " away from ") + pr.name + "? Its TVs update within a minute.")) return;
    b.disabled = true;
    try { await api("/api/admin/profiles/" + pr.id + "/addons/" + encodeURIComponent(a.id), { method: "POST", body: JSON.stringify({ owned: give }) }); renderAddons(); }
    catch (e) { b.disabled = false; fail(e); }
  }));
  $("#nacreate").onclick = async () => {
    const body = { id: $("#naid").value.trim(), name: $("#naname").value, description: $("#nadesc").value, status: $("#nastatus").value };
    try { await api("/api/admin/addons", { method: "POST", body: JSON.stringify(body) }); renderAddons(); } catch (e) { fail(e); }
  };
}

async function render() {
  if (!token) return askToken();
  document.querySelectorAll("nav button").forEach((b) => b.classList.toggle("on", b.dataset.tab === tab));
  try {
    if (!Object.keys(verdicts).length) verdicts = await api("/api/admin/verdicts");
    if (tab === "devices") await renderDevices(); else if (tab === "lines") await renderLines(); else if (tab === "addons") await renderAddons(); else await renderProfiles();
  } catch (e) { if (token) $("#app").innerHTML = '<div class="card err">' + esc(e.message) + "</div>"; }
}
document.querySelectorAll("nav button").forEach((b) => (b.onclick = () => { tab = b.dataset.tab; render(); }));
render();
setInterval(() => { if (tab === "devices" && !document.querySelector("[id^=d-] h4")) render(); }, 30000);
</script>
</body>
</html>`;

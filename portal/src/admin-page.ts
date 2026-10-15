// Single-file admin UI, served by the Worker. No build step, no external requests.
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
.dot{width:10px;height:10px;border-radius:50%;display:inline-block;background:var(--muted)}
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
@media (max-width:700px){.wide{display:none}}
</style>
</head>
<body>
<header><h1>Family TV</h1><nav><button data-tab="devices" class="on">Devices</button><button data-tab="profiles">Profiles</button><button data-tab="lines">Lines</button></nav></header>
<main id="app"></main>
<script>
const $ = (s, el = document) => el.querySelector(s);
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
let token = null;
try { token = localStorage.getItem("ftv_admin"); } catch {}
let tab = "devices", verdicts = {}, profiles = [];

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
    const v = d.last_verdict ? verdicts[d.last_verdict] : null;
    const cls = v ? (v.severity >= 3 ? "bad" : v.severity >= 1 ? "warn" : "on") : online ? "on" : "";
    const playing = d.status && d.status.playing ? "Watching " + esc(d.status.playing.title) : "";
    const err = d.status && d.status.lastError ? '<span class="err">Last error: ' + esc(d.status.lastError) + "</span>" : "";
    html += '<div class="card"><div class="row"><span class="dot ' + cls + '"></span><strong>' + esc(d.nickname) + '</strong><span class="muted small">' +
      esc(d.manufacturer) + " " + esc(d.model) + " · app " + esc(d.app_version) + " · " + esc(d.profile_name) + " · seen " + ago(d.last_seen) +
      '</span><span style="margin-left:auto"><button class="b" data-open="' + esc(d.id) + '">Details</button></span></div>' +
      (playing || err ? '<div class="small" style="margin-top:6px">' + playing + " " + err + "</div>" : "") +
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

async function openDevice(id, dev) {
  const box = $("#d-" + id);
  if (box.innerHTML) { box.innerHTML = ""; return; }
  const { diagnostics, commands } = await api("/api/admin/devices/" + id);
  const popts = profiles.map((x) => '<option value="' + esc(x.id) + '"' + (x.id === dev.profile_id ? " selected" : "") + ">" + esc(x.name) + "</option>").join("");
  let html = '<div class="row" style="margin-top:10px">' +
    '<button class="b p" data-cmd="diagnose">Run check</button><button class="b" data-cmd="refresh">Reload channels</button>' +
    '<button class="b" data-cmd="clear_cache">Clear cache</button><button class="b" data-cmd="restart">Restart app</button>' +
    '<button class="b" data-cmd="message">Show a message</button><button class="b" data-cmd="update">Update app</button>' +
    '<select id="mv-' + esc(id) + '" style="width:auto">' + popts + '</select><button class="b" data-move>Change profile</button>' +
    '<button class="b" data-del style="margin-left:auto">Remove TV</button></div><div class="small muted" id="cm-' + esc(id) + '"></div>';
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
  box.querySelector("[data-move]").onclick = async () => { await api("/api/admin/devices/" + id, { method: "PATCH", body: JSON.stringify({ profileId: $("#mv-" + id).value }) }); render(); };
  box.querySelector("[data-del]").onclick = async () => { if (confirm("Remove this TV? It will show a new pairing code.")) { await api("/api/admin/devices/" + id, { method: "DELETE" }); render(); } };
}

function sourceForm(s, i) {
  const urls = (s.serverUrls || []).join("\\n");
  return '<div class="card" data-src="' + i + '"><div class="grid">' +
    '<div><label>Name</label><input name="name" value="' + esc(s.name) + '"></div>' +
    '<div><label>Type</label><select name="type"><option value="xtream"' + (s.type === "xtream" ? " selected" : "") + '>Xtream login</option><option value="m3u"' + (s.type === "m3u" ? " selected" : "") + ">M3U link</option></select></div>" +
    '<div><label>Username</label><input name="username" value="' + esc(s.username) + '"></div>' +
    '<div><label>Password</label><input name="password" type="password" value="' + esc(s.password) + '"></div></div>' +
    '<label>Server URLs (Xtream, one per line, backups after the main one)</label><textarea name="serverUrls" rows="2">' + esc(urls) + "</textarea>" +
    '<label>M3U playlist URL</label><input name="m3uUrl" value="' + esc(s.m3uUrl) + '">' +
    '<label>Guide (XMLTV) URL, optional</label><input name="epgUrl" value="' + esc(s.epgUrl) + '">' +
    '<input type="hidden" name="id" value="' + esc(s.id) + '"><p><button class="b" data-rm>Remove source</button></p></div>';
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

function editProfile(p) {
  const draft = JSON.parse(JSON.stringify(p));
  const paint = () => {
    $("#app").innerHTML = '<div class="card"><label>Profile name</label><input id="pname" value="' + esc(draft.name) + '">' +
      '<label>Parental PIN (optional, locks adult categories)</label><input id="ppin" type="password" value="' + esc(draft.data.parentalPin) + '"></div>' +
      draft.data.sources.map(sourceForm).join("") +
      '<div class="row"><button class="b" id="adds">Add source</button><button class="b p" id="save">Save</button>' +
      (draft.id ? '<button class="b" id="delp">Delete profile</button>' : "") + '<button class="b" id="back">Back</button><span id="perr" class="err"></span></div>';
    $("#adds").onclick = () => { collect(); draft.data.sources.push({ id: crypto.randomUUID(), type: "xtream", name: "Line " + (draft.data.sources.length + 1) }); paint(); };
    document.querySelectorAll("[data-rm]").forEach((b) => (b.onclick = () => { collect(); draft.data.sources.splice(+b.closest("[data-src]").dataset.src, 1); paint(); }));
    $("#back").onclick = () => renderProfiles();
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
    document.querySelectorAll("[data-src]").forEach((el) => {
      const g = (n) => el.querySelector('[name="' + n + '"]').value.trim();
      const s = draft.data.sources[+el.dataset.src];
      Object.assign(s, { id: g("id"), name: g("name"), type: g("type"), username: g("username") || undefined, password: g("password") || undefined,
        serverUrls: g("serverUrls").split(/\\s+/).filter(Boolean), m3uUrl: g("m3uUrl") || undefined, epgUrl: g("epgUrl") || undefined });
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
      "This page then shows each profile's line, how long it has left, and lets you renew or create lines.</p></div>";
    return;
  }
  const months = (panel.months || [1, 3, 6, 12]).map((m) => '<option value="' + m + '">' + plural(m, "month") + "</option>").join("");
  let html = '<div class="card row">' + (panel.error ? '<span class="err">Panel: ' + esc(panel.error) + "</span>" :
    "<span><strong>" + esc(panel.reseller.credits) + '</strong> credits</span><span class="muted small">reseller ' + esc(panel.reseller.username) + "</span>") +
    '<button class="b" id="lrefresh" style="margin-left:auto">Check all lines now</button></div>';
  html += '<div class="card"><h3 style="margin-top:0">Lines</h3>';
  if (!data.lines.length) html += '<p class="muted">No profile has an Xtream login yet. Create a line below or add one in Profiles.</p>';
  else {
    html += '<table><tr><th>Profile</th><th class="wide">Line</th><th>Expires</th><th>Left</th><th class="wide">Status</th><th>Renew</th></tr>';
    for (const l of data.lines) {
      const status = l.error ? '<span class="err">' + esc(l.error) + "</span>" : l.enabled === false ? '<span class="err">disabled on the panel</span>' :
        l.enabled ? "active" + (l.maxConnections ? " · " + plural(l.maxConnections, "screen") : "") : "";
      html += "<tr><td><strong>" + esc(l.profileName) + '</strong></td><td class="wide">' + esc(l.sourceName) + '<div class="muted small">' + esc(l.username) + "</div></td>" +
        "<td>" + esc(l.expire || "") + "</td><td>" + leftText(l) + '</td><td class="wide small">' + status +
        (l.checkedAt ? '<div class="muted">checked ' + ago(l.checkedAt) + "</div>" : "") + "</td>" +
        '<td><div class="row" style="gap:6px;flex-wrap:nowrap"><select style="width:auto" data-m="' + esc(l.profileId + "/" + l.sourceId) + '">' + months + "</select>" +
        '<button class="b" data-renew="' + esc(l.profileId + "/" + l.sourceId) + '">Renew</button></div></td></tr>';
    }
    html += "</table>";
  }
  html += '<div id="lmsg" class="small"></div></div>';
  const popts = profiles.map((x) => '<option value="' + esc(x.id) + '">' + esc(x.name) + "</option>").join("");
  const pkgs = (panel.packages || []).map((x) => '<option value="' + esc(x.id) + '">' + esc(x.name) + "</option>").join("");
  html += '<div class="card"><h3 style="margin-top:0">New line for a profile</h3><p class="muted small">Creates a line on the panel (uses credits) and adds it to the profile. Its TVs pick it up within a minute.</p><div class="grid">' +
    '<div><label>Profile</label><select id="nprof">' + (popts || '<option value="">Create a profile first</option>') + "</select></div>" +
    '<div><label>Package</label><select id="npkg">' + (pkgs || '<option value="">No packages from the panel</option>') + "</select></div>" +
    '<div><label>Length</label><select id="nmon">' + months + "</select></div>" +
    '<div><label>Name in the profile</label><input id="nname" value="8K line"></div></div>' +
    '<p><button class="b p" id="ncreate">Create line</button> <span id="nmsg" class="small"></span></p></div>';
  $("#app").innerHTML = html;
  $("#lrefresh").onclick = () => renderLines(true);
  document.querySelectorAll("[data-renew]").forEach((b) => (b.onclick = async () => {
    const key = b.dataset.renew, [profileId, sourceId] = key.split("/");
    const l = data.lines.find((x) => x.profileId === profileId && x.sourceId === sourceId);
    const m = +document.querySelector('[data-m="' + CSS.escape(key) + '"]').value;
    if (!confirm("Renew " + l.profileName + " (" + l.username + ") for " + plural(m, "month") + "? This uses panel credits.")) return;
    b.disabled = true; $("#lmsg").textContent = "Renewing…";
    try { await api("/api/admin/lines/renew", { method: "POST", body: JSON.stringify({ profileId, sourceId, months: m }) }); renderLines(); }
    catch (e) { b.disabled = false; $("#lmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  }));
  $("#ncreate").onclick = async () => {
    const profileId = $("#nprof").value, packageId = $("#npkg").value, m = +$("#nmon").value;
    if (!profileId || !packageId) return;
    const pname = profiles.find((x) => x.id === profileId).name, pkg = $("#npkg").selectedOptions[0].textContent;
    if (!confirm("Create a new " + plural(m, "month") + " line (" + pkg + ") for " + pname + "? This uses panel credits.")) return;
    $("#ncreate").disabled = true; $("#nmsg").textContent = "Creating…";
    try {
      const r = await api("/api/admin/lines/new", { method: "POST", body: JSON.stringify({ profileId, packageId, months: m, name: $("#nname").value }) });
      if (r.ok) renderLines();
      else { $("#ncreate").disabled = false; $("#nmsg").innerHTML = '<span class="err">' + esc(r.error) + "</span> Link: " + esc(r.url); }
    } catch (e) { $("#ncreate").disabled = false; $("#nmsg").innerHTML = '<span class="err">' + esc(e.message) + "</span>"; }
  };
}

async function render() {
  if (!token) return askToken();
  document.querySelectorAll("nav button").forEach((b) => b.classList.toggle("on", b.dataset.tab === tab));
  try {
    if (!Object.keys(verdicts).length) verdicts = await api("/api/admin/verdicts");
    if (tab === "devices") await renderDevices(); else if (tab === "lines") await renderLines(); else await renderProfiles();
  } catch (e) { if (token) $("#app").innerHTML = '<div class="card err">' + esc(e.message) + "</div>"; }
}
document.querySelectorAll("nav button").forEach((b) => (b.onclick = () => { tab = b.dataset.tab; render(); }));
render();
setInterval(() => { if (tab === "devices" && !document.querySelector("[id^=d-] h4")) render(); }, 30000);
</script>
</body>
</html>`;

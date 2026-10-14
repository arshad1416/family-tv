# Family TV

A lean IPTV player for **Chromecast with Google TV, Android TV and Fire TV**, plus a
**Cloudflare admin portal** to set up and troubleshoot every TV remotely. Nobody types
passwords on a remote, and when a TV goes black the portal tells you why.

```
 TV app (android/)  ── HTTPS ──>  Portal (portal/, Cloudflare Worker + D1)  <──  you, in a browser
      │                                   │
      └─────── Xtream / M3U ──────>  your IPTV provider
```

## What it does

**On the TV**
- Live TV with now/next, a 6-hour guide, movies, and series with episodes. Sources are Xtream logins
  or M3U links with an optional XMLTV guide.
- Several server URLs per Xtream line, so a backup URL is used automatically when the main one fails.
- Lists stream straight into SQLite in batches of 500, and the guide keeps only the next 48 hours for
  your channels. That keeps memory flat on 1 GB Fire TV Sticks.
- D-pad up/down changes channel. A live stream that fails on `.ts` retries once as HLS (`.m3u8`).
- **Help → Run a check**, which also runs by itself when playback fails. It works out which of these
  is the problem and tells the viewer in plain words:

  | Verdict | How it is detected |
  |---|---|
  | No internet | IP-addressed endpoints (1.1.1.1, 8.8.8.8) unreachable |
  | DNS filter on their network | Home DNS fails or answers `0.0.0.0`/`127.x` while DNS-over-HTTPS resolves the host (AdGuard, Pi-hole, NextDNS, router parental controls) |
  | Blocked on their network / ISP | TV can't connect, gets HTTP 451, or gets a block page while the portal can reach the same server |
  | Server address gone | Public DNS has no record either |
  | Provider server down / erroring | Neither TV nor portal can connect, or HTTP 5xx |
  | Wrong login / line expired / too many devices | Xtream `user_info`: auth, status, `exp_date`, `active_cons` vs `max_connections` |
  | Slow connection | Real stream throughput under 3 Mbit/s |

  The check reports what is blocked; it doesn't try to get around blocks. Passwords are never
  included in reports.
- Picks up portal actions within 30 seconds while open, and every 30 minutes in the background.
  The actions are reload channels, clear cache, restart, show a message, run a check, and update the app.

**In the portal**
- **Devices:** each TV shows online/offline, model, app version, what's playing, its last error and
  its last check result with the fix to apply. Action buttons sit on every TV.
- **Add a TV:** the TV shows a 6-character code and a QR. Scanning the QR opens the portal with the
  code filled in. Pick a name and a profile.
- **Profiles:** one per household, holding its lines (Xtream or M3U, with backup URLs and a guide URL),
  an optional parental PIN for adult categories, and hidden categories. Saving a profile reloads every
  TV on it.
- Provider passwords are encrypted at rest (AES-GCM, `CONFIG_KEY`), never sent back to the browser,
  and only delivered to paired TVs.
- The portal's reachability check only probes hosts that are in that TV's own profile, and only the
  server root without credentials, so it can't be used as an open proxy.

## Set up

### 1. Portal (once)
```bash
cd portal
npm install
npx wrangler d1 create family_tv            # paste the database_id into wrangler.toml
npm run db:migrate:remote
npx wrangler secret put ADMIN_TOKEN         # a long random string; the admin page asks for it once
openssl rand -base64 32 | npx wrangler secret put CONFIG_KEY
npm run deploy                               # prints https://family-tv-portal.<you>.workers.dev
```
Keep `CONFIG_KEY` safe: losing it means re-entering the line passwords.
For a nicer sign-in, put the Worker on your own domain behind Cloudflare Access as well.

### 2. TV app
```bash
cd android
./gradlew assembleRelease -PportalUrl=https://family-tv-portal.<you>.workers.dev
# APK: app/build/outputs/apk/release/app-release.apk (about 2.6 MB)
```
Sign release builds with your own key: set `FAMILYTV_KEYSTORE`, `FAMILYTV_KEYSTORE_PASSWORD`,
`FAMILYTV_KEY_ALIAS` and `FAMILYTV_KEY_PASSWORD`. Without them the debug key is used.
Every install must keep the same key, or Android refuses updates.

Host it from the portal, so every TV installs from `<portal>/tv`:
```bash
cd portal
npx wrangler r2 bucket create family-tv-apps     # once
npx wrangler r2 object put family-tv-apps/family-tv.apk --file ../android/app/build/outputs/apk/release/app-release.apk --remote
```

Installing:
- **Fire TV:** Settings → My Fire TV → Developer options → Install unknown apps → *Downloader*. Then type
  `<portal>/tv` into Downloader.
- **Google TV / Android TV:** the same idea with *Downloader* or *Send Files to TV*, after allowing
  unknown sources for that app.

### 3. Remote updates
Upload a new APK with a higher `versionCode` (same `r2 object put` command), then set
`LATEST_APP_VERSION_CODE` and `LATEST_APP_URL` (`<portal>/tv`) in `wrangler.toml` and redeploy. Press **Update app** on a TV and the installer opens there; someone
presses Install once.

## Develop
| Where | Command |
|---|---|
| `portal/` | `npm run dev`, `npm run typecheck`, `npm test` |
| `android/` | `./gradlew testDebugUnitTest assembleDebug` (the debug build talks to `http://10.0.2.2:8787`, the emulator's view of a local `wrangler dev`) |

## Not in v1
Catch-up and recordings, multiple user profiles per TV, iOS/Apple TV, and an FFmpeg audio decoder for
boxes without Dolby passthrough (most TV sticks handle AC3/E-AC3 themselves).

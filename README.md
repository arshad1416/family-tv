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
- **Guide details:** the guide header and the player's info banner show everything the XMLTV guide
  carries: episode (S2 E5) and episode title, genres, rating (the Canadian one first), year, NEW,
  description, picture and cast. When the guide has no description or picture for the focused
  programme, it is looked up on TMDB (with the portal's key) or TVmaze and labelled "Details from
  TMDB/TVmaze". News, sport and the like are never looked up.
- **Extra guides:** each profile lists XMLTV guides (epgshare01's Canada, US and India files unless the
  admin changes them) for channels the provider's guide leaves thin. A channel links to one extra-guide
  channel by a portal pin, its guide id, or its name without country prefix and HD/SD tags. Links
  never cross countries and never pick between regional feeds ("CA| CTV" stays unlinked). A channel whose
  provider schedule covers 80% of the next day keeps it and only gains missing details from the same
  show; a thinner one shows the extra guide's whole schedule. The admin page lists thin channels that
  nothing linked, ready to pin.
- Several server URLs per Xtream line, so a backup URL is used automatically when the main one fails.
- Lists stream straight into SQLite in batches of 500, and the guide keeps only the next 48 hours for
  your channels. That keeps memory flat on 1 GB Fire TV Sticks.
- D-pad up/down changes channel. A live stream that fails on `.ts` retries once as HLS (`.m3u8`).
- **Search** finds channels, movies and series whose words start with what you type, ignoring case
  and accents ("pokemon" finds *Pokémon*, "star wa" finds *Star Wars*); names that only contain the
  text come after those.
- **Startup video:** a 7-second seasonal intro (spring Mar-May, summer Jun-Aug, fall Sep-Nov,
  winter Dec-Feb, by the TV's month) plays while the app starts; any key or a tap skips it. A
  profile's *Startup video* on the portal picks Family TV's or The Nook's (the Airbnb guest profile).
- **Usage stats (anonymous)**, a profile checkbox on the portal, off by default: the TVs report which
  screens and buttons get used (screen, action and a number or fixed word, e.g. time on a page or a
  search's result count) to `POST /api/device/usage`, never what's watched or searched. The portal
  keeps them 30 days in D1 `usage_events`; the allowed names are in `portal/src/usage.ts`.
- **Live scores:** on a sports channel whose programme title names both teams of a game today, the
  live info bar shows the score. The TV asks `GET /api/device/scores?league=nhl` (NHL, NBA, WNBA,
  NFL, MLB, MLS, Premier League, Champions League, LaLiga; `portal/src/scores.ts`), which reads ESPN's
  public scoreboard and keeps it a minute. Nothing about the TV or the channel is sent or stored.
- **Automatic server switch:** after 3 server-side stream failures in a row within 10 minutes (HTTP
  5xx, or a connection/DNS failure the portal's reachability check confirms), the TV moves the line
  to the first of its other servers that plays the same stream within 5 s, and says "Server not
  responding, switched to backup server." The choice stays on that TV; the first server is retried
  at app start and every 6 hours. The heartbeat's `serverSwitch` shows the last move (host names only).
- **Stream quality** (Settings): one choice each for Live TV, Movies and Series: Auto, Always prefer
  4K, HD, 1080p or 720p, kept on that TV. Movies and shows open the copy nearest that quality (after a
  part-watched copy and the family's language). Live TV starts HLS streams on the nearest picture
  size and plays the preferred version of a channel the line has in several qualities ("CTV HD",
  "CTV 4K"); Up/Down steps past the other versions. Missing quality:
  the nearest lower, then higher. Picture & sound in the player still changes it for one video.
- **Favourites:** hold OK (or press Menu) on any channel, movie or series to add or remove it; on a
  phone or tablet tap the heart. Live TV, Movies and Series each get a *Favourites* category at the
  top, and Home a *Favourites* row. Favourites survive channel reloads.
- **Categories** (next to Home): choose Live TV, Movies or Series, then one of its categories. Each
  section ends with *Adult Content*, which holds the adult categories. It asks for the profile's
  parental PIN, and once it's entered (there or under Settings' *Enter PIN*) it opens without asking
  until the app restarts; with no PIN on the profile it never asks. Live TV (and the player's guide)
  also shows adult live categories once unlocked, or always when the profile has no PIN. Movies,
  Series, search and Home never show adult categories.
  *Sports* gathers the live channels of every sports category (general sports, PPV and league lists)
  and sorts them by sport: hockey, football (NFL & CFL), soccer, basketball, baseball, cricket,
  tennis, golf, motorsport, fighting, rugby and more. A channel goes by the sport its own name names,
  else its category's; channels from general sports categories that name none go under More sports.
  Hidden and adult categories stay out, and the profile's category order is kept.
- **Movies and Series** open on *Highlights*: shelves of recently added, favourite and top-rated
  movies (and, for series, shows with new episodes). The provider's categories follow, each as a
  poster grid. On a TV a spotlight across the top shows the focused poster's artwork, year, rating
  and plot (for movies, the plot, genres and running time come from TMDB once focus rests, when the
  portal has a TMDB key). Each page remembers its category, scroll and last card when you switch
  pages.
- **One poster per title:** a show or movie the provider lists once per language ("EN - Dune",
  "FR - Dune", or in 4K beside HD) is one poster. A show's page has a Language choice; for a movie,
  OK plays the copy already part-watched, else the language last picked for that film (or any film,
  or show, then the device language), and holding OK lists "Play in English", "Play in French" and
  so on, remembering the pick. Same-titled films released years apart stay separate.
- **Watched marks:** hold OK (or Menu, or a long press on touch) on a movie to mark it watched or
  unwatched. On a show's page, the season button marks the whole season, and holding OK on an
  episode marks it, or every episode up to it. Marking clears the resume point.
- **New episodes:** every 6 hours (and a minute after the app opens, when due or just updated) the app looks at
  favourite shows (up to 60, one at a time). Every episode after the furthest one watched, plus any
  that arrived since the last look, counts as waiting: the show's cover gets a counter ("NEW", or
  "3 NEW") and the show joins a *New episodes* row on Home. It counts down as each one is watched
  (here or on Trakt) and goes away at zero. A show nobody has started counts only episodes that
  arrive after it was favourited. Arrivals also post a notification (on Android 13+ the app asks
  once for permission).
- Watched marks and resume points are shared by every TV in a profile through the portal (no account
  needed): finish an episode in the lounge and the bedroom TV shows it watched; stop a movie halfway and
  any TV in the profile resumes it. The later change wins.
- **Trakt (optional):** connect a Trakt account in Settings by scanning a QR that fills the code in (no typing on the
  remote). Favourite movies and series follow the Trakt watchlist both ways, and watched marks and
  resume points sync through Trakt scrobbling and history, so every device on the same Trakt account
  agrees. Every show in the Trakt history (and any with an episode in progress) is added to Favourites
  with its watched episodes and resume points; those stay on the device, and one removed here isn't
  added back. Titles are matched by id, else by title and year. Channel favourites stay on the
  device, since Trakt has no live TV.
- Live TV on a TV remote: OK shows the channel list; Back opens the full guide over the video, and
  Back again shrinks the video into a window at the top of the guide. OK on another channel plays it
  in that window, OK again goes full screen. A third Back opens the categories beside the guide
  (OK shows that category's channels; a channel picked there becomes the list Up/Down zaps through),
  and Back once more leaves live TV.
- Holding Back for about a second on any screen asks whether to exit Family TV (Cancel is selected).
- Captions, picture format, audio/video tracks and stream info in the player. Live TV: D-pad right opens Options;
  movies and episodes: press up from the player controls for the CC, picture & sound and info buttons;
  phones and tablets: tap the video. The remote's Info, Captions and Menu keys work too.
  - **Captions** come from the stream itself first (CEA-608 in live TV, subtitle tracks in movies,
    HLS subtitle renditions). On/off and the caption language are remembered.
  - **Online subtitles** for movies and episodes with none of their own come from OpenSubtitles.com,
    matched by the provider's TMDB/IMDb ids (series id + season + episode for episodes), falling back
    to title and year. Only shown when the portal has an `OPENSUBTITLES_API_KEY`.
  - **Picture & sound** sets the picture format (Original, Zoom to fill, Stretch, 16:9, 4:3; kept
    for every channel and video after it), and lists each audio track (language, codec, channels)
    and video quality (resolution, bitrate, Auto) when there's more than one.
  - **Stream info** shows what is really playing: codecs, resolution, frame rate (declared and
    rendered), declared and measured bitrate, HDR, audio channels and sample rate, stream type,
    dropped frames and buffer, updating every second.
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
  an optional parental PIN for Categories' Adult Content, and hidden categories. Saving a profile
  reloads every TV on it.
- **Lines** (with the 8K reseller panel connected): every line the portal knows, whether a profile
  uses it or not, with its expiry date and days left (red within a week) and which profiles use it;
  renew one for 1, 3, 6 or 12 months; your credit balance; **Add lines you already have** (paste
  their playlist links; the panel's API can't list lines, so each is added once and looked up on the
  panel first); and **New line**, which creates a line in a chosen package, for a profile or for
  later. Renew and New ask before spending credits. Lines are re-checked when the page opens if
  older than 6 hours. A profile's "Add a line" picker offers all of these lines.
- **Categories** (in each profile): drag (or use the arrow buttons) to arrange, and tick to show or
  hide, the Live TV, Movies and TV Shows categories the profile's TVs report. TVs list them in that
  order, the rest after. Each device can also arrange its own lists in Settings (Arrange categories);
  that order comes first on that device, and "Use the portal's order" undoes it.
- **Package sorter link** (per line, optional, set on the Lines page): the provider's page where that
  line's subscriber picks and orders their channels. Every profile using the line gets it, and the app
  shows it in Settings as a QR code and link (with an Open button on phones).
- **Suggestions** (in each TV's details): what the last check means for that TV and what to do: Ethernet
  or 5 GHz for weak or slow Wi-Fi, the host to allowlist on a home DNS filter, a one-click "use this
  server first" when a line's main server is down for everyone or much slower than its backup, and
  renew or screen-limit hints. For an ISP block it lists what was ruled out and names the ISP; it never
  suggests another server to get around a block.
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
npm run deploy                               # serves https://familytv.arshadkazi.ca (routes in wrangler.toml) and workers.dev
```
Keep `CONFIG_KEY` safe: losing it means re-entering the line passwords.

Optional, for online subtitles: create an API consumer at opensubtitles.com (Profile → API consumers)
and `npx wrangler secret put OPENSUBTITLES_API_KEY`. Paired TVs get the key with their config; without
it the TV only offers the stream's own captions. Without an OpenSubtitles login the key allows a small
number of downloads a day per IP; the TV says so when the limit is reached.

Optional, for trailers and richer episode pages: `npx wrangler secret put TMDB_API_KEY` (an API key or
read access token from themoviedb.org → Settings → API) and/or `npx wrangler secret put TVDB_API_KEY`
(a TheTVDB v4 project key). Paired TVs get them with their config. Trailers and episode details come
from the provider first, then TMDB, then TheTVDB; without the keys only the provider's own data is used.
Optional, for the Lines page: `npx wrangler secret put IPTV_PANEL_API_KEY` (the 8K Gold reseller
panel's API key; it stays in the Worker and is only sent to cms-8k.com), then `npm run db:migrate:remote`
and `npm run deploy`.

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
npx wrangler r2 object put family-tv-apps/family-tv.apk --file ../android/app/build/outputs/apk/release/app-release.apk
```

Installing:
- **Fire TV:** Settings → My Fire TV → Developer options → Install unknown apps → *Downloader*. Then type
  `<portal>/tv` into Downloader.
- **Google TV / Android TV:** the same idea with *Downloader* or *Send Files to TV*, after allowing
  unknown sources for that app.

### 3. Updates
Merging to `main` runs `.github/workflows/release.yml`: it builds a signed APK, uploads it with
`latest.json` to the `family-tv-apps` bucket, and every TV picks it up within 30 minutes.
- **Google TV / Android TV (Android 12+):** after the first self-update, updates install in the
  background while the app is closed.
- **Fire TV and older Android:** the app opens the installer when it's next on the home screen;
  someone presses Install once.
- Nothing installs while something is playing. **Update app** in the portal forces it now.

Secrets stay in Bitwarden Secrets Manager (EU), in a project of their own:
`FAMILYTV_KEYSTORE_B64`, `FAMILYTV_KEYSTORE_PASSWORD` and `CLOUDFLARE_API_TOKEN` (R2 edit on this
account). GitHub holds only `BWS_ACCESS_TOKEN`, for a machine account that can read that project.

### 4. Trakt sync (optional)
1. At <https://trakt.tv/oauth/applications/new> create an app: any name, **Redirect URI**
   `https://familytv.arshadkazi.ca/trakt` (Trakt refuses the old `urn:ietf:wg:oauth:2.0:oob`; if you
   change it, set `TRAKT_REDIRECT_URI` in `wrangler.toml` to match), no JavaScript (CORS) origins, and under Permissions tick
   */scrobble* (*/checkin* is not used).
2. Give the Worker the app's Client ID, then redeploy:
   ```bash
   cd portal
   npx wrangler secret put TRAKT_CLIENT_ID
   ```
   Apps created since October 2026 are PKCE-only and show "Not issued" for the Client Secret; the
   device-code sign-in works without one. An older app that has a secret can also set
   `TRAKT_CLIENT_SECRET`, which only this Worker ever sees.
The TV asks the portal (only once paired) to exchange and refresh its Trakt tokens, then calls Trakt
directly with its own token. Without `TRAKT_CLIENT_ID` the TVs hide
the Trakt section. No database migration is needed; Trakt tokens live on each device.

### 5. Real-Debrid
This app has no Real-Debrid (removed Oct 2026; a separate fork builds it). The portal keeps its
`/api/device/realdebrid/*` routes and `profile_debrid` table for that fork; nothing here calls them.

## Develop
| Where | Command |
|---|---|
| `portal/` | `npm run dev`, `npm run typecheck`, `npm test` |
| `android/` | `./gradlew testDebugUnitTest assembleDebug` (the debug build talks to `http://10.0.2.2:8787`, the emulator's view of a local `wrangler dev`) |

## Not in v1
Catch-up and recordings, multiple user profiles per TV, iOS/Apple TV, and an FFmpeg audio decoder for
boxes without Dolby passthrough (most TV sticks handle AC3/E-AC3 themselves).

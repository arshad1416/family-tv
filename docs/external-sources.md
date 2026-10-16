# External movie and series sources

In Search, type a title and choose **Find movies & series online**. This explicitly searches TMDB
using the existing configured metadata key. Select a movie, or a show followed by its actual TMDB
season and episode numbers. Torrentio is queried without any RD configuration, account key or bearer
header. The picker is manual; advertised 1080p/720p SDR AVC releases appear first, while other sources
require an additional compatibility confirmation. Release labels are not device capability detection,
an availability guarantee, a language guarantee or a claim that the torrent is cached.

In Settings → **External movie & series sources**, enter the account owner's Real-Debrid API key.
The input is masked and blank even when a key is configured. Save, replace and remove explicitly
affect all authorized devices in the same profile. Accounts must not be shared with other people.
Removal deletes the saved profile key; it does not revoke the underlying key at Real-Debrid or stop
an already playing direct stream. New preparation requests require the current profile revision.
The existing device-code sign-in and library-only Search shelf remain separate.

## Credential and policy boundaries

The new authenticated portal API checks the device's current profile and expected profile ID. A
monotonic revision and compare-and-swap writes prevent an older save from restoring a replaced or
removed key. Removal retains a revision tombstone. A 409 requires reloading status and re-entering
the key; Android never automatically retries the old write. The portal encrypts stored keys, and
reads are restricted to authorized devices in that profile. Keys are omitted from admin views,
routine configuration and telemetry. Android stores no new key on disk and uses a bounded,
cancellable client that rejects redirects and sanitizes errors. HTTPS is required except loopback
test fixtures. Never put the key in a URL or Torrentio configuration.

The portal's accompanying stored-XSS hardening, authenticated endpoints and migration are deployment
prerequisites. Review and deploy them together before entering a real key. Development does not
configure a real credential, deploy, install the app, or contact live media/account endpoints.

Protected profiles unlock the external window with the existing parental PIN. The grant lasts only
for that window, actual profile ID, device identity and profile policy. Before account calls and
playback, the app checks current portal profile, policy, key revision and key equality. Leaving the
screen or closing the dialog cancels pending work. TMDB adult titles are excluded; `adult=false`
does not certify an age rating. Known hidden/adult catalog identities are checked independently of
the typed SQL query, including punctuation/accent normalization and TMDB identity. Policy query
errors, more than 900 visible categories or more than 20,000 restricted identities per media kind
fail closed for external/RD-only results. Ordinary IPTV browsing is unaffected by these bounds.
Unclassified titles in the existing RD library shelf also require an explicit profile-bound PIN
unlock before appearing in protected profiles; leaving Search locks them again.

## Explicit account mutation and playback

Discovery performs no RD torrent mutations. Choosing Play sends exactly the chosen magnet hash to
RD, reads torrent information, and selects one verified video file using the returned RD ID.
Torrentio's `fileIdx` is zero-based, RD file IDs are one-based; missing/sparse/reordered indices are
not guessed. A missing index is accepted only when exactly one file matches. Episode names must
identify the requested show, season and episode; combined episodes, samples, archives, multiple
selected files or ambiguous links are rejected. The returned torrent hash must match the choice.

The app polls at most 12 times, waiting 2 seconds initially and 5 seconds thereafter. Preparation,
download and unavailable states are shown honestly. A rate limit applies a one-minute cooldown;
mutations are not automatically replayed. Once one matching selected file has one secure link, the
app calls RD unrestrict and gives its HTTPS download URL directly to the existing player. Cancel
stops local waiting and further requests; a torrent already added may remain in the account. The app
does not delete account data automatically.

External playback uses `tmdb/movie/<id>` or `tmdb/tv/<id>` identity with explicit TMDB/IMDb fields,
plus season and episode. The existing progress database, portal progress and Trakt tracker can
therefore resume across source changes without treating a hash or download URL as the title ID.

## Contract references and tests

- [Torrentio addon route and stream objects](https://github.com/TheBeastLT/torrentio-scraper/blob/master/addon/addon.js)
- [Torrentio RD file mapping](https://github.com/TheBeastLT/torrentio-scraper/blob/master/addon/moch/realdebrid.js)
- [Real-Debrid API: addMagnet, info, selectFiles, unrestrict](https://api.real-debrid.com/)
- [TMDB multi search](https://developer.themoviedb.org/reference/search-multi)
- [TMDB show external IDs](https://developer.themoviedb.org/reference/tv-series-external-ids)

The JUnit fixtures use synthetic JSON, account keys and loopback MockWebServer responses. They cover
credentialless requests, redirect isolation, size bounds, 429 cooldown, cancellation, source ranking,
malformed index/hash, exact episode/file/link selection, finite polling, profile/policy changes,
revision/tombstone conflicts, late revocation and normalized hidden catalog identities. No tests
use live credentials or media. Run with already-installed tooling only:

```sh
./gradlew --offline --no-daemon testDebugUnitTest assembleDebug
./gradlew --offline --no-daemon lintDebug
```

TV remote layout, real decoding and live service availability still require an owner-controlled
device check. Offline lint may be blocked when instrumentation lint artifacts are not cached;
missing dependencies are reported rather than installed.

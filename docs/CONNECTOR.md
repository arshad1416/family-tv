# External source connector (protocol 1)

A versioned Android service contract that lets a separately installed, approved companion app list
sources for a movie or episode and resolve the one the viewer picks. Family TV keeps its own screens,
release picker, player, parental controls, resume progress and Trakt; the companion only supplies
release metadata and a playback handoff.

Each part below is marked **IMPLEMENTED** (in this repo, with tests) or **PROPOSED** (agreed, not built).

## Add-ons and the connector are two different things

- **The add-on** ([ADDONS.md](../ADDONS.md)) answers *may this TV use it now?* The portal says so per
  profile: the add-on is bought, the membership hasn't ended, and the portal answered this TV in the
  last 7 days. When the membership ends, the add-on pauses and stays owned, and a renewal turns it
  back on without paying for it again.
- **The connector** (this file) answers *how does the app talk to the companion?* It is a binder
  interface between two apps on the TV, and the host only uses it while the provider's add-on is on.

Every approved provider has its own add-on id.

## Status

| Part | Status |
|---|---|
| AIDL interfaces `ISourceProvider`, `ISourceCallback` ([android/app/src/main/aidl/app/familytv/connector/v1](../android/app/src/main/aidl/app/familytv/connector/v1)) | IMPLEMENTED |
| Bundle formats, caps and typed errors (`connector/Protocol.kt`) | IMPLEMENTED |
| Service discovery: intent action and protocol meta-data | IMPLEMENTED |
| Host adapter: allowlist, certificate pin, add-on gate, bind, negotiate, list, resolve, cancel, close, timeouts (`connector/SourceConnector.kt`) | IMPLEMENTED |
| Allowlist pinned by package and signing certificate, empty in release builds (`connector/ApprovedProviders.kt`) | IMPLEMENTED |
| Debug-only reference companion with synthetic public-domain data (`src/debug/.../ReferenceSourceProvider.kt`) | IMPLEMENTED |
| Contract tests (`ConnectorContractTest`, `SourceConnectorTest`) run in the Android CI job | IMPLEMENTED |
| Source picker screen and handing the resolved source to the player | PROPOSED |
| Rust host: its Java shell binds the same AIDL and bridges to Rust over JNI | PROPOSED |
| Contract test in the Rust host's CI | PROPOSED |
| Portal-signed entitlement token in `hello`, so a provider can check the add-on with the portal itself | PROPOSED |
| An approved provider | None yet |

## Approved providers

None. Only providers that are licensed, or that serve the household's own media (Jellyfin, Plex and
the like), can be approved, and each one needs Arshad's approval. RD+/Torrentio is not an approved
provider. Release builds bind nothing until an entry is added to `ApprovedProviders.RELEASE` in code
(never from config), pinning the package name and the SHA-256 of its single signing certificate.

## Discovery

The companion declares an exported service:

```xml
<service android:name=".FamilyTvSourceService" android:exported="true">
    <intent-filter>
        <action android:name="app.familytv.connector.SOURCE_PROVIDER_V1" />
    </intent-filter>
    <meta-data android:name="app.familytv.connector.PROTOCOL_MIN" android:value="1" />
    <meta-data android:name="app.familytv.connector.PROTOCOL_MAX" android:value="1" />
</service>
```

The host lists services for that action. It considers one only if:

- its package is in the allowlist;
- the package has exactly one signing certificate, and it is the pinned one;
- the service's protocol range includes 1;
- the provider's add-on is on.

The host checks the certificate again after binding, because the package could have been replaced in
between. Family TV declares the action under `<queries>`, so Android 11 and later let it see companions.

## Interface

[`ISourceProvider.aidl`](../android/app/src/main/aidl/app/familytv/connector/v1/ISourceProvider.aidl)
and [`ISourceCallback.aidl`](../android/app/src/main/aidl/app/familytv/connector/v1/ISourceCallback.aidl)
are the contract. Copy them unchanged into the companion, in package `app.familytv.connector.v1`.

| Transaction | Method | Answer on the callback |
|---|---|---|
| 1 | `negotiate(requestId, hello, callback)` | `onResult`: protocol, capabilities, providerName |
| 2 | `listSources(requestId, sessionId, identity, callback)` | `onResult`: sources |
| 3 | `resolve(requestId, sessionId, selectionId, callback)` | `onResult`: uri, mimeType, headers, expiresAtMs |
| 4 | `cancel(requestId)` | none required; `onError(CANCELLED)` is allowed |
| 5 | `closeSession(sessionId)` | none |

How the calls behave:

- **Calls are one-way.** The provider answers each request exactly once, through `onResult` or
  `onError`, with the same `requestId`. It answers from its own worker thread, never from the binder
  thread.
- **The host makes the ids.** `requestId` and `sessionId` are opaque. Every id, `selectionId`
  included, is 1 to 128 characters from `A-Z a-z 0-9 . _ ~ : -`.
- **A session belongs to one title.** The host opens it with `listSources` and ends it with
  `closeSession`. A `selectionId` only works in the session that listed it, and the host refuses to
  send any other.
- **Time limits.** The host waits 5 s to bind, 5 s for `negotiate` and 20 s each for `listSources` and
  `resolve`. When the time runs out, or the viewer leaves first, the host sends `cancel` and ignores
  any late answer.
- **Where answers arrive.** The host's suspend functions resume on the caller's dispatcher, so a
  screen that calls them from the main thread gets its answer there.

### Bundles

Bundles carry only strings, ints, longs, string lists and nested Bundles, never custom Parcelables.
Unknown keys are ignored in both directions. Where the host gets text that is too long, it trims it.
Where it gets an invalid entry, it drops it.

**hello** (host to provider)

| Key | Type | |
|---|---|---|
| `protocolMin`, `protocolMax` | int | 1 and 1 |
| `hostVersion` | String | Family TV's version name |
| `capabilities` | ArrayList&lt;String&gt; | empty in protocol 1 |

**negotiate result**

| Key | Type | |
|---|---|---|
| `protocol` | int | must be a version the host offered (1) |
| `capabilities` | ArrayList&lt;String&gt; | up to 32 strings of up to 64 characters; protocol 1 defines none |
| `providerName` | String | up to 60 characters |

**identity**: the title the host has already matched.

| Key | Type | |
|---|---|---|
| `type` | String | `movie` or `episode` |
| `tmdb`, `tvdb` | String | digits, optional |
| `imdb` | String | `tt` followed by digits, optional |
| `season`, `episode` | int | episodes only; season ≥ 0, episode ≥ 1 |

At least one of `tmdb`, `imdb` or `tvdb` is set.

**listSources result**: `sources` is an ArrayList&lt;Bundle&gt; of at most 100 entries. Each entry has:

| Key | Type | Cap |
|---|---|---|
| `selectionId` | String | id rules above; required |
| `title` | String | 200; required |
| `quality` | String | 20 (for example `1080p`) |
| `sizeBytes` | long | ≥ 0 |
| `audio` | String | 60 |
| `language` | String | 35 (BCP 47) |

**resolve result**

| Key | Type | |
|---|---|---|
| `uri` | String | `https` or `http` only, up to 4096 characters, no whitespace |
| `mimeType` | String | optional, up to 100 characters |
| `headers` | Bundle of String to String | optional, at most 10; names are HTTP tokens of up to 64 characters, values up to 1024 characters with no line breaks |
| `expiresAtMs` | long | optional, wall-clock ms |

### Errors

`onError(requestId, code, detail)`. The host never shows or logs `detail`.

| Code | Name | When |
|---|---|---|
| 1 | UNSUPPORTED_VERSION | no common protocol version |
| 2 | NOT_ENTITLED | the provider's own account or entitlement refuses |
| 3 | NOT_FOUND | no sources for this identity, or an unknown selection |
| 4 | TIMEOUT | the provider's own upstream timed out |
| 5 | PROVIDER_ERROR | anything else; also how the host reads an unknown code or a malformed answer |
| 6 | CANCELLED | the request was cancelled |

## Security

**What the host does** (IMPLEMENTED):

- It binds only allowlisted packages that have the pinned certificate.
- It runs the gate before listing a provider, before each `listSources`, and again before each
  `resolve`, so right before the player gets the source. The gate checks the provider's add-on
  (`Addons.isActive`: bought, membership current, portal heard from within 7 days) and the screen's
  own profile and parental-PIN checks.
- It never logs credentials, tokens or resolved uris; `ResolvedSource.toString()` leaves them out.
- It sends the provider nothing about the viewer except the title identity.

**What the provider must do** (PROPOSED for each companion):

- Check the caller on every call: `Binder.getCallingUid()` must belong to package `app.familytv` and
  be signed with Family TV's release certificate. Arshad gives that SHA-256 to an approved provider.
- Keep its own credentials to itself. The handoff contains only what the player needs, and a resolved
  uri should expire soon after it is used.
- Answer every request exactly once and off the binder thread.
- Honour `cancel` and `closeSession`.

## Compatibility

Once released, protocol 1 does not change:

- Method numbers, parameter meanings and Bundle keys stay as they are, enforced by
  `ConnectorContractTest.theV1InterfacesAreFrozen`.
- A new method takes the next free number.
- A new optional feature is a capability that both sides list in `negotiate`.
- An incompatible change is protocol 2, with a new package (`...connector.v2`) and a new action.
- The host refuses versions it doesn't speak: a provider that answers with any version but 1 gets no
  calls.
- Screen and player changes stay behind `SourceConnector`, so they never touch the contract.
- With no companion installed, an incompatible one, or a provider that hangs or dies, the rest of the
  app works exactly as before, including IPTV playback.

## Synthetic example

This is the debug reference companion's exchange. The film is the public-domain "Night of the Living
Dead" (1968); the releases and uris are made up. It is shown as JSON for reading; on the wire each
object is a Bundle.

```jsonc
// negotiate(requestId = "3f0c…", hello)
{ "protocolMin": 1, "protocolMax": 1, "hostVersion": "0.2.73", "capabilities": [] }
// onResult("3f0c…")
{ "protocol": 1, "capabilities": [], "providerName": "Reference (test data)" }

// listSources(requestId = "9a41…", sessionId = "c2d7…", identity)
{ "type": "movie", "tmdb": "10331", "imdb": "tt0063350" }
// onResult("9a41…")
{ "sources": [
  { "selectionId": "ref-1080", "title": "Night of the Living Dead (1968), reference test data", "quality": "1080p", "sizeBytes": 2147483648, "audio": "AAC 2.0", "language": "en" },
  { "selectionId": "ref-720",  "title": "Night of the Living Dead (1968), reference test data", "quality": "720p",  "sizeBytes": 1073741824, "audio": "AAC 2.0", "language": "en" },
  { "selectionId": "ref-480",  "title": "Night of the Living Dead (1968), reference test data", "quality": "480p",  "sizeBytes": 524288000,  "audio": "MP3 2.0", "language": "en" }
] }

// resolve(requestId = "51be…", sessionId = "c2d7…", selectionId = "ref-720")
// onResult("51be…")
{ "uri": "https://example.invalid/reference/ref-720.mp4", "mimeType": "video/mp4", "headers": {}, "expiresAtMs": 1792000000000 }

// listSources for any other title
// onError(requestId, 3 /* NOT_FOUND */, null)

// closeSession("c2d7…")
```

In debug builds the reference companion runs in its own process (`:connector_reference`), so calls to
it cross a real binder. Its allowlist entry pins "signed with the same key as the host", and it
answers to the add-on id `connector-reference`.

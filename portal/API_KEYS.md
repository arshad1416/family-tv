# Profile Real-Debrid API keys

This opt-in setting is for an account owner's own devices in one portal profile. It does not
authorize sharing an account with other people. Torrentio discovery remains credentialless;
the Real-Debrid key must never be sent to Torrentio, placed in URLs, or included in logs.

Apply migration `0007_profile_rd_api_keys.sql` before enabling these endpoints. This change
also includes the guide-telemetry renderer fix: deploying key synchronization without that
fix leaves device-controlled stored markup able to execute as the portal administrator.
No migration or deployment is performed by the local test workflow.

Every endpoint requires a paired device bearer token and an expected `profileId` matching
that device's current profile. Ordinary administrator tokens cannot read keys. Database
statements check current device membership/token together with credential access.

| Method | Route/body | Result |
| --- | --- | --- |
| GET | `/api/device/realdebrid/api-key?profileId=<id>` | `{profileId, revision, configured, apiKey}` |
| PUT | `/api/device/realdebrid/api-key`, JSON `{profileId, revision, apiKey}` | `{profileId, revision, configured}` |
| DELETE | Same route, JSON `{profileId, revision}` | `{profileId, revision, configured}` |

Responses use `Cache-Control: no-store`. Only GET includes the key (or null). Routine config,
admin views, heartbeats and commands do not include it. API keys accept 16–512 printable
non-space ASCII characters; saving stores an encrypted profile-bound envelope without
calling Real-Debrid to validate the credential.

The endpoint rejects HTTP with 426 before reading or changing credentials. Clients must also
reject non-HTTPS portal URLs before sending credentials: a server rejection cannot protect a
key already transmitted over cleartext. HTTP mock clients require an explicit loopback-only
test seam, and are not a production configuration.

An absent key starts at revision zero. Replace/remove must supply the latest revision;
success increments it. Removal keeps a tombstone. A stale change returns 409; wrong or
changed profile membership returns 403. Clients must reload after conflict, discard responses
from an old profile/session, and never blindly retry a stale replace/remove. They must protect
local storage, mask input, and refresh before using a locally cached key. A remote removal is
observed at the next authenticated sync; the portal cannot erase a copy held by an offline
device or revoke the API key at Real-Debrid itself. Removing this setting does not change
the separate legacy OAuth account connection.

## Offline verification

The normal suite retains `rd-api-key.test.ts` and `guide-security.test.ts` for Vitest. If its
native dependencies are unavailable, Node 22's built-in test runner can exercise the actual
TypeScript-compiled production Worker with the real migrations on SQLite:

```sh
# From portal/, using already installed dependencies; select an installed Node 22 runtime.
node node_modules/typescript/bin/tsc --noEmit
node node_modules/typescript/bin/tsc --noEmit false --outDir ../../validation/portal-compiled
node test/prepare-security-node.mjs ../../validation/portal-compiled
PORTAL_COMPILED_DIR="$(cd ../../validation/portal-compiled && pwd)" node --test test/security.node.mjs
```

The preparation script changes import extensions only in disposable compiler output. The
13 focused tests use synthetic keys, production handlers, migration-backed SQLite and the
actual shipped guide renderer. They do not replace a full Vitest run or a deployed D1 check.

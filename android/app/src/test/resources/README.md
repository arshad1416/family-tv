`portal-rd-contract.json` was generated on 2026-10-16 from the actual portal Worker
at commit c575f2e, with its real migrations applied to Node's in-memory SQLite D1
adapter (`portal/test/d1.ts`). It contains only synthetic profile/device values,
metadata credentials, and an API key that cannot access a real account. No provider
or deployed service was contacted.

The generator is `android/tools/generate-portal-rd-fixture.mjs`. It accepts a
Node-compatible compiled portal tree containing `src/index.js`, `src/crypto.js`,
`test/d1.js`, and the portal migrations in the layout expected by the D1 adapter.
Using Node 22 or newer with built-in SQLite:

```
node android/tools/generate-portal-rd-fixture.mjs /path/to/compiled-portal
```

Its default output is this fixture; an optional second argument chooses another
output for comparison. Review fixture changes alongside portal contract changes.
The generator calls the real Worker handlers directly, verifies each response,
and never makes network requests.

From `android/`, `./gradlew --no-daemon verifyReleaseRdModel` runs actual release
Kotlin response classes through the installed AGP R8 shrinker, then deserializes
these responses with real Gson. The `assembleRelease` task depends on this check, so existing CI and release
publication builds cannot bypass it.
`PortalDiscoveryFlowTest` separately exercises the production PortalClient and
profile session factory against these responses through a local mock server,
including save/read/remove and isolated metadata search.

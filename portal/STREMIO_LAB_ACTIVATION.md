# Additive Stremio Lab routes on the existing portal

This patch belongs only to the existing Family TV repository. Keep Worker
`family-tv-portal`, D1 `family_tv`, APPS bucket `family-tv-apps`, domain
`https://familytv.arshadkazi.ca`, registration/pairing/schema, legacy keys, routes,
`latestApp` behavior and original workflows unchanged. No resource provisioning or
migration is needed. The Stremio fork contains no portal source/config/deploy scripts.

GET/HEAD `/stremio-lab/latest.json` and
`/stremio-lab/builds/<positive-code>/app.apk` call the existing requireDevice and
require nonnull profile assignment before APPS reads. Normal shared-portal pairing can
assign an existing or new profile. No device tokens, profiles or provider credentials
are silently copied/imported.

GET/HEAD/PUT `/api/stremio-lab/publish/latest.json` and
`/api/stremio-lab/publish/builds/<positive-code>/app.apk` accept only the optional
independent secret `STREMIO_LAB_PUBLISH_TOKEN`. Admin/device tokens cannot substitute.
Unset/missing/wrong token fails before storage. The exact canonical HTTPS host and path
whitelist exclude queries, encoded aliases, arbitrary keys and bucket selection.
All internal writes are fixed to `stremio-lab/*` in APPS. The fork receives only the
namespace publisher capability, never Cloudflare account/bucket credentials or shared
admin/config/provider secrets.

APK PUT requires Content-Length (positive, at most 64 MiB) and X-Content-SHA256
(lowercase hex). Native FixedLengthStream bounds streaming length; R2 verifies SHA-256
and absent-only creation. No full APK buffer is created. Feed PUT is bounded to 64 KiB,
checks exactly package/channel/versionName/code/URL/hash/size, matches the existing APK's
R2 checksum/length, requires a valid older current feed and uses etag compare-and-swap
(or absent-only creation). Failed conditions return conflict without overwriting.
No multi-object transaction exists; an unsuccessful publication may leave an orphan
immutable APK, and a lost feed response requires a read before deciding outcome.

## Activation approval and verification

Deployment and persistent secret setup require current operator approval. Integrate only
the additive patch into the **latest reviewed shared-portal head** before deployment;
do not deploy a stale full checkout over newer work. Existing portal workflows remain
unchanged. No new Worker, D1, bucket, domain, registration identity or migration is needed.

The independent publisher token's custody, private release-environment setup, stable
independent application signer, encrypted backup and installation/pairing approvals are
documented separately in the private fork's operator handoff. Do not place those private
custody records or credential values in this public repository.

Keep releases disabled until the additive routes and namespace secret are configured
after approval, authenticated delivery is verified, and separately approved signed
Android upgrades pass. Verify native Worker request limits, FixedLengthStream behavior,
R2 SHA-256/checksum metadata and conditional writes before enabling releases. Offline
Node tests use a documented stream/R2 stand-in and do not establish deployed behavior.

The shared origin is fixed; no new domain is required. The Lab package
`app.familytv.stremio` and channel `stremio-lab` remain separate. Original
0.1.53/0.1.57 packages cannot migrate in place. The app checks in while active
(about 30 seconds; feed cache five minutes), waits during playback unless forced, and
waits while hidden unless it considers itself silently eligible. Android unknown-source
permission/installation confirmation may still be needed, including when installer-of-record
heuristics consider it eligible on API31+. No immediate background push, silent guarantee,
device-owner enrollment, accessibility service or consent bypass is provided.

## Offline verification

Use existing pinned dependencies and an independent node_modules directory, never a
symlink into another checkout. Do not run live provider fixtures. Run every test under
an OS network boundary that denies outbound traffic except loopback, using a minimal
environment. Cargo offline flags alone are not an OS network boundary.

From this portal directory, inside the required offline environment with installed Node22:

```sh
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vitest/vitest.mjs run --pool=forks --poolOptions.forks.singleFork
```

The private fork also provides a portable native Miniflare/workerd harness that bundles
this repository's source from an exact reviewed commit. It uses temporary local R2 fixtures,
disables Miniflare metadata probes and bounds execution. It must run inside the same OS
network boundary; it needs cached platform-compatible runtime binaries. No live bindings
or deployment credentials are used. A passing local native smoke still does not replace
deployed authenticated delivery and signed Android upgrade checks.

References:
[Workers best practices](https://developers.cloudflare.com/workers/best-practices/workers-best-practices/),
[R2 checksums and conditionals](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/),
[FixedLengthStream](https://developers.cloudflare.com/workers/runtime-apis/streams/transformstream/).

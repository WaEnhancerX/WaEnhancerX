# WAEX community visual catalog — isolated API prototype

**Status: implemented and exercised locally, NOT deployed, integrated into WAEX, or
verified inside WhatsApp.** All test files are synthetic. No TISU APK contents,
third-party code, registered WhatsApp data, or user submissions are included.

This self-contained Python 3.12 standard-library service models the community
catalog requested for themes, static wallpapers, and animated wallpapers, so
new uploads and ratings can appear without a WAEX app update. The current
prototype listens on loopback **only** and requires explicitly provisioned
local development bearer principals for writes. There is **no production login,
moderation team, hosting, storage/CDN, or public API**.

## Run and verify

```bash
cd tools/community_catalog_backend
python3 -m unittest discover -s tests -v
python3 -m py_compile waex_catalog/server.py tests/test_server.py
```

For a disposable manual trial, provide development identities through the
environment; use **independent randomly generated tokens** (at least 16
visible ASCII bytes) outside version control. Do not use this local-token
mechanism for a public service.

```bash
export WAEX_CATALOG_DEV_TOKENS_JSON='{"REPLACE_WITH_A_UNIQUE_RANDOM_SECRET":{"user_id":"demo_001","display_name":"Local demo","moderator":true}}'
python3 -m waex_catalog.server --db /tmp/waex-catalog-demo/catalog.sqlite3 --blobs /tmp/waex-catalog-demo/blobs --port 8765
curl -fsS http://127.0.0.1:8765/healthz
```

Nothing needs to run persistently for the test suite; its HTTP servers bind
ephemeral loopback ports and are shut down after every case. The CLI example
above runs only until explicitly interrupted.

## API v1 contract

All responses are JSON except the binary asset route. `schema_version: 1` is
the catalog response schema, **not** proof that WAEX's host hook can apply the
asset. All UUID identifiers are service-generated; clients must construct
same-origin paths, refuse cross-origin redirects and independently verify the
downloaded byte count and SHA-256 before previewing/importing.

| Route | Access | Behavior |
|---|---|---|
| `GET /healthz` | Public on loopback | Liveness only, no production readiness claim |
| `GET /v1/packs?type=theme\|wallpaper_static\|wallpaper_animated&sort=newest\|oldest\|popular\|rating&page_size=20&pageToken=...` | Public | Approved packs only; deterministic order, signed opaque cursor |
| `GET /v1/packs/{uuid}` | Public | Approved metadata; `declarative_manifest` only for themes |
| `GET /v1/packs/{uuid}/file` | Public | Bytes, exact MIME and length, `ETag`, `X-WAEX-SHA256`, `Digest`; no-store to permit takedown |
| `POST /v1/packs` | Bearer user | Validate bytes/hash/type/license/targets; new item starts **pending** |
| `GET /v1/me/packs` | Bearer user | First 100 of the account's own items, including pending/rejected/withdrawn status |
| `POST /v1/packs/{uuid}/ratings` | Bearer user | `{ "rating": 1..5 }`; one score per account, update existing, no self-rating |
| `POST /v1/packs/{uuid}/reports` | Bearer user | `{ "reason": "copyright"\|"spam"\|"unsafe"\|"misleading"\|"other" }`, upsert one report per account |
| `DELETE /v1/packs/{uuid}` | Owner/moderator | Soft withdrawal; no public detail/download after withdrawal |
| `GET /v1/moderation` | Bearer moderator | First 100 pending items |
| `GET /v1/moderation/{uuid}/file` | Bearer moderator | SHA-checked private preview bytes for review of pending content |
| `GET /v1/moderation/reports` | Bearer moderator | First 100 approved reported items with aggregate reasons, no reporter identities |
| `POST /v1/moderation/{uuid}` | Bearer moderator | `{ "decision": "approve"\|"reject" }`, pending only, audited |

`GET /v1/packs` returns:

```json
{
  "schema_version": 1,
  "items": [
    {
      "id": "00000000-0000-4000-8000-000000000000",
      "type": "theme",
      "title": "Example (synthetic)",
      "description": "Declarative colors",
      "author": "Local demo",
      "target_packages": ["com.whatsapp", "com.whatsapp.w4b"],
      "license": "CC0-1.0",
      "media_type": "application/vnd.waex.theme+json",
      "size_bytes": 99,
      "sha256": "<client-computed-64-lowercase-hex>",
      "created_at": "2026-11-18T00:00:00.000Z",
      "downloads": 0,
      "rating_average": null,
      "rating_count": 0,
      "download_url": "/v1/packs/00000000-0000-4000-8000-000000000000/file"
    }
  ],
  "next_page_token": null,
  "total": 1
}
```

The above is an illustrative **schema**, not a claim of a real published
pack, timestamp, ID, hash, or remote endpoint. Public metadata never includes
the uploader's bearer token or private internal account identifier. Upload
success returns the same fields with `moderation_state: "pending"`; it does not
make the item public. Pending/rejected/withdrawn items return 404 to public
detail and file requests.

### Upload contract

`POST /v1/packs` requires exactly:

```json
{
  "type": "theme",
  "title": "My synthetic theme",
  "description": "An original color palette",
  "target_packages": ["com.whatsapp"],
  "license": "CC0-1.0",
  "rights_attested": true,
  "media_type": "application/vnd.waex.theme+json",
  "size_bytes": 99,
  "sha256": "<sha256-of-decoded-content>",
  "content_base64": "<base64-of-decoded-content>"
}
```

- `target_packages`: allowlisted official package identifiers only;
  Android user/profile IDs are **not** package names. Compatibility is still
  `NOT_VERIFIED` per WhatsApp version and hook.
- `license`: one of `CC0-1.0`, `CC-BY-4.0`, `CC-BY-SA-4.0`; uploader must
  attest rights. **Attestation is not verification of copyright ownership**.
- `type=theme`: MIME `application/vnd.waex.theme+json`, max 256 KiB; UTF-8
  JSON with exactly two top-level keys. Safe declarative schema:

```json
{
  "schema_version": 1,
  "colors": {
    "primary": "#112233",
    "background": "#000000",
    "text": "#FFFFFF",
    "secondary": "#445566",
    "surface": "#778899",
    "accent": "#AABBCC"
  }
}
```

  Required: `primary`, `background`, `text`; remaining colors optional.
  Colors must be exactly `#RRGGBB`. No arbitrary CSS, JavaScript, external
  URLs, paths, fonts, scripts, or ZIPs may be published in this v1 format.
  WAEX must explicitly convert the allowlisted palette to a reviewed local
  theme contract before attempting to apply it. The existing local ZIP/CSS
  theme archive is **not** implicitly trusted for remote submissions.
- `type=wallpaper_static`: PNG, JPEG, WebP with matching declared MIME.
- `type=wallpaper_animated`: GIF or MP4 with matching declared MIME. **Media
  header sniffing is not a full decode, safety scan, animation proof, or host
  support test**. WhatsApp/WAEX animated wallpaper application is presently
  `NOT_VERIFIED` and clients must not present it as working by default.
- Payload cap 16 MiB, JSON request cap 23 MiB. Exactly supplied SHA-256/size
  must match the actual decoded bytes, with strict base64 and no executable
  content. For a public service, replace JSON base64 with resumable object
  storage uploads, content processing and full media validators.

### Sorting, pagination and ratings

- `newest`: creation time DESC + ID DESC.
- `oldest`: creation time ASC + ID ASC.
- `popular`: **number of distinct authenticated rating accounts** DESC,
  followed by mean rating DESC, creation time DESC, ID DESC. Anonymous
  downloads are counted for diagnostics but **cannot influence popularity**.
  This is more resistant to trivial download-refresh manipulation, but real
  identity verification and anti-sybil controls are still missing.
- `rating`: mean 1–5 DESC (unrated last), rating count DESC, creation time
  DESC, ID DESC. `rating_average=null` denotes no ratings.
- `page_size`: 1–50; opaque HMAC-signed `pageToken` expires after 15 minutes
  and binds type, sort, size, offset, and a **server-side frozen ordered list
  of IDs**. Ratings and downloads changing between page1/page2 cannot produce
  duplicate/missing IDs under the original snapshot order. New approvals are
  absent from existing snapshots; newly withdrawn items are skipped for safety.
  Published counters/averages in a later page are current values, while
  **their ranking remains the first page's snapshot**.
- Snapshot memory is explicitly bounded: at most 64 active snapshots and
  10,000 IDs per snapshot; exceeding the bound produces `SNAPSHOT_LIMIT` or
  `EXPIRED_CURSOR` and clients must restart pagination. Process restart
  invalidates in-memory snapshots, even with the same cursor signing secret.
  Public production use needs durable/resumable distributed pagination.
- Users must authenticate to rate; `ratings` has a unique `(pack_id,user_id)`
  constraint. New score replaces the previous user's score and adjusts the
  aggregate without inflating count. Uploader cannot self-rate.

## Safety and verification limits

The service deliberately has no TISU wallpapers, TISU themes, or third-party
artwork bundled. The unrelated GPLv3 fork `igorcv88/WaEnhancerXCommunity`
was mentioned during parallel source review; **no code/assets from that fork
or TISU were copied here**, and its licensing must not be conflated with the
upstream WAEX repository.

Local controls: SQLite WAL/transactions, content-addressed SHA-256 blob names,
atomic file replacement, idempotent blob storage, strict manifest field/type
validation, moderated approval gate, ownership-aware soft withdrawal, audit
rows for moderator decisions, report intake, per-user in-process action rate
limiting (including per-loopback-address downloads), quotas, signed cursors,
strict duplicate-JSON-key rejection, no token/request-body logging, no CORS,
no-store responses, loopback-only bind, and no default credentials.

The file handler checks `state='approved'` again **after** reading and hashing
the bytes, in the same transaction as its download counter update. If a
takedown happens between initial metadata read and that check, download fails
with HTTP 404 and never emits the bytes (tested by adversarial injection).
There remains a small time-of-check-to-time-of-send window if takedown commits
after that final authorization; preventing that requires production file
leases/redirect revocation semantics, not holding a SQLite write lock through
a network transfer. Responses are deliberately `Cache-Control: no-store`.

`ThreadingHTTPServer` is **one thread per connection** and each download is
fully loaded into memory up to 16 MiB. This is acceptable only for isolated
tests, not internet-scale performance or DDoS safety. The media validators
check file signatures but do not decode all frames or prove video playback:
**NOT_MEDIA_DECODE_VERIFIED**.

These controls do **not** make it a production-ready public gallery. Required
before public availability:

1. Identity integration (OIDC or equivalent) with verified account-to-token
   binding, revocation, anti-sybil protections and no use of WhatsApp accounts.
2. Hosting, TLS, WAF/DDoS limits, quotas across processes/regions, resilient
   object storage, CDN/takedown policy, operational monitoring and cost limits.
3. Full media transcoding/sandboxed validation and content-moderation tooling;
   copyright notice/counter-notice, anti-abuse response, community rules,
   accessibility and license verification.
4. Product-level Android compatibility: safe theme palette conversion,
   animated GIF/MP4 preview/apply hooks, per-package preferences, persistence,
   OFF/ON/restart/rollback, verified permissions and managed profiles. No
   host-owned WhatsApp database edits without backup and causal tests.
5. Secure client networking: allowlisted HTTPS origin, no redirects to other
   hosts, request timeout/cancellation, streaming size cap, hash verification,
   no remote script execution, safe caching/offline behavior and explicit
   unsupported-version diagnostics.
6. Consistent backup/restore of BOTH SQLite metadata (with WAL) and blobs;
   test restoration, reconcile orphan files, enforce retention/erasure and
   moderation/legal holds. The current prototype has no backup automation.
7. Production observability and per-version acceptance testing with an
   actual WAEX build plus actual WhatsApp/Business account where authorized.

Test outcomes are confined to Python HTTP/SQLite behavior. They do **not**
verify Android injection, WhatsApp's wallpaper pipeline, rights in TISU
content, remote identity, internet-scale service availability, or user-visible
installation and application of any submitted pack.

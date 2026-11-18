# Community catalog client foundation (experimental)

Scope: WAEX manager only. It performs **read-only discovery and verified private downloads**
of declarative theme palettes, static wallpapers and animated-media packages. It never
loads executable code, changes WhatsApp databases, activates a theme, or injects an
unverified conversation renderer. The user-facing gallery and publication account flow
are **not implemented by this slice**; do not advertise it as a finished feature.

## Contract v1

- `GET /v1/packs?type=theme|wallpaper_static|wallpaper_animated&sort=newest|oldest|popular|rating&page_size=20&pageToken=...`
  returns JSON `{ "schema_version": 1, "items": [...], "next_page_token": null | string }`.
- Each item has canonical lowercase UUID `id`; `type`, `title`, `description`,
  `target_packages`, `author`, `license`, `media_type`, `size_bytes`, lowercase
  SHA-256 `sha256`, UTC `created_at`, `downloads`, `rating_average` (null if
  unrated), and `rating_count`. `download_url` is intentionally ignored.
- `GET /v1/packs/{id}/file` is derived from the configured **HTTPS origin**, never
  followed as an arbitrary URL returned by untrusted catalog data. Redirects are
  disabled even if a caller injects a different OkHttp client, and the returned
  request URL is checked against the original. A non-200 response, wrong MIME,
  length, checksum, leading format signature, excessive size or invalid metadata
  rejects the download. Leading signatures are NOT a complete media decoder validation.
  The request asks for identity encoding, checks `X-WAEX-SHA256` when present,
  and independently hashes every returned byte.
- `theme`: `application/vnd.waex.theme+json`, maximum 256 KiB, UTF-8 JSON
  `{ "schema_version": 1, "colors": { "primary":"#RRGGBB", "background":"#RRGGBB", "text":"#RRGGBB", "secondary":"#RRGGBB", "surface":"#RRGGBB", "accent":"#RRGGBB" } }`.
  First three required, remaining optional. No extra top-level or color keys.
  These palettes are *stored data*, not CSS executed in WhatsApp.
- `wallpaper_static`: PNG/JPEG/WebP, maximum 16 MiB; `wallpaper_animated`: GIF/MP4,
  maximum 16 MiB. These are only saved pending an independently verified host
  integration. A GIF within existing `ThemeArchive` is currently rendered as one
  bitmap by `CssThemeHook`; downloading does not make it animated.
- The current public v1 server admits only `com.whatsapp` and `com.whatsapp.w4b`
  targets, SPDX licenses `CC0-1.0`, `CC-BY-4.0`, `CC-BY-SA-4.0`, title at most
  80 characters, description at most 500 and author at most 60. The client
  rejects metadata outside these bounds, even before downloading.
- Filenames are deterministic `<uuid>-<sha256>.<extension>` under a caller-provided
  **WAEX app-private** directory. Files are streamed into a sibling temporary
  file with SHA-256 and length checks, then renamed. Existing good files are
  reused without a network request; corrupt same-name files are never overwritten.

## Example request and response

`GET https://catalog.example.org/v1/packs?sort=popular&page_size=20&type=wallpaper_static`

```json
{
  "schema_version": 1,
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "type": "wallpaper_static",
      "title": "Sunset",
      "description": "A synthetic sample, not TISU content",
      "target_packages": ["com.whatsapp", "com.whatsapp.w4b"],
      "author": "Example author",
      "license": "CC0-1.0",
      "media_type": "image/png",
      "size_bytes": 70,
      "sha256": "<replace with the actual 64-character SHA-256 of file bytes>",
      "created_at": "2026-11-18T12:00:00Z",
      "downloads": 3,
      "rating_average": null,
      "rating_count": 0,
      "download_url": "/v1/packs/550e8400-e29b-41d4-a716-446655440000/file"
    }
  ],
  "next_page_token": null
}
```

The placeholder SHA in this documentation is not a test fixture. Runtime validation
requires the actual exact digest and file size.

## Reproducible QA

With JDK 17 and the project-pinned Kotlin 2.0.20 compiler, compile
`app/src/main/java/com/waenhancer/community/CommunityPack.kt`,
`CommunityCatalogClient.kt` and `qa/fixtures/CommunityCatalogContractQa.kt`
using the existing app dependencies: OkHttp 4.12.0, Okio 3.6.0,
`kotlinx-serialization-core-jvm` 1.7.3 and `-json-jvm` 1.7.3.
Run `com.waenhancer.community.CommunityCatalogContractQa` with a Java 17
classpath including those jars and Kotlin runtime. The fake OkHttp transport
tests API pagination, allowed sorting/type, strict metadata, HTTPS-only
origins, redirect/oversize/MIME/hash/magic failures, idempotent downloads,
temporary-file cleanup, and inert theme palettes. No public server or Android
account is touched. Passing this test **does not verify** WhatsApp rendering,
theme application, MediaPlayer lifecycle, a public catalog, moderation or
publication/ratings APIs.

The server-side service and database are a separate deployment with independent
authentication, licensing/rights attestation, moderation and abuse controls.
No third-party TISU binaries/assets are included. Never copy a modified APK's
proprietary images or code into a public WAEX PR without documented rights.

## Next integration gate

Add a real WAEX UI that uses this client and displays **downloaded** versus
**applied** status. Before enabling Apply, implement host-specific selectors,
target-package/profile isolation and a reversible installation transaction.
For animated media, prove a single WhatsApp conversation background lifecycle
(attach, pause, resume, detach, audio mute, decoder release), with API/WhatsApp
version evidence and negative tests. Do not confuse ZIP import or GIF
extraction with animation or a successful WhatsApp hook.

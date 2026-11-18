"""Black-box HTTP tests against the real loopback prototype and SQLite store."""

from __future__ import annotations

import base64
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from waex_catalog import CatalogConfig, CatalogService, Principal, create_server
from waex_catalog.server import ApiError


TOKENS = {
    "synthetic-owner-token": Principal("test-owner-opaque-id", "Author"),
    "synthetic-reader-token": Principal("test-reader-opaque-id", "Reader"),
    "synthetic-moderator-token": Principal("test-moderator-opaque-id", "Moderator", True),
}
THEME_JSON = b'{"schema_version":1,"colors":{"primary":"#112233","background":"#000000","text":"#FFFFFF"}}'
WALLPAPER_PNG = b"\x89PNG\r\n\x1a\n" + b"synthetic-image-payload"
WALLPAPER_JPEG = b"\xff\xd8\xff" + b"synthetic-jpeg-payload"
WALLPAPER_GIF = b"GIF89a" + b"synthetic-animated-payload"


class CatalogHTTPTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="waex-catalog-test-")
        work = Path(self.temp.name)
        self.config = CatalogConfig(db_path=work / "data" / "catalog.sqlite3", blob_dir=work / "blobs",
                                    tokens=TOKENS, cursor_secret=b"synthetic-test-cursor-key" * 2)
        self.server = create_server(self.config, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.temp.cleanup()

    def request(self, path: str, *, method: str = "GET", body: object | None = None,
                token: str | None = None, raw: bool = False) -> tuple[int, object, dict[str, str]]:
        headers: dict[str, str] = {}
        payload = None
        if token:
            headers["Authorization"] = f"Bearer {token}"
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        req = Request(self.base + path, data=payload, method=method, headers=headers)
        try:
            response = urlopen(req, timeout=5)
        except HTTPError as exc:
            response = exc
        with response:
            status = response.status
            data = response.read()
            meta = dict(response.headers.items())
        return status, data if raw else json.loads(data), meta

    @staticmethod
    def payload(*, kind: str = "theme", content: bytes = THEME_JSON, title: str = "Synthetic theme",
                mime: str | None = None) -> dict[str, object]:
        if mime is None:
            mime = {"theme": "application/vnd.waex.theme+json", "wallpaper_static": "image/png",
                    "wallpaper_animated": "image/gif"}[kind]
        return {
            "type": kind,
            "title": title,
            "description": "Synthetic test media, no real person or third-party assets",
            "target_packages": ["com.whatsapp", "com.whatsapp.w4b"],
            "license": "CC0-1.0",
            "rights_attested": True,
            "media_type": mime,
            "size_bytes": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
            "content_base64": base64.b64encode(content).decode("ascii"),
        }

    def publish(self, **kwargs: object) -> dict[str, object]:
        status, data, _ = self.request("/v1/packs", method="POST", body=self.payload(**kwargs),
                                       token="synthetic-owner-token")
        self.assertEqual(status, 201, data)
        self.assertEqual(data["moderation_state"], "pending")
        return data

    def approve(self, pack_id: str) -> dict[str, object]:
        status, data, _ = self.request(f"/v1/moderation/{pack_id}", method="POST",
                                       body={"decision": "approve"}, token="synthetic-moderator-token")
        self.assertEqual(status, 200, data)
        return data

    def test_auth_pending_moderation_integrity_and_withdrawal(self) -> None:
        status, data, _ = self.request("/v1/packs", method="POST", body=self.payload())
        self.assertEqual((status, data["error"]["code"]), (401, "AUTH_REQUIRED"))
        created = self.publish()
        asset_id = created["id"]
        status, own, _ = self.request("/v1/me/packs", token="synthetic-owner-token")
        self.assertEqual((status, [(x["id"], x["moderation_state"]) for x in own["items"]]),
                         (200, [(asset_id, "pending")]))
        status, other, _ = self.request("/v1/me/packs", token="synthetic-reader-token")
        self.assertEqual((status, other["items"]), (200, []))
        for path in ("/v1/packs", f"/v1/packs/{asset_id}", f"/v1/packs/{asset_id}/file"):
            status, payload, _ = self.request(path)
            if path == "/v1/packs":
                self.assertEqual((status, payload["items"], payload["total"]), (200, [], 0))
            else:
                self.assertEqual((status, payload["error"]["code"]), (404, "NOT_FOUND"))
        status, payload, _ = self.request("/v1/moderation")
        self.assertEqual(status, 401)
        status, payload, _ = self.request("/v1/moderation", token="synthetic-reader-token")
        self.assertEqual(status, 403)
        status, payload, _ = self.request("/v1/moderation", token="synthetic-moderator-token")
        self.assertEqual((status, [x["id"] for x in payload["items"]]), (200, [asset_id]))
        status, hidden, _ = self.request(f"/v1/moderation/{asset_id}/file", raw=True,
                                         token="synthetic-reader-token")
        self.assertEqual(status, 403)
        status, preview, meta = self.request(f"/v1/moderation/{asset_id}/file", raw=True,
                                             token="synthetic-moderator-token")
        self.assertEqual((status, preview, meta["X-WAEX-SHA256"]),
                         (200, THEME_JSON, hashlib.sha256(THEME_JSON).hexdigest()))
        status, payload, _ = self.request(f"/v1/moderation/{asset_id}", method="POST",
                                          body={"decision": "approve"}, token="synthetic-owner-token")
        self.assertEqual(status, 403)
        self.approve(asset_id)
        status, detail, _ = self.request(f"/v1/packs/{asset_id}")
        self.assertEqual(status, 200)
        self.assertEqual(detail["declarative_manifest"]["colors"]["text"], "#FFFFFF")
        self.assertNotIn("moderation_state", detail)
        status, body, headers = self.request(f"/v1/packs/{asset_id}/file", raw=True)
        self.assertEqual(status, 200)
        self.assertEqual(body, THEME_JSON)
        self.assertEqual(hashlib.sha256(body).hexdigest(), detail["sha256"])
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(headers["X-WAEX-SHA256"], detail["sha256"])
        self.assertEqual(headers["ETag"], f'"sha256-{detail["sha256"]}"')
        self.assertEqual(headers["Digest"], "sha-256=" + base64.b64encode(hashlib.sha256(body).digest()).decode("ascii"))
        self.assertEqual(headers["Cache-Control"], "no-store")
        status, listing, _ = self.request("/v1/packs")
        self.assertEqual((status, listing["total"], listing["items"][0]["downloads"]), (200, 1, 1))
        status, payload, _ = self.request(f"/v1/moderation/{asset_id}", method="POST",
                                          body={"decision": "reject"}, token="synthetic-moderator-token")
        self.assertEqual((status, payload["error"]["code"]), (409, "INVALID_STATE"))
        status, payload, _ = self.request(f"/v1/packs/{asset_id}", method="DELETE", token="synthetic-reader-token")
        self.assertEqual(status, 404)
        status, payload, _ = self.request(f"/v1/packs/{asset_id}", method="DELETE", token="synthetic-owner-token")
        self.assertEqual((status, payload["withdrawn"]), (200, True))
        status, listing, _ = self.request("/v1/packs")
        self.assertEqual((status, listing["total"]), (200, 0))
        status, payload, _ = self.request(f"/v1/packs/{asset_id}")
        self.assertEqual(status, 404)

    def test_sort_pagination_ratings_popularity_and_reports(self) -> None:
        ids = []
        for title, content, mime in (
            ("Alpha", WALLPAPER_PNG, "image/png"),
            ("Beta", WALLPAPER_PNG + b"b", "image/png"),
            ("Gamma", WALLPAPER_PNG + b"c", "image/png"),
        ):
            ids.append(self.publish(kind="wallpaper_static", content=content, title=title, mime=mime)["id"])
        for item_id in ids:
            self.approve(item_id)
        status, newest, _ = self.request("/v1/packs?sort=newest&page_size=1")
        self.assertEqual(status, 200)
        self.assertEqual(newest["items"][0]["id"], ids[2])
        self.assertTrue(newest["next_page_token"])
        cursor = newest["next_page_token"]
        status, next_page, _ = self.request(f"/v1/packs?sort=newest&page_size=1&pageToken={cursor}")
        self.assertEqual((status, next_page["items"][0]["id"]), (200, ids[1]))
        status, oldest, _ = self.request("/v1/packs?sort=oldest&page_size=3")
        self.assertEqual([row["id"] for row in oldest["items"]], ids)
        status, filtered, _ = self.request("/v1/packs?type=theme")
        self.assertEqual((status, filtered["total"]), (200, 0))
        for _ in range(12):
            status, bytes_data, _ = self.request(f"/v1/packs/{ids[0]}/file", raw=True)
            self.assertEqual(status, 200)
            self.assertEqual(bytes_data, WALLPAPER_PNG)
        status, popular, _ = self.request("/v1/packs?sort=popular")
        # Anonymous repeated downloads may be recorded but do not count as votes.
        self.assertEqual((status, popular["items"][0]["id"]), (200, ids[2]))
        self.assertEqual(popular["items"][2]["downloads"], 12)
        for score in (3, 5):
            status, response, _ = self.request(f"/v1/packs/{ids[1]}/ratings", method="POST",
                                               body={"rating": score}, token="synthetic-reader-token")
            self.assertEqual(status, 200)
            self.assertEqual((response["rating_average"], response["rating_count"]), (float(score), 1))
        status, rated, _ = self.request("/v1/packs?sort=rating")
        self.assertEqual((status, rated["items"][0]["id"]), (200, ids[1]))
        status, rated_popular, _ = self.request("/v1/packs?sort=popular")
        self.assertEqual((status, rated_popular["items"][0]["id"]), (200, ids[1]))
        status, response, _ = self.request(f"/v1/packs/{ids[1]}/ratings", method="POST",
                                           body={"rating": 5}, token="synthetic-owner-token")
        self.assertEqual((status, response["error"]["code"]), (403, "SELF_RATING"))
        status, report, _ = self.request(f"/v1/packs/{ids[1]}/reports", method="POST",
                                         body={"reason": "copyright"}, token="synthetic-reader-token")
        self.assertEqual((status, report["accepted"], report["moderation_review_required"]), (202, True, True))
        status, report, _ = self.request(f"/v1/packs/{ids[1]}/reports", method="POST",
                                         body={"reason": "spam"}, token="synthetic-reader-token")
        self.assertEqual(status, 202)  # one report per account, updating reason
        status, reports, _ = self.request("/v1/moderation/reports", token="synthetic-reader-token")
        self.assertEqual(status, 403)
        status, reports, _ = self.request("/v1/moderation/reports", token="synthetic-moderator-token")
        self.assertEqual((status, len(reports["items"])), (200, 1))
        self.assertEqual((reports["items"][0]["reports_total"], reports["items"][0]["report_reasons"]), (1, ["spam"]))
        self.assertNotIn("test-reader-opaque-id", json.dumps(reports))

    def test_rejects_wrong_digest_size_media_rights_and_unsafe_theme(self) -> None:
        cases = [
            ("sha256", "0" * 64, "DIGEST_MISMATCH"),
            ("size_bytes", 1, "SIZE_MISMATCH"),
            ("rights_attested", False, "RIGHTS_REQUIRED"),
            ("license", "Unverified TISU author", "RIGHTS_REQUIRED"),
            ("license", [], "RIGHTS_REQUIRED"),
            ("target_packages", ["com.evil.fake"], "INVALID_TARGET"),
            ("media_type", "application/zip", "UNSUPPORTED_MEDIA"),
        ]
        for field, value, expected in cases:
            with self.subTest(field=field):
                bad = self.payload()
                bad[field] = value
                status, data, _ = self.request("/v1/packs", method="POST", body=bad,
                                               token="synthetic-owner-token")
                self.assertGreaterEqual(status, 400)
                self.assertEqual(data["error"]["code"], expected)
        for content in (
            b'{"schema_version":1,"colors":{"primary":"#123456","background":"#123456","text":"#123456"},"script":"alert(1)"}',
            b'{"schema_version":1,"colors":{"primary":"url(https://evil.example)","background":"#000000","text":"#FFFFFF"}}',
            b'{"schema_version":999,"colors":{"primary":"#000000","background":"#000000","text":"#000000"}}',
            b'{"schema_version":1,"colors":{"primary":"#123456","background":"#000000","text":"#FFFFFF"},"colors":{"primary":"#000000","background":"#FFFFFF","text":"#000000"}}',
            b'<html><script>unsafe</script></html>',
        ):
            bad = self.payload(content=content)
            status, data, _ = self.request("/v1/packs", method="POST", body=bad,
                                           token="synthetic-owner-token")
            self.assertEqual((status, data["error"]["code"]), (422, "BAD_MANIFEST"))
        bad = self.payload(kind="wallpaper_static", content=b"totally-wrong", mime="image/png")
        status, response, _ = self.request("/v1/packs", method="POST", body=bad, token="synthetic-owner-token")
        self.assertEqual((status, response["error"]["code"]), (422, "BAD_MEDIA"))
        status, response, _ = self.request("/v1/packs")
        self.assertEqual((status, response["total"]), (200, 0))

    def test_json_duplicate_keys_are_rejected(self) -> None:
        req = Request(self.base + "/v1/packs", data=b'{"rating":5,"rating":1}', method="POST",
                      headers={"Content-Type": "application/json", "Authorization": "Bearer synthetic-owner-token"})
        try:
            resp = urlopen(req, timeout=5)
        except HTTPError as exc:
            resp = exc
        with resp:
            status = resp.status
            data = json.loads(resp.read())
        self.assertEqual((status, data["error"]["code"]), (400, "BAD_JSON"))

    def test_rejected_not_public_bad_ratings_and_cursor_integrity(self) -> None:
        created = self.publish(kind="wallpaper_static", content=WALLPAPER_PNG)
        second = self.publish(kind="wallpaper_static", content=WALLPAPER_JPEG, mime="image/jpeg")
        status, rejected, _ = self.request(f"/v1/moderation/{second['id']}", method="POST",
                                           body={"decision": "reject"}, token="synthetic-moderator-token")
        self.assertEqual((status, rejected["moderation_state"]), (200, "rejected"))
        self.approve(created["id"])
        status, response, _ = self.request(f"/v1/packs/{second['id']}/file")
        self.assertEqual(status, 404)
        for rating in (0, 6, 2.5, True, "5"):
            status, data, _ = self.request(f"/v1/packs/{created['id']}/ratings", method="POST",
                                           body={"rating": rating}, token="synthetic-reader-token")
            self.assertEqual((status, data["error"]["code"]), (422, "INVALID_RATING"))
        third = self.publish(kind="wallpaper_animated", content=WALLPAPER_GIF, mime="image/gif")
        self.approve(third["id"])
        status, page, _ = self.request("/v1/packs?sort=newest&page_size=1")
        self.assertEqual(status, 200)
        token = page["next_page_token"]
        self.assertIsNotNone(token)
        status, data, _ = self.request(f"/v1/packs?sort=oldest&page_size=1&pageToken={token}")
        self.assertEqual((status, data["error"]["code"]), (400, "INVALID_CURSOR"))
        changed = token[:-1] + ("A" if token[-1] != "A" else "B")
        status, data, _ = self.request(f"/v1/packs?sort=newest&page_size=1&pageToken={changed}")
        self.assertEqual((status, data["error"]["code"]), (400, "INVALID_CURSOR"))
        status, data, _ = self.request("/v1/packs?page_size=0")
        self.assertEqual((status, data["error"]["code"]), (400, "INVALID_PAGE_SIZE"))
        status, data, _ = self.request("/v1/packs?sort=global-popularity")
        self.assertEqual((status, data["error"]["code"]), (400, "INVALID_SORT"))

    def test_popularity_snapshot_avoids_duplicate_or_omitted_ids_during_rating_update(self) -> None:
        ids = []
        for title, suffix in (("First", b"a"), ("Second", b"b"), ("Third", b"c")):
            pack = self.publish(kind="wallpaper_static", content=WALLPAPER_PNG + suffix, title=title)
            ids.append(pack["id"])
            self.approve(pack["id"])
        status, initial, _ = self.request("/v1/packs?sort=popular&page_size=1")
        self.assertEqual((status, initial["items"][0]["id"]), (200, ids[2]))
        old_snapshot_cursor = initial["next_page_token"]
        # The lower-ranked pack jumps to rank #1 between page requests.
        status, rated, _ = self.request(f"/v1/packs/{ids[0]}/ratings", method="POST",
                                        body={"rating": 5}, token="synthetic-reader-token")
        self.assertEqual((status, rated["rating_count"]), (200, 1))
        status, changed_ranking, _ = self.request("/v1/packs?sort=popular&page_size=3")
        self.assertEqual((status, changed_ranking["items"][0]["id"]), (200, ids[0]))
        # A naive ORDER BY current rating_count OFFSET 1 would return Third
        # again and omit First/Second. The signed snapshot keeps original IDs.
        status, second, _ = self.request(f"/v1/packs?sort=popular&page_size=1&pageToken={old_snapshot_cursor}")
        self.assertEqual((status, second["items"][0]["id"]), (200, ids[1]))
        status, third, _ = self.request(f"/v1/packs?sort=popular&page_size=1&pageToken={second['next_page_token']}")
        self.assertEqual((status, third["items"][0]["id"], third["next_page_token"]), (200, ids[0], None))
        self.assertEqual(len({initial["items"][0]["id"], second["items"][0]["id"], third["items"][0]["id"]}), 3)

    def test_snapshot_excludes_new_approvals_and_skips_withdrawn_pack(self) -> None:
        first = self.publish(kind="wallpaper_static", content=WALLPAPER_PNG + b"q")
        second = self.publish(kind="wallpaper_static", content=WALLPAPER_PNG + b"w")
        third = self.publish(kind="wallpaper_static", content=WALLPAPER_PNG + b"e")
        self.approve(first["id"])
        self.approve(second["id"])
        status, initial, _ = self.request("/v1/packs?sort=oldest&page_size=1")
        self.assertEqual((status, initial["total"], initial["items"][0]["id"]), (200, 2, first["id"]))
        self.approve(third["id"])  # New approval must not enter existing snapshot.
        status, withdrawn, _ = self.request(f"/v1/packs/{second['id']}", method="DELETE",
                                            token="synthetic-owner-token")
        self.assertEqual((status, withdrawn["withdrawn"]), (200, True))
        status, next_page, _ = self.request(f"/v1/packs?sort=oldest&page_size=1&pageToken={initial['next_page_token']}")
        self.assertEqual((status, next_page["items"], next_page["next_page_token"]), (200, [], None))
        status, fresh, _ = self.request("/v1/packs?sort=oldest&page_size=5")
        self.assertEqual((status, [x["id"] for x in fresh["items"]]), (200, [first["id"], third["id"]]))

    def test_concurrent_rating_upserts_do_not_inflate_account_count(self) -> None:
        pack = self.publish()
        self.approve(pack["id"])

        def rate(score: int) -> int:
            status, response, _ = self.request(f"/v1/packs/{pack['id']}/ratings", method="POST",
                                               body={"rating": score}, token="synthetic-reader-token")
            self.assertEqual(status, 200, response)
            return response["rating_count"]

        with ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(rate, [1, 2, 3, 4, 5, 1, 2, 5]))
        self.assertEqual(results, [1] * 8)
        status, detail, _ = self.request(f"/v1/packs/{pack['id']}")
        self.assertEqual((status, detail["rating_count"]), (200, 1))
        self.assertIn(detail["rating_average"], [1.0, 2.0, 3.0, 4.0, 5.0])

    def test_concurrent_downloads_count_without_affecting_popularity(self) -> None:
        pack = self.publish()
        self.approve(pack["id"])

        def download(_: int) -> bytes:
            status, data, _ = self.request(f"/v1/packs/{pack['id']}/file", raw=True)
            self.assertEqual(status, 200)
            return data

        with ThreadPoolExecutor(max_workers=10) as pool:
            payloads = list(pool.map(download, range(20)))
        self.assertEqual(payloads, [THEME_JSON] * 20)
        status, detail, _ = self.request(f"/v1/packs/{pack['id']}")
        self.assertEqual((status, detail["downloads"], detail["rating_count"]), (200, 20, 0))
        status, popular, _ = self.request("/v1/packs?sort=popular")
        self.assertEqual((status, popular["items"][0]["rating_count"]), (200, 0))

    def test_sqlite_and_content_survive_new_service_instance_without_exposing_pending(self) -> None:
        pending = self.publish(title="Pending privacy")
        approved = self.publish(title="Approved privacy", content=THEME_JSON + b" ")
        self.approve(approved["id"])
        alternate = create_server(self.config, port=0)
        alternate.daemon_threads = True
        worker = threading.Thread(target=alternate.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        worker.start()
        original_base = self.base
        try:
            self.base = f"http://127.0.0.1:{alternate.server_port}"
            status, listed, _ = self.request("/v1/packs")
            self.assertEqual((status, [item["id"] for item in listed["items"]]), (200, [approved["id"]]))
            status, owner, _ = self.request("/v1/me/packs", token="synthetic-owner-token")
            self.assertEqual(status, 200)
            self.assertEqual({x["id"]: x["moderation_state"] for x in owner["items"]},
                             {pending["id"]: "pending", approved["id"]: "approved"})
            status, data, _ = self.request(f"/v1/packs/{approved['id']}/file", raw=True)
            self.assertEqual((status, data), (200, THEME_JSON + b" "))
        finally:
            self.base = original_base
            alternate.shutdown()
            alternate.server_close()
            worker.join(timeout=3)

    def test_corrupt_disk_blob_rejected_without_serving_bad_bytes(self) -> None:
        pack = self.publish()
        self.approve(pack["id"])
        blob_path = self.config.blob_dir / pack["sha256"][:2] / pack["sha256"]
        blob_path.write_bytes(b"tampered")
        status, response, _ = self.request(f"/v1/packs/{pack['id']}/file")
        self.assertEqual((status, response["error"]["code"]), (500, "STORAGE_CORRUPT"))

    def test_withdrawal_race_during_blob_read_blocks_download(self) -> None:
        pack = self.publish()
        self.approve(pack["id"])
        service = CatalogService(self.config)
        original_path = service._blob_path(pack["sha256"])

        class RaceBlob:
            def read_bytes(self) -> bytes:
                data = original_path.read_bytes()
                # Adversarial ordering: approval read -> file read -> owner
                # withdraws -> download's final UPDATE authorization check.
                service.withdraw(TOKENS["synthetic-owner-token"], pack["id"])
                return data

        with patch.object(service, "_blob_path", return_value=RaceBlob()):
            with self.assertRaises(ApiError) as raised:
                service.download(pack["id"])
        self.assertEqual((raised.exception.status, raised.exception.code), (404, "NOT_FOUND"))
        status, own, _ = self.request("/v1/me/packs", token="synthetic-owner-token")
        self.assertEqual((status, own["items"][0]["moderation_state"], own["items"][0]["downloads"]),
                         (200, "withdrawn", 0))

    def test_auth_rate_limiter_quota_and_prototype_scope(self) -> None:
        with self.assertRaisesRegex(ValueError, "loopback-only"):
            create_server(self.config, host="0.0.0.0", port=0)
        with self.assertRaisesRegex(ValueError, "authenticated principals"):
            create_server(CatalogConfig(db_path=Path(self.temp.name) / "other.sqlite3",
                                        blob_dir=Path(self.temp.name) / "other", tokens={}), port=0)
        separate = CatalogConfig(db_path=Path(self.temp.name) / "limit.sqlite3",
                                 blob_dir=Path(self.temp.name) / "limit-blobs", tokens=TOKENS,
                                 cursor_secret=b"synthetic-test-cursor-key" * 2,
                                 max_pending_per_user=1, limit_actions_per_minute=2)
        limited_server = create_server(separate, port=0)
        try:
            limited_server.daemon_threads = True
            worker = threading.Thread(target=limited_server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
            worker.start()
            original_base = self.base
            self.base = f"http://127.0.0.1:{limited_server.server_port}"
            try:
                status, data, _ = self.request("/v1/packs", method="POST", body=self.payload(),
                                               token="synthetic-owner-token")
                self.assertEqual(status, 201)
                status, data, _ = self.request("/v1/packs", method="POST", body=self.payload(),
                                               token="synthetic-owner-token")
                self.assertEqual((status, data["error"]["code"]), (429, "QUOTA_EXCEEDED"))
                status, data, _ = self.request("/v1/packs", method="POST", body=self.payload(),
                                               token="synthetic-owner-token")
                self.assertEqual((status, data["error"]["code"]), (429, "RATE_LIMITED"))
            finally:
                self.base = original_base
                limited_server.shutdown()
                worker.join(timeout=3)
        finally:
            limited_server.server_close()


if __name__ == "__main__":
    unittest.main()

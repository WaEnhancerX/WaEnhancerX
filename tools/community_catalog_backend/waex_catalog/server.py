"""Local REST contract prototype for community-supplied WAEX visual packs.

No public deployment, WhatsApp/WAEX modification, user-account integration or
third-party theme assets are performed by this server. Publishing is always
moderated; a real deployment needs externally verified identities, object
storage, distributed abuse controls, and an operational moderation process.
"""

from __future__ import annotations

import argparse
import base64
from collections import OrderedDict, defaultdict, deque
from contextlib import contextmanager, closing
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import tempfile
import threading
import time
from typing import Any, Iterator, Mapping
from urllib.parse import parse_qs, urlsplit
import uuid


PACK_TYPES = frozenset({"theme", "wallpaper_static", "wallpaper_animated"})
SORTS = frozenset({"newest", "oldest", "popular", "rating"})
LICENSES = frozenset({"CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0"})
TARGET_PACKAGES = frozenset({"com.whatsapp", "com.whatsapp.w4b"})
MEDIA_TYPES = {
    "theme": frozenset({"application/vnd.waex.theme+json"}),
    "wallpaper_static": frozenset({"image/png", "image/jpeg", "image/webp"}),
    "wallpaper_animated": frozenset({"image/gif", "video/mp4"}),
}
MAX_PACK_BYTES = 16 * 1024 * 1024
MAX_THEME_BYTES = 256 * 1024
MAX_BODY_BYTES = 23 * 1024 * 1024  # base64 overhead for a 16 MiB binary
HEX_COLOR = re.compile(r"#[0-9A-Fa-f]{6}\Z")
SHA256_HEX = re.compile(r"[0-9a-f]{64}\Z")
UUID_RE = re.compile(r"[0-9a-fA-F-]{36}\Z")
PUBLISH_FIELDS = frozenset({
    "type", "title", "description", "target_packages", "license",
    "rights_attested", "media_type", "size_bytes", "sha256", "content_base64",
})


def _unique_json_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


@dataclass(frozen=True)
class Principal:
    user_id: str
    display_name: str
    moderator: bool = False


@dataclass(frozen=True)
class CatalogConfig:
    db_path: Path
    blob_dir: Path
    tokens: Mapping[str, Principal]
    cursor_secret: bytes = field(default_factory=lambda: secrets.token_bytes(32))
    max_pending_per_user: int = 20
    max_total_per_user: int = 100
    limit_actions_per_minute: int = 60
    cursor_ttl_seconds: int = 900
    max_snapshot_items: int = 10000
    max_active_snapshots: int = 64


def _string(value: Any, field_name: str, max_length: int, *, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or (not allow_empty and not value.strip()):
        raise ApiError(422, "INVALID_FIELD", f"{field_name} must be a nonempty string")
    if len(value) > max_length or any(ord(c) < 32 for c in value) or "<" in value or ">" in value:
        raise ApiError(422, "INVALID_FIELD", f"{field_name} contains unsupported characters or is too long")
    return value.strip()


def _timestamp(value_ns: int) -> str:
    return datetime.fromtimestamp(value_ns / 1_000_000_000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _pack_id(value: str) -> str:
    if not UUID_RE.fullmatch(value):
        raise ApiError(404, "NOT_FOUND", "Pack not found")
    try:
        return str(uuid.UUID(value))
    except ValueError as exc:
        raise ApiError(404, "NOT_FOUND", "Pack not found") from exc


def _validate_content(kind: str, mime: str, content: bytes) -> dict[str, Any] | None:
    if kind == "theme":
        if len(content) > MAX_THEME_BYTES:
            raise ApiError(413, "TOO_LARGE", "Theme manifest exceeds 256 KiB")
        try:
            manifest = json.loads(content.decode("utf-8"), object_pairs_hook=_unique_json_pairs)
        except (UnicodeError, ValueError) as exc:
            raise ApiError(422, "BAD_MANIFEST", "Theme must be valid UTF-8 JSON") from exc
        if not isinstance(manifest, dict) or set(manifest) != {"schema_version", "colors"}:
            raise ApiError(422, "BAD_MANIFEST", "Theme must contain only schema_version and colors")
        if type(manifest["schema_version"]) is not int or manifest["schema_version"] != 1:
            raise ApiError(422, "BAD_MANIFEST", "Unsupported declarative theme schema")
        colors = manifest["colors"]
        valid_colors = {"primary", "secondary", "background", "surface", "text", "accent"}
        if not isinstance(colors, dict) or not {"primary", "background", "text"}.issubset(colors):
            raise ApiError(422, "BAD_MANIFEST", "Theme requires primary, background and text colors")
        if not set(colors).issubset(valid_colors) or not all(
            isinstance(v, str) and HEX_COLOR.fullmatch(v) for v in colors.values()
        ):
            raise ApiError(422, "BAD_MANIFEST", "Only allowlisted #RRGGBB palette colors are supported")
        return manifest
    signatures = {
        "image/png": content.startswith(b"\x89PNG\r\n\x1a\n"),
        "image/jpeg": content.startswith(b"\xff\xd8\xff"),
        "image/webp": len(content) >= 12 and content[:4] == b"RIFF" and content[8:12] == b"WEBP",
        "image/gif": content.startswith((b"GIF87a", b"GIF89a")),
        "video/mp4": len(content) >= 12 and content[4:8] == b"ftyp",
    }
    if not signatures.get(mime, False):
        raise ApiError(422, "BAD_MEDIA", "Content magic does not match declared media_type")
    return None


class CatalogService:
    def __init__(self, config: CatalogConfig):
        self.config = config
        self._snapshots_lock = threading.Lock()
        self._snapshots: OrderedDict[str, tuple[float, tuple[str, ...]]] = OrderedDict()
        if not config.tokens or any(not isinstance(v, Principal) for v in config.tokens.values()):
            raise ValueError("Explicit authenticated principals are required")
        if len(config.cursor_secret) < 32:
            raise ValueError("cursor_secret must be at least 32 bytes")
        if config.limit_actions_per_minute < 1 or config.max_pending_per_user < 1 or config.max_total_per_user < 1 or \
                config.max_snapshot_items < 1 or config.max_active_snapshots < 1:
            raise ValueError("Quotas, snapshot limits and action limit must be positive")
        for token, principal in config.tokens.items():
            if not isinstance(token, str) or len(token) < 16 or len(token) > 256 or \
                    not re.fullmatch(r"[!-~]+", token):
                raise ValueError("Authentication tokens must be 16-256 visible ASCII characters")
            if not re.fullmatch(r"[A-Za-z0-9_-]{3,64}", principal.user_id):
                raise ValueError("Principal IDs must be opaque identifiers, not phone numbers or text")
            _string(principal.display_name, "principal display name", 60)
        config.db_path.parent.mkdir(parents=True, exist_ok=True)
        config.blob_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        (config.blob_dir / ".staging").mkdir(mode=0o700, exist_ok=True)
        with closing(self._connection()) as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS packs (
                    id TEXT PRIMARY KEY,
                    owner_id TEXT NOT NULL,
                    author TEXT NOT NULL,
                    type TEXT NOT NULL CHECK(type IN ('theme','wallpaper_static','wallpaper_animated')),
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    targets_json TEXT NOT NULL,
                    license TEXT NOT NULL,
                    media_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL CHECK(size_bytes > 0),
                    sha256 TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('pending','approved','rejected','withdrawn')),
                    created_ns INTEGER NOT NULL,
                    approved_ns INTEGER,
                    updated_ns INTEGER NOT NULL,
                    downloads INTEGER NOT NULL DEFAULT 0,
                    rating_sum INTEGER NOT NULL DEFAULT 0,
                    rating_count INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS packs_public ON packs(state,approved_ns,created_ns,id);
                CREATE INDEX IF NOT EXISTS packs_owner ON packs(owner_id,state);
                CREATE TABLE IF NOT EXISTS ratings (
                    pack_id TEXT NOT NULL REFERENCES packs(id),
                    user_id TEXT NOT NULL,
                    score INTEGER NOT NULL CHECK(score BETWEEN 1 AND 5),
                    updated_ns INTEGER NOT NULL,
                    PRIMARY KEY (pack_id,user_id)
                );
                CREATE TABLE IF NOT EXISTS reports (
                    pack_id TEXT NOT NULL REFERENCES packs(id),
                    user_id TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    created_ns INTEGER NOT NULL,
                    PRIMARY KEY (pack_id,user_id)
                );
                CREATE TABLE IF NOT EXISTS moderation_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    pack_id TEXT NOT NULL REFERENCES packs(id),
                    moderator_id TEXT NOT NULL,
                    decision TEXT NOT NULL CHECK(decision IN ('approve','reject','withdraw')),
                    created_ns INTEGER NOT NULL
                );
            """)

    def _connection(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.config.db_path, isolation_level=None, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=5000")
        return db

    @contextmanager
    def _write(self) -> Iterator[sqlite3.Connection]:
        with closing(self._connection()) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def _blob_path(self, digest: str) -> Path:
        return self.config.blob_dir / digest[:2] / digest

    def _save_verified_blob(self, digest: str, content: bytes) -> None:
        path = self._blob_path(digest)
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if path.exists():
            if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise ApiError(500, "STORAGE_CORRUPT", "Existing blob has invalid digest")
            return
        fd, temp_name = tempfile.mkstemp(dir=self.config.blob_dir / ".staging", prefix="waex-")
        try:
            with os.fdopen(fd, "wb") as out:
                out.write(content)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temp_name, path)
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)

    @staticmethod
    def _public(row: sqlite3.Row, *, include_state: bool = False) -> dict[str, Any]:
        count = row["rating_count"]
        value = {
            "id": row["id"],
            "type": row["type"],
            "title": row["title"],
            "description": row["description"],
            "author": row["author"],
            "target_packages": json.loads(row["targets_json"]),
            "license": row["license"],
            "media_type": row["media_type"],
            "size_bytes": row["size_bytes"],
            "sha256": row["sha256"],
            "created_at": _timestamp(row["created_ns"]),
            "downloads": row["downloads"],
            "rating_average": round(row["rating_sum"] / count, 2) if count else None,
            "rating_count": count,
            "download_url": f"/v1/packs/{row['id']}/file",
        }
        if include_state:
            value["moderation_state"] = row["state"]
        return value

    def publish(self, principal: Principal, request: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(request, dict) or set(request) != PUBLISH_FIELDS:
            raise ApiError(422, "INVALID_FIELDS", "Upload fields are missing or unsupported")
        kind = request["type"]
        if not isinstance(kind, str) or kind not in PACK_TYPES:
            raise ApiError(422, "INVALID_TYPE", "Unknown visual pack type")
        mime = request["media_type"]
        if not isinstance(mime, str) or mime not in MEDIA_TYPES[kind]:
            raise ApiError(415, "UNSUPPORTED_MEDIA", "Media type not permitted for pack type")
        title = _string(request["title"], "title", 80)
        description = _string(request["description"], "description", 500, allow_empty=True)
        targets = request["target_packages"]
        if not isinstance(targets, list) or not targets or len(targets) > 2 or \
                any(not isinstance(t, str) for t in targets) or set(targets) - TARGET_PACKAGES or len(set(targets)) != len(targets):
            raise ApiError(422, "INVALID_TARGET", "Only exact declared WhatsApp package targets are allowed")
        if not isinstance(request["license"], str) or request["license"] not in LICENSES or request["rights_attested"] is not True:
            raise ApiError(422, "RIGHTS_REQUIRED", "Supported license and explicit rights attestation are required")
        length = request["size_bytes"]
        if type(length) is not int or length < 1 or length > MAX_PACK_BYTES:
            raise ApiError(413, "TOO_LARGE", "Pack byte size must be between 1 and 16 MiB")
        digest = request["sha256"]
        if not isinstance(digest, str) or not SHA256_HEX.fullmatch(digest):
            raise ApiError(422, "INVALID_DIGEST", "sha256 must be lowercase hexadecimal")
        encoded = request["content_base64"]
        if not isinstance(encoded, str):
            raise ApiError(422, "INVALID_DATA", "content_base64 must be a string")
        try:
            content = base64.b64decode(encoded, validate=True)
        except (ValueError, base64.binascii.Error) as exc:
            raise ApiError(422, "INVALID_DATA", "Base64 payload is malformed") from exc
        if len(content) != length:
            raise ApiError(422, "SIZE_MISMATCH", "Declared size differs from actual bytes")
        if not hmac.compare_digest(hashlib.sha256(content).hexdigest(), digest):
            raise ApiError(422, "DIGEST_MISMATCH", "Declared SHA-256 differs from actual content")
        _validate_content(kind, mime, content)
        now = time.time_ns()
        pack_id = str(uuid.uuid4())
        with self._write() as db:
            pending = db.execute("SELECT COUNT(*) FROM packs WHERE owner_id=? AND state='pending'", (principal.user_id,)).fetchone()[0]
            total = db.execute("SELECT COUNT(*) FROM packs WHERE owner_id=? AND state!='withdrawn'", (principal.user_id,)).fetchone()[0]
            if pending >= self.config.max_pending_per_user or total >= self.config.max_total_per_user:
                raise ApiError(429, "QUOTA_EXCEEDED", "Submission quota exceeded")
            self._save_verified_blob(digest, content)
            db.execute("""
                INSERT INTO packs(id,owner_id,author,type,title,description,targets_json,license,
                                  media_type,size_bytes,sha256,state,created_ns,updated_ns)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,'pending',?,?)
            """, (pack_id, principal.user_id, principal.display_name, kind, title, description,
                  json.dumps(targets), request["license"], mime, length, digest, now, now))
            row = db.execute("SELECT * FROM packs WHERE id=?", (pack_id,)).fetchone()
        return self._public(row, include_state=True)

    def moderate(self, principal: Principal, pack_id: str, decision: str) -> dict[str, Any]:
        pack_id = _pack_id(pack_id)
        if not isinstance(decision, str) or decision not in {"approve", "reject"}:
            raise ApiError(422, "INVALID_DECISION", "Decision must be approve or reject")
        now = time.time_ns()
        with self._write() as db:
            row = db.execute("SELECT * FROM packs WHERE id=?", (pack_id,)).fetchone()
            if row is None:
                raise ApiError(404, "NOT_FOUND", "Pack not found")
            if row["state"] != "pending":
                raise ApiError(409, "INVALID_STATE", "Only pending packs may be moderated")
            db.execute("UPDATE packs SET state=?,approved_ns=?,updated_ns=? WHERE id=?",
                       ("approved" if decision == "approve" else "rejected", now if decision == "approve" else None, now, pack_id))
            db.execute("INSERT INTO moderation_events(pack_id,moderator_id,decision,created_ns) VALUES (?,?,?,?)",
                       (pack_id, principal.user_id, decision, now))
            result = db.execute("SELECT * FROM packs WHERE id=?", (pack_id,)).fetchone()
        return self._public(result, include_state=True)

    def withdraw(self, principal: Principal, pack_id: str) -> None:
        pack_id = _pack_id(pack_id)
        with self._write() as db:
            row = db.execute("SELECT owner_id,state FROM packs WHERE id=?", (pack_id,)).fetchone()
            if row is None or (row["owner_id"] != principal.user_id and not principal.moderator):
                raise ApiError(404, "NOT_FOUND", "Pack not found")
            if row["state"] == "withdrawn":
                raise ApiError(409, "INVALID_STATE", "Pack already withdrawn")
            db.execute("UPDATE packs SET state='withdrawn',updated_ns=? WHERE id=?", (time.time_ns(), pack_id))
            if principal.moderator and row["owner_id"] != principal.user_id:
                db.execute("INSERT INTO moderation_events(pack_id,moderator_id,decision,created_ns) VALUES (?,?,?,?)",
                           (pack_id, principal.user_id, "withdraw", time.time_ns()))

    def _row(self, pack_id: str, *, only_public: bool = True) -> sqlite3.Row:
        pack_id = _pack_id(pack_id)
        with closing(self._connection()) as db:
            row = db.execute("SELECT * FROM packs WHERE id=?", (pack_id,)).fetchone()
        if row is None or (only_public and row["state"] != "approved"):
            raise ApiError(404, "NOT_FOUND", "Pack not found")
        return row

    def detail(self, pack_id: str) -> dict[str, Any]:
        row = self._row(pack_id)
        result = self._public(row)
        if row["type"] == "theme":
            data = self._blob_path(row["sha256"]).read_bytes()
            if hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise ApiError(500, "STORAGE_CORRUPT", "Theme blob failed integrity verification")
            result["declarative_manifest"] = json.loads(data)
        return result

    def download(self, pack_id: str) -> tuple[dict[str, Any], bytes]:
        row = self._row(pack_id)
        try:
            content = self._blob_path(row["sha256"]).read_bytes()
        except OSError as exc:
            raise ApiError(500, "STORAGE_CORRUPT", "Approved pack data is unavailable") from exc
        if len(content) != row["size_bytes"] or hashlib.sha256(content).hexdigest() != row["sha256"]:
            raise ApiError(500, "STORAGE_CORRUPT", "Approved pack failed integrity verification")
        with self._write() as db:
            updated = db.execute("UPDATE packs SET downloads=downloads+1 WHERE id=? AND state='approved'", (row["id"],))
            if updated.rowcount != 1:
                # The pack was withdrawn while its bytes were being checked;
                # no response may be authorized based on the earlier read.
                raise ApiError(404, "NOT_FOUND", "Pack not found")
        return self._public(row), content

    def rate(self, principal: Principal, pack_id: str, score: int) -> dict[str, Any]:
        pack_id = _pack_id(pack_id)
        if type(score) is not int or score < 1 or score > 5:
            raise ApiError(422, "INVALID_RATING", "rating must be an integer 1 through 5")
        with self._write() as db:
            row = db.execute("SELECT * FROM packs WHERE id=? AND state='approved'", (pack_id,)).fetchone()
            if row is None:
                raise ApiError(404, "NOT_FOUND", "Pack not found")
            if row["owner_id"] == principal.user_id:
                raise ApiError(403, "SELF_RATING", "Authors cannot rate their own packs")
            previous = db.execute("SELECT score FROM ratings WHERE pack_id=? AND user_id=?",
                                  (pack_id, principal.user_id)).fetchone()
            now = time.time_ns()
            if previous is None:
                db.execute("INSERT INTO ratings(pack_id,user_id,score,updated_ns) VALUES (?,?,?,?)",
                           (pack_id, principal.user_id, score, now))
                db.execute("UPDATE packs SET rating_sum=rating_sum+?,rating_count=rating_count+1 WHERE id=?", (score, pack_id))
            else:
                db.execute("UPDATE ratings SET score=?,updated_ns=? WHERE pack_id=? AND user_id=?",
                           (score, now, pack_id, principal.user_id))
                db.execute("UPDATE packs SET rating_sum=rating_sum+? WHERE id=?", (score - previous["score"], pack_id))
            result = db.execute("SELECT * FROM packs WHERE id=?", (pack_id,)).fetchone()
        return self._public(result)

    def report(self, principal: Principal, pack_id: str, reason: str) -> dict[str, Any]:
        pack_id = _pack_id(pack_id)
        if not isinstance(reason, str) or reason not in {"copyright", "spam", "unsafe", "misleading", "other"}:
            raise ApiError(422, "INVALID_REPORT", "Report reason is unsupported")
        with self._write() as db:
            row = db.execute("SELECT state FROM packs WHERE id=?", (pack_id,)).fetchone()
            if row is None or row["state"] != "approved":
                raise ApiError(404, "NOT_FOUND", "Pack not found")
            db.execute("""
                INSERT INTO reports(pack_id,user_id,reason,created_ns) VALUES (?,?,?,?)
                ON CONFLICT(pack_id,user_id) DO UPDATE SET reason=excluded.reason,created_ns=excluded.created_ns
            """, (pack_id, principal.user_id, reason, time.time_ns()))
        return {"accepted": True, "moderation_review_required": True}

    def moderation_queue(self) -> list[dict[str, Any]]:
        with closing(self._connection()) as db:
            rows = db.execute("SELECT * FROM packs WHERE state='pending' ORDER BY created_ns ASC,id ASC LIMIT 100").fetchall()
        return [self._public(row, include_state=True) for row in rows]

    def own_queue(self, principal: Principal) -> list[dict[str, Any]]:
        with closing(self._connection()) as db:
            rows = db.execute("SELECT * FROM packs WHERE owner_id=? ORDER BY created_ns DESC,id DESC LIMIT 100",
                              (principal.user_id,)).fetchall()
        return [self._public(row, include_state=True) for row in rows]

    def moderator_preview(self, pack_id: str) -> tuple[dict[str, Any], bytes]:
        row = self._row(pack_id, only_public=False)
        try:
            content = self._blob_path(row["sha256"]).read_bytes()
        except OSError as exc:
            raise ApiError(500, "STORAGE_CORRUPT", "Pending pack data is unavailable") from exc
        if len(content) != row["size_bytes"] or hashlib.sha256(content).hexdigest() != row["sha256"]:
            raise ApiError(500, "STORAGE_CORRUPT", "Pending pack failed integrity verification")
        return self._public(row, include_state=True), content

    def reported_queue(self) -> list[dict[str, Any]]:
        with closing(self._connection()) as db:
            rows = db.execute("""
                SELECT packs.*,COUNT(reports.user_id) AS reports_total,
                       GROUP_CONCAT(reports.reason) AS report_reasons
                FROM packs JOIN reports ON packs.id=reports.pack_id
                WHERE packs.state='approved'
                GROUP BY packs.id ORDER BY reports_total DESC, packs.created_ns DESC LIMIT 100
            """).fetchall()
        result = []
        for row in rows:
            item = self._public(row, include_state=True)
            item["reports_total"] = row["reports_total"]
            item["report_reasons"] = row["report_reasons"].split(",")
            result.append(item)
        return result

    def _cursor(self, data: dict[str, Any]) -> str:
        body = json.dumps(data, sort_keys=True, separators=(",", ":")).encode("utf-8")
        sig = hmac.new(self.config.cursor_secret, body, hashlib.sha256).digest()
        return base64.urlsafe_b64encode(body).rstrip(b"=").decode("ascii") + "." + \
            base64.urlsafe_b64encode(sig).rstrip(b"=").decode("ascii")

    def _read_cursor(self, token: str) -> dict[str, Any]:
        if not token or len(token) > 768 or token.count(".") != 1:
            raise ApiError(400, "INVALID_CURSOR", "Malformed pagination token")
        a, b = token.split(".")
        try:
            body = base64.urlsafe_b64decode(a + "=" * (-len(a) % 4))
            sig = base64.urlsafe_b64decode(b + "=" * (-len(b) % 4))
            data = json.loads(body)
        except (ValueError, UnicodeError, base64.binascii.Error) as exc:
            raise ApiError(400, "INVALID_CURSOR", "Malformed pagination token") from exc
        if not hmac.compare_digest(hmac.new(self.config.cursor_secret, body, hashlib.sha256).digest(), sig):
            raise ApiError(400, "INVALID_CURSOR", "Invalid pagination token signature")
        if not isinstance(data, dict) or set(data) != {"offset", "snapshot_id", "sort", "type", "size", "issued"}:
            raise ApiError(400, "INVALID_CURSOR", "Unexpected pagination token fields")
        if type(data["issued"]) is not int or time.time() - data["issued"] > self.config.cursor_ttl_seconds:
            raise ApiError(400, "EXPIRED_CURSOR", "Pagination token expired")
        if type(data["offset"]) is not int or not 0 <= data["offset"] <= self.config.max_snapshot_items or \
                not isinstance(data["snapshot_id"], str) or type(data["size"]) is not int:
            raise ApiError(400, "INVALID_CURSOR", "Invalid pagination bounds")
        return data

    def _new_snapshot(self, kind: str | None, sort: str) -> tuple[str, tuple[str, ...]]:
        # One SQLite SELECT captures a stable order, including popularity at that
        # instant. Later ratings/downloads cannot reorder later cursor pages.
        order = {
            "newest": "created_ns DESC,id DESC",
            "oldest": "created_ns ASC,id ASC",
            # Anonymous download requests are not trusted as popularity votes.
            # One authenticated account contributes at most ONE to rating_count.
            "popular": "rating_count DESC,CASE WHEN rating_count=0 THEN -1.0 ELSE CAST(rating_sum AS REAL)/rating_count END DESC,created_ns DESC,id DESC",
            "rating": "CASE WHEN rating_count=0 THEN -1.0 ELSE CAST(rating_sum AS REAL)/rating_count END DESC,rating_count DESC,created_ns DESC,id DESC",
        }[sort]
        where = "state='approved'"
        args: list[Any] = []
        if kind is not None:
            where += " AND type=?"
            args.append(kind)
        with closing(self._connection()) as db:
            rows = db.execute(f"SELECT id FROM packs WHERE {where} ORDER BY {order} LIMIT ?",
                              [*args, self.config.max_snapshot_items + 1]).fetchall()
        if len(rows) > self.config.max_snapshot_items:
            raise ApiError(503, "SNAPSHOT_LIMIT", "Catalog snapshot exceeds local prototype limit")
        snapshot_id = str(uuid.uuid4())
        ids = tuple(row["id"] for row in rows)
        now = time.monotonic()
        with self._snapshots_lock:
            self._prune_snapshots(now)
            self._snapshots[snapshot_id] = (now, ids)
            while len(self._snapshots) > self.config.max_active_snapshots:
                self._snapshots.popitem(last=False)
        return snapshot_id, ids

    def _prune_snapshots(self, now: float) -> None:
        """Caller holds _snapshots_lock."""
        for key, (created, _) in list(self._snapshots.items()):
            if now - created > self.config.cursor_ttl_seconds:
                del self._snapshots[key]

    def _snapshot_ids(self, snapshot_id: str) -> tuple[str, ...]:
        now = time.monotonic()
        with self._snapshots_lock:
            self._prune_snapshots(now)
            entry = self._snapshots.get(snapshot_id)
            if entry is None:
                raise ApiError(400, "EXPIRED_CURSOR", "Catalog snapshot expired; restart pagination")
            return entry[1]

    def list_public(self, *, kind: str | None, sort: str, size: int, token: str | None) -> dict[str, Any]:
        if kind is not None and kind not in PACK_TYPES:
            raise ApiError(400, "INVALID_TYPE", "Unknown pack type filter")
        if sort not in SORTS:
            raise ApiError(400, "INVALID_SORT", "Unknown sort order")
        if type(size) is not int or not 1 <= size <= 50:
            raise ApiError(400, "INVALID_PAGE_SIZE", "page_size must be between 1 and 50")
        if token:
            cursor = self._read_cursor(token)
            if cursor["sort"] != sort or cursor["type"] != kind or cursor["size"] != size:
                raise ApiError(400, "INVALID_CURSOR", "Pagination parameters changed")
            offset, snapshot_id, issued = cursor["offset"], cursor["snapshot_id"], cursor["issued"]
            ids = self._snapshot_ids(snapshot_id)
        else:
            offset, issued = 0, int(time.time())
            snapshot_id, ids = self._new_snapshot(kind, sort)
        # A pack withdrawn after snapshot creation must never leak from public
        # paging; skip its ID while keeping the remaining original ranking.
        selected: list[sqlite3.Row] = []
        next_offset = offset
        with closing(self._connection()) as db:
            while len(selected) < size and next_offset < len(ids):
                batch = ids[next_offset: min(len(ids), next_offset + size - len(selected))]
                found = db.execute(
                    "SELECT * FROM packs WHERE state='approved' AND id IN (" +
                    ",".join("?" for _ in batch) + ")", batch).fetchall()
                by_id = {row["id"]: row for row in found}
                selected.extend(by_id[pack_id] for pack_id in batch if pack_id in by_id)
                next_offset += len(batch)
        next_page = self._cursor({"offset": next_offset, "snapshot_id": snapshot_id, "sort": sort,
                                  "type": kind, "size": size, "issued": issued}) if next_offset < len(ids) else None
        return {"schema_version": 1, "items": [self._public(row) for row in selected],
                "next_page_token": next_page, "total": len(ids)}


class ActionLimiter:
    """Per-process safety backstop, NOT a production distributed rate limiter."""

    def __init__(self, per_minute: int):
        self.per_minute = per_minute
        self._events: dict[tuple[str, str], deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, principal: Principal, action: str) -> None:
        with self._lock:
            now = time.monotonic()
            events = self._events[(principal.user_id, action)]
            while events and now - events[0] >= 60.0:
                events.popleft()
            if len(events) >= self.per_minute:
                raise ApiError(429, "RATE_LIMITED", "Too many operations; retry later")
            events.append(now)


def create_server(config: CatalogConfig, *, host: str = "127.0.0.1", port: int = 0) -> ThreadingHTTPServer:
    if host not in {"127.0.0.1", "localhost"}:
        raise ValueError("Prototype is loopback-only: configure a real TLS/auth gateway before production")
    service = CatalogService(config)
    limiter = ActionLimiter(config.limit_actions_per_minute)

    class Handler(BaseHTTPRequestHandler):
        server_version = "WAEXCatalogPrototype/0.1"
        sys_version = ""
        protocol_version = "HTTP/1.0"

        def log_message(self, fmt: str, *args: object) -> None:
            # Do not record Authorization or the URL (including pageToken) in logs.
            pass

        def _headers(self, status: int, mime: str, size: int, *, extra: Mapping[str, str] | None = None) -> None:
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(size))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'")
            # Takedown must not be defeated by a long-lived cache of unreviewed media.
            self.send_header("Cache-Control", "no-store")
            for name, value in (extra or {}).items():
                self.send_header(name, value)
            self.end_headers()

        def _json(self, status: int, value: dict[str, Any]) -> None:
            body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self._headers(status, "application/json; charset=utf-8", len(body))
            self.wfile.write(body)

        def _error(self, exc: ApiError) -> None:
            self._json(exc.status, {"error": {"code": exc.code, "message": exc.message}})

        def _auth(self, *, moderator: bool = False) -> Principal:
            value = self.headers.get("Authorization", "")
            if not value.startswith("Bearer ") or value.count(" ") != 1:
                raise ApiError(401, "AUTH_REQUIRED", "Bearer authentication is required")
            token = value[7:]
            principal = next((p for candidate, p in config.tokens.items() if hmac.compare_digest(token, candidate)), None)
            if principal is None:
                raise ApiError(401, "AUTH_REQUIRED", "Bearer authentication is required")
            if moderator and not principal.moderator:
                raise ApiError(403, "FORBIDDEN", "Moderator role is required")
            return principal

        def _incoming_json(self) -> dict[str, Any]:
            if self.headers.get("Transfer-Encoding"):
                raise ApiError(411, "CONTENT_LENGTH_REQUIRED", "Chunked requests are not accepted")
            mime = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if mime != "application/json":
                raise ApiError(415, "UNSUPPORTED_MEDIA", "Use application/json")
            try:
                length = int(self.headers["Content-Length"])
            except (KeyError, TypeError, ValueError) as exc:
                raise ApiError(411, "CONTENT_LENGTH_REQUIRED", "Valid Content-Length is required") from exc
            if length < 1 or length > MAX_BODY_BYTES:
                raise ApiError(413, "TOO_LARGE", "Request body size exceeds configured limit")
            data = self.rfile.read(length)
            if len(data) != length:
                raise ApiError(400, "TRUNCATED_BODY", "Request body is incomplete")
            try:
                payload = json.loads(data, object_pairs_hook=_unique_json_pairs)
            except (UnicodeError, ValueError) as exc:
                raise ApiError(400, "BAD_JSON", "Malformed JSON body") from exc
            if not isinstance(payload, dict):
                raise ApiError(400, "BAD_JSON", "Top-level body must be an object")
            return payload

        @staticmethod
        def _param(query: dict[str, list[str]], key: str, default: str | None = None) -> str | None:
            values = query.get(key)
            if values is None:
                return default
            if len(values) != 1:
                raise ApiError(400, "DUPLICATE_QUERY", f"Duplicate query parameter: {key}")
            return values[0]

        def _route(self, method: str) -> None:
            parsed = urlsplit(self.path)
            query = parse_qs(parsed.query, keep_blank_values=True)
            path = parsed.path
            if path == "/healthz" and method == "GET":
                self._json(200, {"status": "ok", "scope": "local-prototype"})
                return
            if path == "/v1/packs" and method == "GET":
                if set(query) - {"type", "sort", "page_size", "pageToken"}:
                    raise ApiError(400, "INVALID_QUERY", "Unknown catalog query parameters")
                try:
                    size = int(self._param(query, "page_size", "20") or "")
                except ValueError as exc:
                    raise ApiError(400, "INVALID_PAGE_SIZE", "page_size must be an integer") from exc
                data = service.list_public(kind=self._param(query, "type"),
                                           sort=self._param(query, "sort", "newest") or "newest",
                                           size=size, token=self._param(query, "pageToken"))
                self._json(200, data)
                return
            if path == "/v1/packs" and method == "POST":
                principal = self._auth()
                limiter.check(principal, "publish")
                self._json(201, service.publish(principal, self._incoming_json()))
                return
            if path == "/v1/moderation" and method == "GET":
                self._auth(moderator=True)
                self._json(200, {"items": service.moderation_queue()})
                return
            if path == "/v1/me/packs" and method == "GET":
                principal = self._auth()
                self._json(200, {"items": service.own_queue(principal)})
                return
            if path == "/v1/moderation/reports" and method == "GET":
                self._auth(moderator=True)
                self._json(200, {"items": service.reported_queue()})
                return
            parts = path.strip("/").split("/")
            if len(parts) == 4 and parts[:2] == ["v1", "moderation"] and parts[3] == "file" and method == "GET":
                self._auth(moderator=True)
                metadata, data = service.moderator_preview(parts[2])
                digest = metadata["sha256"]
                self._headers(200, metadata["media_type"], len(data), extra={
                    "ETag": '"sha256-' + digest + '"', "X-WAEX-SHA256": digest,
                })
                self.wfile.write(data)
                return
            if len(parts) == 3 and parts[:2] == ["v1", "moderation"] and method == "POST":
                principal = self._auth(moderator=True)
                limiter.check(principal, "moderate")
                body = self._incoming_json()
                if set(body) != {"decision"}:
                    raise ApiError(422, "INVALID_FIELDS", "Moderation requires decision only")
                self._json(200, service.moderate(principal, parts[2], body["decision"]))
                return
            if len(parts) >= 3 and parts[:2] == ["v1", "packs"]:
                pack_id = parts[2]
                if len(parts) == 3 and method == "GET":
                    self._json(200, service.detail(pack_id))
                    return
                if len(parts) == 3 and method == "DELETE":
                    principal = self._auth()
                    limiter.check(principal, "withdraw")
                    service.withdraw(principal, pack_id)
                    self._json(200, {"withdrawn": True})
                    return
                if len(parts) == 4 and parts[3] == "file" and method == "GET":
                    limiter.check(Principal("public_" + self.client_address[0], "Public"), "download")
                    metadata, data = service.download(pack_id)
                    digest = metadata["sha256"]
                    self._headers(200, metadata["media_type"], len(data), extra={
                        "ETag": '"sha256-' + digest + '"',
                        "X-WAEX-SHA256": digest,
                        "Digest": "sha-256=" + base64.b64encode(bytes.fromhex(digest)).decode("ascii"),
                    })
                    # Return an identity check the client MUST independently compute.
                    self.wfile.write(data)
                    return
                if len(parts) == 4 and parts[3] == "ratings" and method == "POST":
                    principal = self._auth()
                    limiter.check(principal, "rate")
                    body = self._incoming_json()
                    if set(body) != {"rating"}:
                        raise ApiError(422, "INVALID_FIELDS", "Ratings require rating only")
                    self._json(200, service.rate(principal, pack_id, body["rating"]))
                    return
                if len(parts) == 4 and parts[3] == "reports" and method == "POST":
                    principal = self._auth()
                    limiter.check(principal, "report")
                    body = self._incoming_json()
                    if set(body) != {"reason"}:
                        raise ApiError(422, "INVALID_FIELDS", "Report requires reason only")
                    self._json(202, service.report(principal, pack_id, body["reason"]))
                    return
            raise ApiError(404, "NOT_FOUND", "Endpoint not found")

        def do_GET(self) -> None:
            self._dispatch("GET")

        def do_POST(self) -> None:
            self._dispatch("POST")

        def do_DELETE(self) -> None:
            self._dispatch("DELETE")

        def _dispatch(self, method: str) -> None:
            try:
                self._route(method)
            except ApiError as exc:
                self._error(exc)
            except Exception:
                # No traceback, tokens, paths, message identifiers or file bytes in HTTP output.
                self._error(ApiError(500, "INTERNAL_ERROR", "Internal catalog error"))

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server


def main() -> None:
    parser = argparse.ArgumentParser(description="WAEX local-only catalog prototype")
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--blobs", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    raw = os.environ.get("WAEX_CATALOG_DEV_TOKENS_JSON")
    if not raw:
        parser.error("WAEX_CATALOG_DEV_TOKENS_JSON is required; no built-in credentials exist")
    try:
        tokens = {
            token: Principal(user_id=v["user_id"], display_name=v["display_name"],
                             moderator=bool(v.get("moderator", False)))
            for token, v in json.loads(raw).items()
        }
    except (TypeError, KeyError, ValueError) as exc:
        parser.error(f"invalid WAEX_CATALOG_DEV_TOKENS_JSON shape: {type(exc).__name__}")
    with create_server(CatalogConfig(db_path=args.db, blob_dir=args.blobs, tokens=tokens), port=args.port) as server:
        print(f"WAEX catalog prototype on http://127.0.0.1:{server.server_port} (loopback only)")
        try:
            server.serve_forever(poll_interval=0.25)
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

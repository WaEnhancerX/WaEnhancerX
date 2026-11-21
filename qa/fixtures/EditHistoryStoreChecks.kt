package test

import android.database.Cursor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.waenhancer.xposed.core.db.EditMessageStore

private var checks = 0
private fun checkCase(value: Boolean, message: String) {
    checks++
    if (!value) throw AssertionError(message)
}

private fun newStore(): EditMessageStore {
    SQLiteOpenHelper.nextDatabase = SQLiteDatabase()
    val constructor = EditMessageStore::class.java.getDeclaredConstructor(Context::class.java)
    constructor.isAccessible = true
    return constructor.newInstance(Context())
}

private fun texts(store: EditMessageStore, row: Long, key: String?): List<String> =
    store.getMessages(row, key).map { it.textData }

private fun row(id: Long, key: String, text: String?) =
    mutableMapOf<String, Any?>("_id" to id, "key_id" to key, "text_data" to text)

private fun historyRow(id: Long, rowId: Long, key: String, text: String, version: Int) =
    mutableMapOf<String, Any?>("_id" to id, "row_id" to rowId, "key_id" to key,
        "text_data" to text, "timestamp" to id * 1000, "version" to version)

fun main(args: Array<String>) {
    val scenario = args.firstOrNull() ?: "all"
    if (scenario == "cache" || scenario == "all") {
        for (keyFirst in listOf(false, true)) {
            val store = newStore()
            if (keyFirst) {
                store.recordTransition(88, "77", "key-original", "key-edited", 20000)
                store.recordTransition(77, null, "row-original", "row-edited", 10000)
            } else {
                store.recordTransition(77, null, "row-original", "row-edited", 10000)
                store.recordTransition(88, "77", "key-original", "key-edited", 20000)
            }
            checkCase(texts(store, 0, "77") == listOf("key-original", "key-edited"),
                "numeric-looking message key must not reuse row-ID cache")
            checkCase(texts(store, 77, null) == listOf("row-original", "row-edited"),
                "row-ID history must not reuse numeric-looking key cache")
            store.recordTransition(90, "row:77", "prefixed-original", "prefixed-edited", 30000)
            checkCase(texts(store, 0, "row:77") == listOf("prefixed-original", "prefixed-edited"),
                "literal prefixed message IDs cannot collide with row namespace")
            checkCase(texts(store, 77, null) == listOf("row-original", "row-edited"), "unrelated histories stay intact")
            val callerCopy = store.getMessages(77, null) as MutableList<EditMessageStore.MessageItem>
            callerCopy.clear()
            checkCase(texts(store, 77, null).size == 2, "caller mutation does not alter cached list")
            checkCase(store.getMessages(0, null).isEmpty(), "missing identifiers cannot select every history")
        }
    }
    if (scenario == "lookup" || scenario == "all") {
        val store = newStore()
        val host = SQLiteDatabase()
        SQLiteDatabase.host = host
        host.rows("message").add(row(1, "wanted", "wanted text"))
        host.rows("message").add(row(9, "other-chat", "unrelated private text"))
        host.rows("message_ftsv2_content").add(mutableMapOf("docid" to 1L, "c0content" to "wanted FTS"))
        host.rows("message_ftsv2_content").add(mutableMapOf("docid" to 9L, "c0content" to "unrelated FTS"))

        checkCase(store.getCurrentMessageFromWhatsApp(9, "wanted") == "wanted text", "key wins mismatched reflected row")
        host.queries.clear()
        checkCase(store.getCurrentMessageFromWhatsApp(9, "missing") == "", "missing key must not disclose another row")
        checkCase(host.queries.none { it.contains("/_id=?/") || it.contains("/docid=?/") },
            "missing key performs no unverified numeric fallback")
        host.rows("message")[0]["text_data"] = ""
        checkCase(store.getCurrentMessageFromWhatsApp(9, "wanted") == "wanted FTS", "empty text uses key-verified FTS ID")
        host.rows("message")[0]["text_data"] = null
        checkCase(store.getCurrentMessageFromWhatsApp(9, "wanted") == "wanted FTS", "null text uses key-verified FTS ID")
        host.noTextColumn = true
        checkCase(store.getCurrentMessageFromWhatsApp(9, "wanted") == "wanted FTS", "missing text column resolves key before FTS")
        checkCase(store.getCurrentMessageFromWhatsApp(9, "missing") == "", "schema fallback cannot bypass missing key")
        host.noTextColumn = false
        host.rows("message").add(row(2, "wanted", "other same-key chat"))
        checkCase(store.getCurrentMessageFromWhatsApp(9, "wanted") == "", "duplicate host keys are ambiguous")
        host.rows("message").removeAt(2)
        host.rejectKeyQueries = true
        checkCase(store.getCurrentMessageFromWhatsApp(9, "wanted") == "", "key query failure cannot bypass identity")
        host.rejectKeyQueries = false
        checkCase(store.getCurrentMessageFromWhatsApp(9, null) == "unrelated private text", "row-only compatibility remains")
        checkCase(store.getCurrentMessageFromWhatsApp(0, null) == "", "missing all identities remains empty")
        host.rows("message")[1]["text_data"] = ""
        checkCase(store.getCurrentMessageFromWhatsApp(9, null) == "unrelated FTS", "row-only FTS compatibility remains")
        checkCase(store.getCurrentMessageFromWhatsApp(0, "wanted") == "wanted FTS", "verified key does not require reflected row")
        checkCase(host.openCount == host.closeCount, "host DB handle closes on every successful or early-return path")

        // Actual store transition must not invent an original from another numeric row.
        val recording = newStore()
        recording.recordTransition(9, "not-in-host", null, "new edit", 40000)
        checkCase(texts(recording, 9, "not-in-host") == listOf("new edit"), "no unrelated original is persisted")
    }
    if (scenario == "persistence" || scenario == "all") {
        for (failure in listOf("first", "second", "throw", "commit", "begin")) {
            val store = newStore()
            val db = SQLiteOpenHelper.nextDatabase
            when (failure) {
                "first" -> db.failInsertAt = 1
                "second" -> db.failInsertAt = 2
                "throw" -> db.throwInsertAt = 2
                "commit" -> db.failEnd = true
                "begin" -> db.failBegin = true
            }
            store.recordTransition(10, "persisted", "original", "edited", 10000)
            checkCase(texts(store, 10, "persisted").isEmpty(), "$failure failure must not publish ghost versions")
            checkCase(db.rows("message_history").isEmpty(), "$failure failure must not leave a partial transition")
            checkCase(db.endCalls == if (failure == "begin") 0 else 1, "$failure transaction lifecycle")
            db.failInsertAt = -1; db.throwInsertAt = -1; db.failEnd = false; db.failBegin = false
            store.recordTransition(10, "persisted", "original", "edited", 10000)
            checkCase(texts(store, 10, "persisted") == listOf("original", "edited"), "$failure recovery persists exact transition")
            checkCase(store.getMessages(10, "persisted").map { it.versionNumber } == listOf(1, 2), "$failure recovery version order")
            store.recordTransition(10, "persisted", null, "edited", 10001)
            checkCase(db.rows("message_history").size == 2, "$failure retry remains idempotent")
        }
        val invalid = newStore()
        invalid.recordTransition(0, null, "original", "edited", 10000)
        checkCase(SQLiteOpenHelper.nextDatabase.insertCalls == 0, "missing identity never persists unreachable rows")
    }
    if (scenario == "query_failure" || scenario == "all") {
        for (failure in listOf("throw", "null", "partial", "close")) {
            val store = newStore()
            val db = SQLiteOpenHelper.nextDatabase
            store.recordTransition(20, "history", "one", "two", 10000)
            val inserted = db.insertCalls
            when (failure) {
                "throw" -> db.rejectHistoryQueries = true
                "null" -> db.nullHistoryCursor = true
                "partial" -> Cursor.failAtRow = 1
                "close" -> Cursor.failClose = true
            }
            store.recordTransition(20, "history", "two", "three", 20000)
            checkCase(db.insertCalls == inserted, "$failure query cannot masquerade as empty/truncated history")
            db.rejectHistoryQueries = false; db.nullHistoryCursor = false
            Cursor.failAtRow = -1; Cursor.failClose = false
            checkCase(texts(store, 20, "history") == listOf("one", "two"), "$failure recovery reads confirmed versions")
            store.recordTransition(20, "history", "two", "three", 20000)
            checkCase(texts(store, 20, "history") == listOf("one", "two", "three"), "$failure retry preserves full history")
        }
        // A failed reader must not cache the prefix it happened to enumerate.
        val store = newStore()
        val db = SQLiteOpenHelper.nextDatabase
        store.recordTransition(30, "reader", "one", "two", 10000)
        Cursor.failAtRow = 1
        checkCase(texts(store, 30, null).isEmpty(), "partial read must not escape as confirmed history")
        Cursor.failAtRow = -1
        checkCase(texts(store, 30, null) == listOf("one", "two"), "partial read must not poison later cache")
    }
    if (scenario == "aliases" || scenario == "all") {
        val store = newStore()
        store.recordTransition(40, "alias", "one", "two", 10000)
        checkCase(texts(store, 40, null) == listOf("one", "two"), "numeric alias initially reads history")
        store.recordTransition(40, "alias", "two", "three", 20000)
        checkCase(texts(store, 40, null) == listOf("one", "two", "three"), "keyed writes invalidate numeric cache alias")
        checkCase(texts(store, 0, "alias") == listOf("one", "two", "three"), "keyed cache reflects committed transition")
    }
    if (scenario == "host_lock" || scenario == "all") {
        val store = newStore()
        val db = SQLiteOpenHelper.nextDatabase
        val host = SQLiteDatabase()
        SQLiteDatabase.host = host
        host.rows("message").add(row(50, "prefetch", "host-original"))
        store.recordTransition(50, "prefetch", null, "edited", 10000)
        checkCase(host.accessDuringPrivateTransaction == 0, "host lookup must not run inside the private write transaction")
        checkCase(host.openCount == 1 && host.closeCount == 1, "first edit prefetches original once and closes host DB")
        checkCase(texts(store, 50, "prefetch") == listOf("host-original", "edited"), "prefetch still records original")
        store.recordTransition(50, "prefetch", null, "edited-again", 20000)
        checkCase(host.openCount == 1, "existing history does not repeat host prefetch")
        val competing = newStore()
        val other = SQLiteOpenHelper.nextDatabase
        other.beforeBegin = {
            other.rows("message_history").add(mutableMapOf("_id" to 1L, "row_id" to 60L,
                "key_id" to "concurrent", "text_data" to "other-writer", "timestamp" to 10000L, "version" to 1))
        }
        competing.recordTransition(60, "concurrent", "prefetched", "ours", 20000)
        checkCase(texts(competing, 60, "concurrent") == listOf("other-writer", "ours"), "transaction rechecks persisted rows after prefetch")
        checkCase(competing.getMessages(60, "concurrent").map { it.versionNumber } == listOf(1, 2), "recheck preserves version order")
    }
    if (scenario == "scope_legacy" || scenario == "all") {
        for (ids in listOf(listOf(101L, 202L), listOf(202L, 101L), listOf(0L, 101L, 0L, 202L))) {
            val store = newStore()
            val db = SQLiteOpenHelper.nextDatabase
            ids.forEachIndexed { index, id ->
                db.rows("message_history").add(historyRow(index + 1L, id, "legacy-mixed", "text-$index", index + 1))
            }
            val persisted = db.rows("message_history").map { it.toMap() }
            for (requested in listOf(0L, 101L, 202L)) {
                val before = db.queries.size
                checkCase(store.getMessages(requested, "legacy-mixed").isEmpty(),
                    "mixed positive row IDs must not be grouped by message key")
                checkCase(db.queries.size == before + 1, "ambiguous history must not become a positive or empty cache entry")
                store.recordTransition(requested, "legacy-mixed", "supplied-before", "candidate-after", 20000)
                checkCase(db.insertCalls == 0, "ambiguous persisted history must not authorize a first-version write")
                checkCase(db.rows("message_history") == persisted, "ambiguous legacy rows remain intact")
            }
            checkCase(store.getMessages(101, null).all { it.rowId == 101L }, "numeric-only legacy reads remain scoped to their row")
            checkCase(store.getMessages(202, null).all { it.rowId == 202L }, "second numeric-only legacy row remains separate")
        }
    }
    if (scenario == "scope_writer" || scenario == "all") {
        val store = newStore()
        val db = SQLiteOpenHelper.nextDatabase
        store.recordTransition(101, "reused-key", "A-before", "A-after", 10000)
        val persisted = db.rows("message_history").map { it.toMap() }
        store.recordTransition(202, "reused-key", "B-before", "B-after", 20000)
        checkCase(db.rows("message_history") == persisted && db.insertCalls == 2,
            "another positive row writer must not append to the existing key history")
        checkCase(texts(store, 0, "reused-key") == listOf("A-before", "A-after"), "rejected writer does not replace confirmed cached history")
        checkCase(store.getMessages(202, "reused-key").isEmpty(), "rejected row cannot read the first row's key cache")
        store.recordTransition(101, "reused-key", "A-before", "A-after", 20001)
        checkCase(db.insertCalls == 2, "same numeric identity and same text remain idempotent")
        store.recordTransition(101, "reused-key", "A-after", "A-next", 30000)
        checkCase(texts(store, 101, "reused-key") == listOf("A-before", "A-after", "A-next"), "same numeric identity can append normally")
        checkCase(store.getMessages(101, "reused-key").map { it.versionNumber } == listOf(1, 2, 3), "same-identity version order remains intact")

        val competing = newStore()
        val other = SQLiteOpenHelper.nextDatabase
        other.beforeBegin = {
            other.rows("message_history").add(historyRow(1, 101, "raced-key", "other-writer", 1))
        }
        competing.recordTransition(202, "raced-key", "prefetched-for-B", "B-after", 20000)
        checkCase(other.insertCalls == 0, "transaction recheck rejects a different row arriving before the write lock")
        checkCase(texts(competing, 101, "raced-key") == listOf("other-writer"), "competing persisted row is retained without rollback or overwrite")
        checkCase(competing.getMessages(202, "raced-key").isEmpty(), "competing row cannot be relabeled as the rejected writer")
        checkCase(other.beginCalls == 1 && other.endCalls == 1 && !other.inTransaction(), "identity rejection closes its private transaction")
    }
    if (scenario == "scope_cache" || scenario == "all") {
        val store = newStore()
        val db = SQLiteOpenHelper.nextDatabase
        store.recordTransition(101, "cached-key", "A-before", "A-after", 10000)
        val before = db.queries.size
        repeat(10_000) {
            check(store.getMessages(202, "cached-key").isEmpty()) { "cached history must reject a contradictory positive row" }
        }
        checkCase(db.queries.size == before, "cached row contradiction requires no extra SQLite query")
        checkCase(texts(store, 0, "cached-key") == listOf("A-before", "A-after"), "contradictory lookup does not discard a valid key cache")
        checkCase(texts(store, 101, "cached-key") == listOf("A-before", "A-after"), "matching row still hits the key cache")
        checkCase(db.queries.size == before, "valid key cache remains useful after rejection")

        val cold = newStore()
        val coldDb = SQLiteOpenHelper.nextDatabase
        coldDb.rows("message_history").add(historyRow(1, 101, "cold-key", "confirmed-A", 1))
        checkCase(cold.getMessages(202, "cold-key").isEmpty(), "uncached keyed lookup must reject a contradictory positive row")
        checkCase(texts(cold, 101, "cold-key") == listOf("confirmed-A"), "rejected reader must not poison a subsequent matching lookup")
        checkCase(cold.getMessages(202, "cold-key").isEmpty(), "read-filled cache preserves the stored positive identity")
        println("Guard cache: 10000 contradictory reads; 0 additional SQLite queries")
    }
    if (scenario == "scope_unknown" || scenario == "all") {
        val store = newStore()
        val db = SQLiteOpenHelper.nextDatabase
        store.recordTransition(0, "unresolved-key", "unknown-before", "unknown-after", 10000)
        val original = store.getMessages(0, "unresolved-key")
        checkCase(original.size == 2 && original.all { it.rowId == 0L }, "unresolved row IDs remain stored as unresolved")
        checkCase(store.getMessages(101, "unresolved-key") == original, "one requested positive row must not promote unresolved cached rows")
        checkCase(store.getMessages(202, "unresolved-key") == original, "another request cannot inherit an invented positive cache identity")
        store.recordTransition(101, "unresolved-key", "unknown-after", "known-after", 20000)
        checkCase(store.getMessages(0, "unresolved-key").map { it.rowId } == listOf(0L, 0L, 101L),
            "first positive identity may append without rewriting unresolved legacy rows")
        checkCase(store.getMessages(202, "unresolved-key").isEmpty(), "cache gains a positive identity only from persisted rows")
        store.recordTransition(0, "unresolved-key", "known-after", "still-unresolved", 30000)
        val confirmed = store.getMessages(0, "unresolved-key")
        checkCase(confirmed.map { it.rowId } == listOf(0L, 0L, 101L, 0L), "unresolved later writes are not relabeled or guessed")
        checkCase(confirmed.map { it.versionNumber } == listOf(1, 2, 3, 4), "unresolved compatibility retains version ordering")
        checkCase(store.getMessages(202, "unresolved-key").isEmpty(), "later unresolved rows cannot erase the known positive cache identity")
        store.recordTransition(202, "unresolved-key", "B-before", "B-after", 40000)
        checkCase(store.getMessages(0, "unresolved-key") == confirmed && db.insertCalls == 4,
            "unresolved rows cannot hide an existing positive identity contradiction")
        store.recordTransition(0, "unresolved-key", null, "still-unresolved", 40001)
        checkCase(db.insertCalls == 4, "unresolved same-text retry remains idempotent")
    }
    if (scenario == "cache_budget" || scenario == "all") {
        val large = newStore()
        var db = SQLiteOpenHelper.nextDatabase
        val original = "a".repeat(700_000)
        val edited = "b".repeat(700_000)
        large.recordTransition(70, "large", original, edited, 10000)
        val afterWrite = db.queries.size
        checkCase(texts(large, 70, "large") == listOf(original, edited), "large history remains complete in database")
        checkCase(db.queries.size == afterWrite + 1, "oversized text history must not remain cached")
        texts(large, 70, "large")
        checkCase(db.queries.size == afterWrite + 2, "oversized query result must not refill cache")

        val many = newStore()
        db = SQLiteOpenHelper.nextDatabase
        for (i in 0 until 100) {
            for (version in 1..50) db.rows("message_history").add(mutableMapOf("_id" to (i * 50L + version),
                "row_id" to i + 100L, "key_id" to "items-$i", "text_data" to "text-$version",
                "timestamp" to version.toLong(), "version" to version))
            checkCase(many.getMessages(i + 100L, "items-$i").size == 50, "all versions remain readable")
        }
        var before = db.queries.size
        many.getMessages(199, "items-99")
        checkCase(db.queries.size == before, "recent small history retains a useful cache hit")
        many.getMessages(100, "items-0")
        checkCase(db.queries.size == before + 1, "global item budget evicts the oldest history")

        val textBudget = newStore()
        db = SQLiteOpenHelper.nextDatabase
        for (i in 0..3) textBudget.recordTransition(300L + i, "chars-$i", "a".repeat(160_000), "b".repeat(160_000), 10000)
        before = db.queries.size
        textBudget.getMessages(303, "chars-3")
        checkCase(db.queries.size == before, "latest large-but-bounded history is cached")
        checkCase(textBudget.getMessages(300, "chars-0").size == 2, "evicted text still reads in full")
        checkCase(db.queries.size == before + 1, "global character budget evicts old text")
    }
    println("PASS: $checks whole EditMessageStore checks ($scenario; explicit Android DB doubles)")
}

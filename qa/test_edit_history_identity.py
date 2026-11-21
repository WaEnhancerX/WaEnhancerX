#!/usr/bin/env python3
"""Compile actual EditMessageStore with explicit Android DB doubles, then execute it.

These are local identity/cache behavior checks, not real Android SQLite or host
key-selector validation. --source permits compiled baseline/negative controls.
"""
from pathlib import Path
import argparse
import subprocess
import os
import shutil
import tempfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--source", type=Path,
                    default=root / "app/src/main/java/com/waenhancer/xposed/core/db/EditMessageStore.kt")
parser.add_argument("--scenario", choices=["all", "cache", "lookup", "persistence", "query_failure", "aliases", "host_lock", "cache_budget",
                                         "scope_legacy", "scope_writer", "scope_cache", "scope_unknown"], default="all")
args = parser.parse_args()
stubs = {
    "Context.java": """package android.content;
public class Context { public Context getApplicationContext() { return this; } }
""",
    "ContentValues.kt": """package android.content
class ContentValues {
    val entries = mutableMapOf<String, Any?>()
    fun put(key: String, value: String?) { entries[key] = value }
    fun put(key: String, value: Long) { entries[key] = value }
    fun put(key: String, value: Int) { entries[key] = value }
}
""",
    "Cursor.java": """package android.database;
import java.util.*;
public class Cursor implements AutoCloseable {
    private final String[] columns;
    private final List<? extends Map<String, Object>> rows;
    private int position = -1;
    public static boolean failClose = false;
    public static int failAtRow = -1;
    public Cursor(String[] columns, List<? extends Map<String, Object>> rows) { this.columns = columns; this.rows = rows; }
    public int getCount() { return rows.size(); }
    public boolean moveToFirst() { position = 0; return !rows.isEmpty(); }
    public boolean moveToNext() {
        if (++position == failAtRow) throw new IllegalStateException("read failed");
        return position < rows.size();
    }
    public int getColumnIndex(String name) { return Arrays.asList(columns).indexOf(name); }
    public String getString(int index) { return (String) rows.get(position).get(columns[index]); }
    public long getLong(int index) { Object n = rows.get(position).get(columns[index]); return n instanceof Number ? ((Number)n).longValue() : 0L; }
    public int getInt(int index) { return (int)getLong(index); }
    public void close() { if (failClose) throw new IllegalStateException("cursor close failed"); }
}
""",
    "Database.kt": """package android.database.sqlite
import android.content.Context
import android.content.ContentValues
import android.database.Cursor
open class SQLiteOpenHelper(context: Context, name: String, factory: Any?, version: Int) {
    companion object { var nextDatabase = SQLiteDatabase() }
    val writableDatabase = nextDatabase
    val readableDatabase get() = writableDatabase
    open fun onCreate(db: SQLiteDatabase) {}
    open fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}
class SQLiteDatabase : AutoCloseable {
    companion object {
        const val OPEN_READONLY = 1
        var host = SQLiteDatabase()
        fun openDatabase(path: String, factory: Any?, flags: Int): SQLiteDatabase {
            if (SQLiteOpenHelper.nextDatabase.inTransaction()) host.accessDuringPrivateTransaction++
            host.openCount++
            return host
        }
    }
    var accessDuringPrivateTransaction = 0
    var beforeBegin: (() -> Unit)? = null
    fun inTransaction() = transactionSnapshot != null
    var insertCalls = 0
    var failInsertAt = -1
    var throwInsertAt = -1
    var beginCalls = 0
    var endCalls = 0
    var failBegin = false
    var failEnd = false
    var rejectHistoryQueries = false
    var nullHistoryCursor = false
    private var transactionSnapshot: Map<String, List<Map<String, Any?>>>? = null
    private var successful = false
    fun beginTransaction() {
        beginCalls++
        if (failBegin) throw IllegalStateException("begin failed")
        check(transactionSnapshot == null)
        beforeBegin?.let { beforeBegin = null; it() }
        transactionSnapshot = tables.mapValues { (_, rows) -> rows.map { it.toMap() } }
        successful = false
    }
    fun setTransactionSuccessful() { check(transactionSnapshot != null); successful = true }
    fun endTransaction() {
        endCalls++
        val before = checkNotNull(transactionSnapshot)
        transactionSnapshot = null
        if (!successful || failEnd) {
            tables.clear()
            before.forEach { (name, rows) -> tables[name] = rows.map { it.toMutableMap() }.toMutableList() }
        }
        if (failEnd) throw IllegalStateException("commit failed")
    }
    var openCount = 0
    var closeCount = 0
    var noTextColumn = false
    var rejectKeyQueries = false
    val queries = mutableListOf<String>()
    private val tables = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
    fun rows(table: String): MutableList<MutableMap<String, Any?>> = tables.getOrPut(table) { mutableListOf() }
    fun execSQL(sql: String) {}
    fun rawQuery(sql: String, args: Array<String>?): Cursor = Cursor(arrayOf("name"), emptyList())
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long {
        insertCalls++
        if (insertCalls == throwInsertAt) throw IllegalStateException("insert failed")
        if (insertCalls == failInsertAt) return -1L
        val entries = values.entries.toMutableMap()
        val id = rows(table).size.toLong() + 1
        entries["_id"] = id
        rows(table).add(entries)
        return id
    }
    fun query(table: String, columns: Array<String>, selection: String, args: Array<String>,
              groupBy: String?, having: String?, orderBy: String?): Cursor? {
        if (this === host && SQLiteOpenHelper.nextDatabase.inTransaction()) accessDuringPrivateTransaction++
        queries.add("$table/$selection/${args.joinToString()}")
        if (table == "message_history" && rejectHistoryQueries) throw IllegalStateException("history read failed")
        if (table == "message_history" && nullHistoryCursor) return null
        if (table == "message" && noTextColumn && "text_data" in columns)
            throw IllegalStateException("no such column: text_data")
        if (table == "message" && rejectKeyQueries && selection == "key_id=?")
            throw IllegalStateException("key query unavailable")
        val column = when (selection) {
            "key_id=?" -> "key_id"
            "row_id=?" -> "row_id"
            "_id=?" -> "_id"
            "docid=?" -> "docid"
            else -> throw AssertionError("unmodeled selection $selection")
        }
        val selected = rows(table).filter { it[column]?.toString() == args.single() }
        val sorted = if (table == "message_history") selected.sortedWith(
            compareBy({ (it["version"] as Number).toInt() }, { (it["_id"] as Number).toLong() })) else selected
        return Cursor(columns, sorted)
    }
    override fun close() { closeCount++ }
}
""",
    "XposedBridge.kt": """package de.robv.android.xposed
object XposedBridge { fun log(message: String) { System.err.println(message) } }
""",
    "MessageStore.kt": """package com.waenhancer.xposed.core.db
import android.content.Context
import java.io.File
object MessageStore { fun findMsgstoreDbFile(context: Context): File? = File("/fixture/msgstore.db") }
""",
}

with tempfile.TemporaryDirectory() as folder:
    temp = Path(folder)
    sources = []
    java_sources = []
    for name, content in stubs.items():
        path = temp / name
        path.write_text(content)
        (java_sources if path.suffix == ".java" else sources).append(str(path))
    classes = temp / "classes"
    classes.mkdir()
    subprocess.run(["javac", "--release", "17", "-d", str(classes), *java_sources], check=True)
    subprocess.run([shutil.which("kotlinc.bat") or shutil.which("kotlinc") or "kotlinc", "-classpath", str(classes), *sources, str(args.source),
                    str(root / "qa/fixtures/EditHistoryStoreChecks.kt"), "-jvm-target", "17",
                    "-include-runtime", "-d", str(temp / "checks.jar")], check=True)
    subprocess.run(["java", "-cp", str(temp / "checks.jar") + os.pathsep + str(classes),
                    "test.EditHistoryStoreChecksKt", args.scenario], check=True)

package com.waenhancer.utils

import android.content.Context
import android.content.Intent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object SyncFixture {
    val preferences = ConcurrentHashMap<String, AtomicLong>()
    val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val broadcasts: MutableList<Triple<String, String, Long>> = Collections.synchronizedList(mutableListOf())
    val rootCalls = AtomicInteger()
    val broadcastGates = ConcurrentHashMap<String, BroadcastGate>()
    @Volatile var autoAck = true
    @Volatile var detected: List<HookedAppInfo> = DeclaredWhatsAppTargets.packages().map { HookedAppInfo(it) }
    @Volatile var failBroadcast = false
}
class BroadcastGate {
    val arrived = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun hold() {
        arrived.countDown()
        check(release.await(8, TimeUnit.SECONDS)) { "FIXTURE_BROADCAST_GATE_TIMEOUT" }
    }
}
class FixtureContext : Context() {
    override fun sendBroadcast(intent: Intent) {
        val pkg = intent.getPackage() ?: ""
        if (intent.action != "com.waenhancer.action.REPORT_HOOKS" ||
            pkg !in DeclaredWhatsAppTargets.packages() || intent.getStringExtra("PKG") != pkg) {
            SyncFixture.failures.add("Unexpected broadcast boundary")
            throw AssertionError("Unexpected broadcast boundary")
        }
        SyncFixture.broadcasts.add(Triple(pkg, Thread.currentThread().name, System.nanoTime()))
        SyncFixture.broadcastGates[pkg]?.hold()
        if (SyncFixture.failBroadcast) throw IllegalStateException("EXPECTED_BROADCAST_BOUNDARY_FAILURE")
        if (SyncFixture.autoAck) {
            SyncFixture.preferences.computeIfAbsent("injected_hooks_time_" + pkg) { AtomicLong() }.incrementAndGet()
        }
    }
}

class IteratorGate(private val count: Int) {
    val arrivals = CountDownLatch(count)
    val release = CountDownLatch(1)
    val workerNames: MutableList<String> = Collections.synchronizedList(mutableListOf())
    fun atEnd() {
        workerNames.add(Thread.currentThread().name)
        arrivals.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "FIXTURE_GATE_RELEASE_TIMEOUT" }
    }
}
class GatedCollection(private val values: List<String>, private val gate: IteratorGate) : AbstractCollection<String>() {
    override val size: Int get() = values.size
    override fun iterator(): Iterator<String> = object : Iterator<String> {
        private var index = 0
        private var ended = false
        override fun hasNext(): Boolean {
            val more = index < values.size
            if (!more && !ended) {
                ended = true
                gate.atEnd()
            }
            return more
        }
        override fun next(): String {
            if (index >= values.size) throw NoSuchElementException()
            return values[index++]
        }
    }
}

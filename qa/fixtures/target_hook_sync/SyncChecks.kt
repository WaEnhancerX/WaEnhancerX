package com.waenhancer.utils

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private fun managerScope(): CoroutineScope {
    val field = TargetAppManager::class.java.getDeclaredField("scope")
    field.isAccessible = true
    return field.get(TargetAppManager) as CoroutineScope
}
private suspend fun settle(job: Job) {
    withTimeout(7_000) {
        while (true) {
            val pending = job.children.toList()
            if (pending.isEmpty()) return@withTimeout
            pending.joinAll()
            delay(1)
        }
    }
}
private suspend fun finish(job: Job) { withTimeout(7_000) { job.join() } }

fun main(args: Array<String>) = runBlocking {
    require(args.size == 1)
    val mode = args[0]
    require(mode in setOf("single_ack", "forced_ack", "forced_timeout", "partial_overlap", "disjoint_progress", "timeout_retry", "cancel_retry",
        "duplicate_input", "empty_request", "default_installed_only", "broadcast_failure"))
    val a = "com.whatsapp"
    val b = "com.whatsapp.w4b"
    val ctx = FixtureContext()
    val scope = managerScope()
    val supervisor = scope.coroutineContext[Job]!!
    var checks = 0
    fun expect(condition: Boolean, reason: String) { checks++; check(condition) { reason } }
    fun counts() = synchronized(SyncFixture.broadcasts) { SyncFixture.broadcasts.toList() }.groupingBy { it.first }.eachCount()
    fun expectCounts(expected: Map<String, Int>) = expect(counts() == expected, "BROADCAST_COUNTS: ${counts()} != $expected")
    fun startHeld(request: List<String>, held: String): Job {
        val gate = SyncFixture.broadcastGates.getValue(held)
        val before = supervisor.children.toSet()
        TargetAppManager.syncHooks(ctx, request)
        expect(gate.arrived.await(5, TimeUnit.SECONDS), "HELD_TARGET_DID_NOT_PROGRESS: $held")
        val children = supervisor.children.filterNot { it in before }.toList()
        expect(children.size == 1, "UNEXPECTED_HELD_CHILDREN: ${children.size}")
        return children.single()
    }
    fun observe(phase: String) {
        val currentCounts = counts().toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
        println("OBS|$phase|syncing=${TargetAppManager.hooksSyncing.value.sorted().joinToString(",")}" +
            "|unresponsive=${TargetAppManager.hooksUnresponsive.value.sorted().joinToString(",")}|counts=$currentCounts")
    }
    println("RUNTIME|kotlin=${KotlinVersion.CURRENT}|java=${System.getProperty("java.version")}")
    var iteratorGate: IteratorGate? = null
    val began = System.nanoTime()
    var failure: Throwable? = null
    try {
        when (mode) {
            "single_ack", "forced_ack", "forced_timeout" -> {
                SyncFixture.autoAck = mode != "forced_timeout"
                val requests = listOf(a, b)
                if (mode != "single_ack") {
                    val gate = IteratorGate(2); iteratorGate = gate
                    TargetAppManager.syncHooks(ctx, GatedCollection(requests, gate))
                    TargetAppManager.syncHooks(ctx, GatedCollection(requests, gate))
                    expect(gate.arrivals.await(5, TimeUnit.SECONDS), "FIXTURE_BOTH_ITERATORS_NOT_REACHED")
                    expect(gate.workerNames.distinct().size == 2, "FIXTURE_NOT_TWO_IO_WORKERS")
                    expect(TargetAppManager.hooksSyncing.value.isEmpty(), "FIXTURE_CLAIM_BEFORE_GATE")
                    val jobs = supervisor.children.toList()
                    expect(jobs.size == 2, "FIXTURE_EXPECTED_TWO_SYNC_JOBS")
                    observe("both_iterators_finished_before_claim")
                    gate.release.countDown()
                    withTimeout(7_000) { jobs.joinAll() }
                } else TargetAppManager.syncHooks(ctx, requests)
                settle(supervisor)
                expect(TargetAppManager.hooksUnresponsive.value ==
                    (if (SyncFixture.autoAck) emptySet() else requests.toSet()), "RESPONSE_ORACLE_MISMATCH")
                expect(counts().keys == requests.toSet(), "BROADCAST_TARGET_LOST_OR_ADDED")
                observe("requests_finished")
                expect(requests.all { counts()[it] == 1 }, "DUPLICATE_SYNC_BROADCAST")
            }
            "partial_overlap", "disjoint_progress" -> {
                val ga = BroadcastGate(); val gb = BroadcastGate()
                SyncFixture.broadcastGates[a] = ga; SyncFixture.broadcastGates[b] = gb
                val ja = startHeld(listOf(a), a)
                expect(TargetAppManager.hooksSyncing.value == setOf(a), "FIRST_TARGET_NOT_CLAIMED")
                val jb = startHeld(if (mode == "partial_overlap") listOf(a, b) else listOf(b), b)
                expectCounts(mapOf(a to 1, b to 1))
                expect(TargetAppManager.hooksSyncing.value == setOf(a, b), "DISJOINT_CLAIM_LOST")
                expect(!ja.isCompleted && !jb.isCompleted, "BOTH_BATCHES_MUST_BE_IN_FLIGHT")
                observe("both_inflight_before_either_release")
                if (mode == "partial_overlap") {
                    gb.release.countDown(); finish(jb)
                    expect(TargetAppManager.hooksSyncing.value == setOf(a), "OTHER_OWNER_CLAIM_CLEARED")
                    expect(!ja.isCompleted, "FIRST_OWNER_FINISHED_UNEXPECTEDLY")
                    observe("second_finished_first_still_held")
                    ga.release.countDown(); finish(ja)
                } else {
                    ga.release.countDown(); finish(ja)
                    expect(TargetAppManager.hooksSyncing.value == setOf(b), "OTHER_OWNER_CLAIM_CLEARED")
                    expect(!jb.isCompleted, "SECOND_OWNER_FINISHED_UNEXPECTEDLY")
                    observe("first_finished_second_still_held")
                    gb.release.countDown(); finish(jb)
                }
                settle(supervisor)
                expectCounts(mapOf(a to 1, b to 1))
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "ACKED_TARGET_MARKED_UNRESPONSIVE")
            }
            "timeout_retry" -> {
                SyncFixture.autoAck = false
                TargetAppManager.syncHooks(ctx, listOf(a, b)); settle(supervisor)
                expect(TargetAppManager.hooksUnresponsive.value == setOf(a, b), "TIMEOUT_NOT_RECORDED")
                expect(TargetAppManager.hooksSyncing.value.isEmpty(), "TIMEOUT_CLAIM_LEAK")
                observe("both_timed_out")
                SyncFixture.autoAck = true
                TargetAppManager.syncHooks(ctx, listOf(a)); settle(supervisor)
                expect(TargetAppManager.hooksUnresponsive.value == setOf(b), "RETRY_CHANGED_UNRELATED_TIMEOUT")
                expectCounts(mapOf(a to 2, b to 1)); observe("a_recovered_b_preserved")
                TargetAppManager.syncHooks(ctx, listOf(b)); settle(supervisor)
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "RETRY_FAILED_TO_CLEAR_TIMEOUT")
                expectCounts(mapOf(a to 2, b to 2))
            }
            "cancel_retry" -> {
                val gate = BroadcastGate(); SyncFixture.broadcastGates[a] = gate
                SyncFixture.autoAck = false
                val job = startHeld(listOf(a), a)
                expect(TargetAppManager.hooksSyncing.value == setOf(a), "CANCEL_TARGET_NOT_CLAIMED")
                job.cancel(); gate.release.countDown(); finish(job); settle(supervisor)
                expect(job.isCancelled, "JOB_NOT_CANCELLED")
                expect(TargetAppManager.hooksSyncing.value.isEmpty(), "CANCEL_CLAIM_LEAK")
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "CANCEL_PUBLISHED_FALSE_TIMEOUT")
                observe("cancelled_claim_released")
                SyncFixture.autoAck = true
                TargetAppManager.syncHooks(ctx, listOf(a)); settle(supervisor)
                expectCounts(mapOf(a to 2))
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "RETRY_AFTER_CANCEL_FAILED")
            }
            "duplicate_input" -> {
                TargetAppManager.syncHooks(ctx, listOf(a, a, b, b)); settle(supervisor)
                expectCounts(mapOf(a to 1, b to 1))
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "DUPLICATE_INPUT_RESPONSE_MISMATCH")
            }
            "empty_request" -> {
                TargetAppManager.syncHooks(ctx, emptyList()); settle(supervisor)
                expectCounts(emptyMap())
                expect(SyncFixture.rootCalls.get() > 0, "EMPTY_REQUEST_DID_NOT_REFRESH")
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "EMPTY_REQUEST_CHANGED_RESPONSE_STATE")
            }
            "default_installed_only" -> {
                SyncFixture.detected = listOf(HookedAppInfo(a, isInstalled = true), HookedAppInfo(b, isInstalled = false))
                TargetAppManager.refresh(ctx); settle(supervisor)
                expect(TargetAppManager.detectedApps.value == SyncFixture.detected, "DETECTOR_BOUNDARY_NOT_PUBLISHED")
                TargetAppManager.syncHooks(ctx); settle(supervisor)
                expectCounts(mapOf(a to 1))
                expect(TargetAppManager.hooksUnresponsive.value.isEmpty(), "DEFAULT_REQUEST_RESPONSE_MISMATCH")
            }
            "broadcast_failure" -> {
                SyncFixture.failBroadcast = true
                TargetAppManager.syncHooks(ctx, listOf(a)); settle(supervisor)
                expectCounts(mapOf(a to 1))
                expect(TargetAppManager.hooksUnresponsive.value == setOf(a), "BROADCAST_FAILURE_NOT_TIMED_OUT")
            }
        }
        expect(SyncFixture.failures.isEmpty(), "UNEXPECTED_BOUNDARY_FAILURE")
        expect(TargetAppManager.hooksSyncing.value.isEmpty(), "FINAL_CLAIM_LEAK")
        observe("final")
    } catch (error: Throwable) {
        failure = error
    } finally {
        iteratorGate?.release?.countDown()
        SyncFixture.broadcastGates.values.forEach { it.release.countDown() }
        scope.cancel()
        observe("receipt")
        println("ELAPSED_MS|" + (System.nanoTime() - began) / 1_000_000)
        println("RESULT|${if (failure == null) "PASS" else "FAIL"}|$mode|$checks")
        if (failure != null) println("ERROR|${failure!!::class.java.name}|${failure!!.message?.replace("\n", " ")}")
    }
    if (failure != null) throw failure!!
}

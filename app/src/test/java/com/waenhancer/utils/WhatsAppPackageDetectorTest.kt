package com.waenhancer.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppPackageDetectorTest {

    @Test
    fun `runtime inspection reads live process ids`() {
        val snapshot = parseXposedRuntimeSnapshot(
            "PID|com.whatsapp|1023456|123 456\n",
            mapOf("com.whatsapp" to 1023456)
        )
        assertTrue(snapshot.complete)
        assertTrue(snapshot.runningPids["com.whatsapp"] == setOf(123, 456))
    }

    @Test
    fun `runtime inspection rejects another profile uid`() {
        val snapshot = parseXposedRuntimeSnapshot(
            "PID|com.whatsapp|23456|123 456\n",
            mapOf("com.whatsapp" to 1023456)
        )
        assertFalse(snapshot.complete)
    }

    @Test
    fun `runtime inspection rejects records without uid evidence`() {
        val snapshot = parseXposedRuntimeSnapshot(
            "PID|com.whatsapp|123 456\n",
            mapOf("com.whatsapp" to 1023456)
        )
        assertFalse(snapshot.complete)
    }

    @Test
    fun `same process report in current boot is injected`() {
        assertTrue(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = setOf(1234),
                lastActiveTime = 1_000L,
                now = 2_000L
            )
        )
    }

    @Test
    fun `stale process id and heartbeat are not injected`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = setOf(5678),
                lastActiveTime = 1_000L,
                now = 30_001L
            )
        )
    }

    @Test
    fun `report from another boot is not injected`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 41,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = setOf(1234),
                lastActiveTime = 1_000L,
                now = 2_000L
            )
        )
    }

    @Test
    fun `inactive module cannot report injected`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = false,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = setOf(1234),
                lastActiveTime = 1_000L,
                now = 2_000L
            )
        )
    }

    @Test
    fun `fresh heartbeat without a running process is not injected`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = emptySet(),
                lastActiveTime = 10_000L,
                now = 20_000L
            )
        )
    }

    @Test
    fun `stale heartbeat cannot confirm injection without a matching live pid`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = emptySet(),
                lastActiveTime = 10_000L,
                now = 40_001L
            )
        )
    }

    @Test
    fun `running process with a stale heartbeat is not injected`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = setOf(1234),
                lastActiveTime = 10_000L,
                now = 40_001L
            )
        )
    }
}

package com.waenhancer.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppPackageDetectorTest {

    @Test
    fun `runtime inspection reads live process ids`() {
        val snapshot = parseProcessList("123 com.whatsapp\n456 com.whatsapp\n")
        assertTrue(snapshot.runningPids["com.whatsapp"] == setOf(123, 456))
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
                lastActiveTime = 1_000L
            )
        )
    }

    @Test
    fun `different live process id is not injected`() {
        assertFalse(
            hasCurrentInjection(
                isModuleActive = true,
                recordedBoot = 42,
                currentBoot = 42,
                reportedPid = 1234,
                runningPids = setOf(5678),
                lastActiveTime = 1_000L
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
                lastActiveTime = 1_000L
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
                lastActiveTime = 1_000L
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
                lastActiveTime = 10_000L
            )
        )
    }

}

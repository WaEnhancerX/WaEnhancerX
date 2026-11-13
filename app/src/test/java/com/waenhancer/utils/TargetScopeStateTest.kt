package com.waenhancer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetScopeStateTest {
    private fun snap(live: Boolean, vararg pkgs: String, cache: Boolean = false) = LsposedScopeSnapshot(
        "com.waenhancer", pkgs.toSet(), live,
        when { live -> LsposedScopeSnapshot.Source.DATABASE; cache -> LsposedScopeSnapshot.Source.CACHE
            else -> LsposedScopeSnapshot.Source.UNAVAILABLE }
    )

    @Test fun `live scope containing target is in scope`() =
        assertEquals(TargetScopeState.IN_SCOPE, classifyScope("com.whatsapp", snap(true, "com.whatsapp"), false))

    @Test fun `live scope without target excludes it`() =
        assertEquals(TargetScopeState.NOT_IN_SCOPE, classifyScope("com.whatsapp", snap(true, "com.whatsapp.w4b"), false))

    @Test fun `unreadable scope never hides an installed target`() =
        assertEquals(TargetScopeState.UNVERIFIED, classifyScope("com.whatsapp", snap(false), false))

    @Test fun `stale cache without the target does not hide it`() =
        assertEquals(TargetScopeState.UNVERIFIED, classifyScope("com.whatsapp", snap(false, "com.whatsapp.w4b", cache = true), false))

    @Test fun `stale cache containing the target keeps it in scope`() =
        assertEquals(TargetScopeState.IN_SCOPE, classifyScope("com.whatsapp", snap(false, "com.whatsapp", cache = true), false))

    @Test fun `current injection overrides a live read that omits the target`() =
        assertEquals(TargetScopeState.INJECTED, classifyScope("com.whatsapp", snap(true), true))

    @Test fun `manager hook marks module active`() {
        assertTrue(ModuleActivation.resolve(true, emptyList(), 1_000L).active)
    }

    @Test fun `no signal means inactive`() {
        assertFalse(ModuleActivation.resolve(false, emptyList(), 1_000L).active)
    }

    @Test fun `fresh target report proves module active without manager hook`() {
        val app = HookedAppInfo("com.whatsapp", "WhatsApp", "v1", true, false,
            lastActiveTime = 1_000L, injectionEvidence = InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE)
        val r = ModuleActivation.resolve(false, listOf(app), 2_000L)
        assertTrue(r.active); assertEquals(ModuleSignal.TARGET_REPORT, r.signal)
    }

    @Test fun `expired target report does not prove module active`() {
        val app = HookedAppInfo("com.whatsapp", "WhatsApp", "v1", true, false,
            lastActiveTime = 1_000L, injectionEvidence = InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE)
        assertFalse(ModuleActivation.resolve(false, listOf(app), 100_000L).active)
    }
}

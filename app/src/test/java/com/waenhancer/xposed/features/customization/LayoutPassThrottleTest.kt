package com.waenhancer.xposed.features.customization

import com.waenhancer.xposed.features.customization.LayoutPassThrottle.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutPassThrottleTest {

    @Test
    fun firstPassRunsImmediately() {
        assertEquals(Decision.RUN_NOW, LayoutPassThrottle(200).onLayout(1_000))
    }

    @Test
    fun passesInsideTheIntervalCollapseIntoOneTrailingRun() {
        val throttle = LayoutPassThrottle(200)
        assertEquals(Decision.RUN_NOW, throttle.onLayout(1_000))
        assertEquals(Decision.SCHEDULE_TRAILING, throttle.onLayout(1_010))
        assertEquals(Decision.ALREADY_SCHEDULED, throttle.onLayout(1_050))
        assertEquals(Decision.ALREADY_SCHEDULED, throttle.onLayout(1_199))
    }

    @Test
    fun passAfterTheIntervalRunsAgain() {
        val throttle = LayoutPassThrottle(200)
        throttle.onLayout(1_000)
        assertEquals(Decision.RUN_NOW, throttle.onLayout(1_200))
    }

    @Test
    fun trailingRunResetsTheWindowAndAllowsAnotherTrailing() {
        val throttle = LayoutPassThrottle(200)
        throttle.onLayout(1_000)
        assertEquals(Decision.SCHEDULE_TRAILING, throttle.onLayout(1_100))
        throttle.onTrailingRun(1_200)
        // The window restarted at 1_200, so a pass at 1_250 is inside it again.
        assertEquals(Decision.SCHEDULE_TRAILING, throttle.onLayout(1_250))
        assertEquals(Decision.RUN_NOW, throttle.onLayout(1_450))
    }

    @Test
    fun aStormOfPassesProducesOnlyOneImmediateAndOneTrailingRun() {
        val throttle = LayoutPassThrottle(200)
        val decisions = (0 until 50).map { throttle.onLayout(1_000L + it * 3) }
        assertEquals(1, decisions.count { it == Decision.RUN_NOW })
        assertEquals(1, decisions.count { it == Decision.SCHEDULE_TRAILING })
        assertEquals(48, decisions.count { it == Decision.ALREADY_SCHEDULED })
    }
}

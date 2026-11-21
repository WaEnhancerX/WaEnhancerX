package com.waenhancer.xposed.features.customization

/**
 * Limits how often a full view-tree walk runs in response to layout passes, which can fire many
 * times per second. The first pass runs immediately; passes inside the interval collapse into a
 * single trailing run, so the final layout state is always processed.
 *
 * Not thread-safe: layout callbacks and their posted trailing runs execute on the main thread.
 */
internal class LayoutPassThrottle(private val intervalMs: Long) {
    enum class Decision { RUN_NOW, SCHEDULE_TRAILING, ALREADY_SCHEDULED }

    private var lastRunAt: Long? = null
    private var trailingPending = false

    fun onLayout(now: Long): Decision {
        val last = lastRunAt
        if (last == null || now - last >= intervalMs) {
            lastRunAt = now
            return Decision.RUN_NOW
        }
        if (trailingPending) return Decision.ALREADY_SCHEDULED
        trailingPending = true
        return Decision.SCHEDULE_TRAILING
    }

    /** Call when the scheduled trailing run actually executes. */
    fun onTrailingRun(now: Long) {
        trailingPending = false
        lastRunAt = now
    }
}

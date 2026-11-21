package com.waenhancer.utils

import android.os.SystemClock
import java.util.Collections

/** A successful, timestamped process-list observation, not proof of hook initialization. */
data class XposedRuntimeSnapshot(
    val runningPids: Map<String, Set<Int>>,
    val observedAtElapsed: Long = SystemClock.elapsedRealtime(),
    val complete: Boolean = true,
    /** True when [runningPids] came from a full process list: absence means "not running". */
    val exhaustive: Boolean = false
) {
    /** Live pids for [packageName], or null when this snapshot cannot answer. */
    fun pidsFor(packageName: String): Set<Int>? {
        if (!complete) return null
        return runningPids[packageName] ?: if (exhaustive) emptySet() else null
    }
}

private val processNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

/**
 * Parses `ps` output into pids by process name. Handles `PID NAME` (ps -A -o PID,NAME) and the
 * legacy toolbox layout `USER PID PPID ... NAME`. Header and malformed lines are skipped.
 */
internal fun parseProcessList(output: String, observedAtElapsed: Long = SystemClock.elapsedRealtime()): XposedRuntimeSnapshot {
    val byName = linkedMapOf<String, MutableSet<Int>>()
    output.lineSequence().forEach { line ->
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens.size < 2) return@forEach
        val (pidToken, name) = if (tokens.size == 2) tokens[0] to tokens[1] else tokens[1] to tokens.last()
        val pid = pidToken.toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
        if (name.length <= 255 && processNamePattern.matches(name)) byName.getOrPut(name) { linkedSetOf() }.add(pid)
    }
    return XposedRuntimeSnapshot(
        Collections.unmodifiableMap(byName.mapValues { Collections.unmodifiableSet(it.value) }),
        observedAtElapsed, complete = true, exhaustive = true
    )
}

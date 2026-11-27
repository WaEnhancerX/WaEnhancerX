package com.waenhancer.utils

import android.os.SystemClock
import java.util.Collections

/** A successful, timestamped process-list observation, not proof of hook initialization. */
data class XposedRuntimeSnapshot(
    val runningPids: Map<String, Set<Int>>,
    val observedAtElapsed: Long = SystemClock.elapsedRealtime(),
    val complete: Boolean = true,
    /** True when [runningPids] came from a full process list: absence means "not running". */
    val exhaustive: Boolean = false,
    /** Names explicitly covered by a successful filtered observation. */
    val observedPackages: Set<String> = emptySet()
) {
    /** Live pids for [packageName], or null when this snapshot cannot answer. */
    fun pidsFor(packageName: String): Set<Int>? {
        if (!complete) return null
        return runningPids[packageName] ?: if (exhaustive || packageName in observedPackages) emptySet() else null
    }
}

private val processNamePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")

/**
 * Parses `ps` output into pids by process name. Handles `PID NAME` (ps -A -o PID,NAME) and the
 * legacy toolbox layout `USER PID PPID ... NAME`. A malformed row invalidates the observation.
 */
internal fun parseProcessList(output: String, observedAtElapsed: Long = SystemClock.elapsedRealtime()): XposedRuntimeSnapshot {
    val byName = linkedMapOf<String, MutableSet<Int>>()
    val seenPids = mutableSetOf<Int>()
    fun incomplete() = XposedRuntimeSnapshot(emptyMap(), observedAtElapsed, complete = false)
    for (line in output.lineSequence()) {
        if (line.isBlank()) continue
        val tokens = line.trim().split(Regex("\\s+"))
        if (tokens == listOf("PID", "NAME") ||
            (tokens.size >= 3 && tokens[0] == "USER" && tokens[1] == "PID" && tokens.last() == "NAME")) continue
        val (pidToken, name) = when {
            tokens.size == 2 -> tokens[0] to tokens[1]
            tokens.size >= 4 && tokens[2].toIntOrNull()?.let { it >= 0 } == true -> tokens[1] to tokens.last()
            else -> return incomplete()
        }
        val pid = pidToken.toIntOrNull()?.takeIf { it > 0 } ?: return incomplete()
        if (!seenPids.add(pid) || name.length > 255 || !processNamePattern.matches(name)) return incomplete()
        byName.getOrPut(name) { linkedSetOf() }.add(pid)
    }
    return XposedRuntimeSnapshot(
        Collections.unmodifiableMap(byName.mapValues { Collections.unmodifiableSet(it.value) }),
        observedAtElapsed, complete = true, exhaustive = true
    )
}

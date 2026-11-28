package android.os
object Build { object VERSION { const val SDK_INT = 34 } }
object SystemClock { @JvmStatic fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L }

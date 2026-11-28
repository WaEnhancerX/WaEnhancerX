package com.waenhancer.xposed.utils
import com.waenhancer.utils.SyncFixture
object ModuleStatus { @JvmStatic fun isModuleActive() = false }
object AppRestartHelper {
    @JvmStatic fun runRootCommand(command: String): String? {
        SyncFixture.rootCalls.incrementAndGet()
        if (!command.startsWith("FIXTURE_SCOPE_STAGE\n")) {
            SyncFixture.failures.add("Unexpected root-boundary command")
            throw AssertionError("Unexpected root-boundary command")
        }
        return ""
    }
}

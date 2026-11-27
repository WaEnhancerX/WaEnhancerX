# Dashboard refresh acceptance

> This describes an earlier heartbeat/clock architecture. Current source uses
> event-driven target reports plus lifecycle and explicit refresh; use
> [CURRENT_RUNTIME_EVIDENCE_ACCEPTANCE.md](CURRENT_RUNTIME_EVIDENCE_ACCEPTANCE.md)
> for present implementation, tests and outstanding device acceptance.

The dashboard separates target heartbeat refreshes from the full system diagnostic
inventory. Target broadcasts still refresh process observations and target reports.
System diagnostics (root permission, framework packages, device and SELinux) refresh
on screen entry/resume, the explicit diagnostics refresh action, and settings reset.
This change does not modify WhatsApp hooks or the ten-second host heartbeat.

## Device checks

1. Open the dashboard, allow its initial diagnostics to finish, and trace root command
   executions and `PackageManager.getInstalledApplications` calls for at least one minute.
   Target heartbeat events must not launch another `SystemDiagnosticsReader.read`.
   Target process inspection is a separate operation and must still occur.
2. Stop/restart a target and confirm the target card updates, and that the observation
   clock still expires old evidence. A cached positive diagnostic must not establish
   current target injection.
3. Press Refresh diagnostics and verify system, targets and HMA are checked again.
4. Leave/resume the screen after changing framework/root state and verify a new full
   system inventory. Repeat after a confirmed settings reset.
5. Compare manager CPU/frame traces with the same installed packages and heartbeat
   sequence. Do not infer WhatsApp chat scrolling, battery or RAM gains from this
   reduction of manager diagnostic work.

Local source/syntax checks can verify separate refresh keys and preserved callbacks;
the device checks above remain required for Android scheduling and performance.

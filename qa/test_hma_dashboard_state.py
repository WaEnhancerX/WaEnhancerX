#!/usr/bin/env python3
"""Compile the dashboard's exact HMA when expressions and health conditions.

This checks enum exhaustiveness and displayed state selection. Resource IDs and
the surrounding diagnostics model are explicit adapters; Compose is not rendered.
Complete HmaDiagnostics behavior is exercised separately by android_hma_test.py.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/waenhancer'


def braces(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    dashboard = JAVA / 'ui/screens/dashboard/MainDashboardScreen.kt'
    diagnostics = JAVA / 'utils/HmaDiagnostics.kt'
    ui = dashboard.read_text()
    enum = braces(diagnostics.read_text(), 'enum class HmaState')
    value = braces(ui, 'value = when (hmaReport?.state)').removeprefix('value = ')
    healthy = braces(ui, 'healthy = when (hmaReport?.state)').removeprefix('healthy = ')
    loading = re.search(r'(?m)^\s*isLoading = (hmaReport == null[^\n]+),$', ui).group(1)
    conditions = ui[ui.index('val isCriticalError ='):ui.index('val healthColor =')]
    resources = sorted(set(re.findall(r'R\.string\.(\w+)', value)))
    declared = {item.attrib.get('name') for file in (ROOT / 'app/src/main/res/values').glob('*.xml')
                for item in ET.parse(file).getroot() if item.tag == 'string'}
    assert set(resources) <= declared
    source = '''package com.waenhancer.utils
object HmaDiagnostics {
ENUM
data class HmaReport(val state: HmaState)
}
object R { object string { RESOURCES } }
fun stringResource(value: String) = value
data class Framework(val name: String)
data class Diagnostics(val moduleActive: Boolean, val framework: Framework)
data class Summary(val healthy: Boolean?)
fun label(hmaReport: HmaDiagnostics.HmaReport?): String = VALUE
fun healthy(hmaReport: HmaDiagnostics.HmaReport?): Boolean? = HEALTHY
fun loading(hmaReport: HmaDiagnostics.HmaReport?): Boolean = LOADING
fun warning(hmaReport: HmaDiagnostics.HmaReport?): Boolean {
val diagnostics: Diagnostics? = Diagnostics(true, Framework("Detected"))
val targetSummary = Summary(true)
CONDITIONS
return isWarning
}
fun main() {
var checks = 0
fun verify(value: Boolean) { check(value); checks++ }
for (state in HmaDiagnostics.HmaState.entries) {
val report = HmaDiagnostics.HmaReport(state)
val expected: Boolean? = when (state.name) {
"BLOCKING_TARGETS" -> false
"CLEAN", "NOT_ACTIVE", "NOT_CONFIGURED" -> true
else -> null
}
verify(healthy(report) == expected)
verify(loading(report) == (state.name == "CHECKING"))
verify(warning(report) == (state.name == "UNAVAILABLE"))
}
val unavailable = HmaDiagnostics.HmaReport(HmaDiagnostics.HmaState.valueOf("UNAVAILABLE"))
verify(label(unavailable) == "waex_ui_unavailable_ca184496")
verify(healthy(null) == null)
verify(loading(null))
println("HMA_DASHBOARD_CHECKS=$checks")
}
'''
    replacements = {
        'ENUM': enum,
        'RESOURCES': '\n'.join('const val ' + key + ' = "' + key + '"' for key in resources),
        'VALUE': value, 'HEALTHY': healthy, 'LOADING': loading, 'CONDITIONS': conditions,
    }
    for key, replacement in replacements.items():
        source = source.replace(key, replacement)
    with tempfile.TemporaryDirectory(prefix='waex-hma-dashboard-') as directory:
        out = Path(directory)
        (out / 'Checks.kt').write_text(source)
        subprocess.run([shutil.which('kotlinc') or 'kotlinc', '-jvm-target', '17',
                        str(out / 'Checks.kt'), '-include-runtime', '-d', str(out / 'checks.jar')],
                       check=True, timeout=90)
        subprocess.run(['java', '-jar', str(out / 'checks.jar')], check=True, timeout=30)
    print('SOURCE_SHA256=' + json.dumps({str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                       for p in (dashboard, diagnostics)}, sort_keys=True))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Execute the complete public WAEX theme with real Compose and Android densities.

Requires JDK 21, the Android SDK and network on first use. The isolated Android
library takes dependency versions from this checkout and never loads :licensing.
This checks logical-to-physical dimensions, not glyph rasterization or an APK.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DESIGN = Path('app/src/main/java/com/waenhancer/ui/designsystem')
SOURCES = [DESIGN / (name + '.kt') for name in (
    'WaexTheme', 'WaexTypography', 'WaexSpacing', 'WaexRadius', 'WaexColors', 'WaexElevation')]
FIXTURE = Path('qa/fixtures/InterfaceScaleThemeTest.kt')
FONTS = [Path('app/src/main/res/font') / (name + '.ttf')
         for name in ('outfit_regular', 'outfit_bold')]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--root', type=Path, default=ROOT)
    ap.add_argument('--out', type=Path, required=True)
    a = ap.parse_args(); root = a.root.resolve(); out = a.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    evidence = out / 'evidence'; evidence.mkdir()
    project = out / 'fixture'; project.mkdir()
    probe = project / 'probe'; probe.mkdir()
    versions_path = Path('gradle/libs.versions.toml')
    v = tomllib.loads((root / versions_path).read_text())['versions']
    (project / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "interface-scale-fixture"
include(":probe")
''')
    (project / 'gradle.properties').write_text('android.useAndroidX=true\nandroid.builtInKotlin=false\nandroid.newDsl=false\norg.gradle.jvmargs=-Xmx2g\n')
    build = '''import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("com.android.library") version "@AGP@"
    id("org.jetbrains.kotlin.android") version "@KOTLIN@"
    id("org.jetbrains.kotlin.plugin.compose") version "@KOTLIN@"
}
android {
    namespace = "com.waenhancer.app"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:@BOM@"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.core:core-ktx:@CORE@")
    implementation("androidx.activity:activity-compose:@ACTIVITY@")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:@LIFECYCLE@")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:@COROUTINES@")
    testImplementation("org.robolectric:robolectric:4.17") {
        // This unused optional annotation helper carries newer Kotlin metadata.
        // Keep Robolectric's helper on its runtime classpath, outside Kotlin 2.0's
        // compile classpath; no metadata-version check is disabled.
        exclude(group = "com.google.testparameterinjector", module = "test-parameter-injector")
    }
    testRuntimeOnly("com.google.testparameterinjector:test-parameter-injector:1.22")
    testImplementation("junit:junit:4.13.2")
}
tasks.register("writeQaDependencyHashes") {
    doLast {
        // AGP exposes multiple secondary artifacts for the local :probe project.
        // Its source identity is recorded separately; resolve external modules
        // without making an ambiguous legacy selection of the project's outputs.
        val artifacts = configurations.getByName("debugUnitTestRuntimeClasspath")
            .incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier }
            }.artifacts.artifacts.sortedBy { it.id.componentIdentifier.toString() + it.file.name }
        check(artifacts.isNotEmpty()) { "No external dependency identities were resolved" }
        val lines = artifacts.map { artifact ->
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            "${artifact.id.componentIdentifier}\\t${artifact.file.name}\\t$hash"
        }
        rootProject.file("../evidence/dependency-sha256.tsv").writeText(lines.joinToString("\\n") + "\\n")
    }
}
'''
    for key, value in {'AGP': v['androidGradlePlugin'], 'KOTLIN': v['kotlin'],
                       'BOM': v['androidxComposeBom'], 'CORE': v['androidxCore'],
                       'ACTIVITY': v['androidxActivity'], 'LIFECYCLE': v['androidxLifecycle'],
                       'COROUTINES': v['coroutines']}.items():
        build = build.replace('@' + key + '@', value)
    (probe / 'build.gradle.kts').write_text(build)
    (evidence / 'build.gradle.kts').write_text(build)
    main = probe / 'src/main'; main.mkdir(parents=True)
    (main / 'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>')
    identities = {}
    for source in SOURCES + FONTS + [FIXTURE, versions_path]:
        content = (root / source).read_bytes()
        identities[str(source)] = hashlib.sha256(content).hexdigest()
        snapshot = evidence / 'sources' / source
        snapshot.parent.mkdir(parents=True, exist_ok=True); snapshot.write_bytes(content)
        if source in SOURCES or source in FONTS:
            target = main / source.relative_to('app/src/main')
        elif source == FIXTURE:
            target = probe / 'src/test/java/com/waenhancer/fixture' / source.name
        else:
            continue
        target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(content)
    (evidence / 'source-sha256.json').write_text(json.dumps(identities, indent=2))
    command = ['bash', str(root / 'gradlew'), '-p', str(project), '--no-daemon',
               ':probe:writeQaDependencyHashes', ':probe:testDebugUnitTest']
    (evidence / 'command.json').write_text(json.dumps(command, indent=2))
    returncode = None; failure = None
    try:
        with (evidence / 'framework-build.log').open('w') as log:
            returncode = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=900).returncode
    except (OSError, subprocess.TimeoutExpired) as exc:
        failure = type(exc).__name__ + ': ' + str(exc)
    for report in (probe / 'build/test-results/testDebugUnitTest').glob('*.xml'):
        shutil.copy2(report, evidence / report.name)
    counts = [0, 0, 0, 0]
    for report in evidence.glob('TEST-*.xml'):
        suite = ET.parse(report).getroot()
        for index, key in enumerate(['tests', 'failures', 'errors', 'skipped']):
            counts[index] += int(suite.get(key, 0))
    result = dict(zip(['tests', 'failures', 'errors', 'skipped'], counts))
    result.update({'build_returncode': returncode, 'failure': failure,
                   'android_api': [24, 28, 34], 'interface_sizes': ['Compact', 'Standard', 'Comfortable', 'Large'],
                   'limits': 'Complete production theme and six design-system files; real Compose, Material3, Android Density and recomposition. Dimension conversion only, no glyph rasterization, full APK or device/LSPosed execution.'})
    (evidence / 'result.json').write_text(json.dumps(result, indent=2))
    if returncode != 0 or counts != [12, 0, 0, 0]:
        print((evidence / 'framework-build.log').read_text()[-22000:])
        raise RuntimeError('Interface scale framework failed: ' + json.dumps(result))
    print('PASS: ' + json.dumps(result))


if __name__ == '__main__':
    main()

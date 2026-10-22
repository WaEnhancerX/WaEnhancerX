#!/usr/bin/env python3
"""Measure the exact dashboard counter and bottom-navigation layout expressions.

Kotlin/compiler-plugin versions are those declared by the checkout. Gradle uses
its real Android/JVM metadata and the project's Compose BOM. Robolectric executes
Compose Text/Row/Column/BoxWithConstraints and native Android text on API 28/34.
The surrounding app data, navigation and string lookup are fixture boundaries;
the two layout expressions are copied verbatim. This is not an APK build, the
complete manager screen, WhatsApp injection or on-device accessibility testing.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET
import zipfile

import android_media_preview_test as framework

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('app/src/main/java/com/waenhancer')
NAV = JAVA/'ui/navigation/Navigation.kt'
DASH = JAVA/'ui/screens/dashboard/MainDashboardScreen.kt'
DESIGN = JAVA/'ui/designsystem'
FIXTURE = ROOT/'qa/fixtures/manager_layout'
LABEL_KEYS = ['nav_home', 'nav_controls', 'nav_media', 'nav_automation', 'nav_styles',
              'view_all', 'injected_hooks_active_count']
COMPOSE_PLUGIN_VERSION = '2.0.20'
COMPOSE_PLUGIN_SHA256 = '79ecd166c37597fefdd31f55a34492637721ec416f53b924bede67b76371754a'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_dependencies(args, out, versions):
    if args.deps:
        return args.deps.resolve()
    project = out/'dependency-project'
    project.mkdir()
    template = (FIXTURE/'dependencies.gradle').read_text()
    for token, value in {'BOM': versions['androidxComposeBom'], 'ACTIVITY': versions['androidxActivity'],
            'LIFECYCLE': versions['androidxLifecycle'], 'CORE': versions['androidxCore'],
            'COROUTINES': versions['coroutines']}.items():
        template = template.replace('@'+token+'@', value)
    (project/'build.gradle').write_text(template)
    (project/'settings.gradle').write_text("rootProject.name='waex-manager-layout-dependencies'\n")
    (project/'gradle.properties').write_text('org.gradle.daemon=false\norg.gradle.jvmargs=-Xmx768m\norg.gradle.workers.max=2\n')
    command = [str(args.gradle)] if args.gradle else ['bash', str(ROOT/'gradlew')]
    command += ['--no-daemon', '--console=plain', '-p', str(project), 'copyQaDependencies']
    (project/'command.json').write_text(json.dumps(command, indent=2)+'\n')
    framework.run(command, project/'resolve.log', timeout=600)
    return project/'deps'


def extract(source, start_marker, end_marker, name):
    if source.count(start_marker) != 1:
        raise RuntimeError('Layout start marker must be unique: '+name)
    start = source.index(start_marker) + len(start_marker)
    end = source.index(end_marker, start)
    expression = source[start:end]
    return expression, {'first_line': source[:start].count('\n')+1,
        'last_line': source[:end].count('\n')+1,
        'sha256': hashlib.sha256(expression.encode()).hexdigest()}


def prepare_resources(deps, stage):
    """AAR resource ID classes, not substituted Compose implementations.

    These fragments use no library XML drawable/layout resources. AAR tags need
    unique IDs, so generate R from the actual published symbol tables. Manager
    labels and font files are loaded by explicit fixture resource boundaries.
    """
    jars = list(deps.glob('*.jar'))
    sources = []
    resource_id = 0x7f010000
    for archive in sorted(deps.glob('*.aar')):
        with zipfile.ZipFile(archive) as bundle:
            for item in bundle.namelist():
                if item == 'classes.jar' or (item.startswith('libs/') and item.endswith('.jar')):
                    target = stage/'aar-jars'/(archive.stem+'-'+item.replace('/', '_'))
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(bundle.read(item)); jars.append(target)
            if 'R.txt' not in bundle.namelist():
                continue
            package = ET.fromstring(bundle.read('AndroidManifest.xml')).attrib.get('package')
            if not package:
                continue
            groups = {}
            for row in bundle.read('R.txt').decode().splitlines():
                kind, group, name, value = row.split(' ', 3)
                if kind == 'int' and group != 'styleable':
                    resource_id += 1
                    value = hex(resource_id)
                groups.setdefault(group, []).append(f'public static final {kind} {name}={value};')
            path = stage/'resource-ids'/Path(package.replace('.', '/'))/'R.java'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package '+package+'; public final class R {'+
                ''.join('public static final class '+group+' {'+''.join(rows)+'}'
                    for group, rows in groups.items())+'}')
            sources.append(path)
    return sorted(jars), sorted(set(sources))


def resources(root, stage):
    maps = {}
    identity = {}
    for language, folder in [('en', 'values'), ('es', 'values-es')]:
        labels = {}
        for path in sorted((root/'app/src/main/res'/folder).glob('*.xml')):
            for item in ET.parse(path).getroot().findall('string'):
                if item.attrib['name'] in LABEL_KEYS:
                    identity[str(path.relative_to(root))] = sha(path)
                    text = ''.join(item.itertext())
                    # AAPT strips quoted resource delimiters and Android escapes.
                    # These seven plain labels contain no spans or resource links.
                    if text.startswith('"') and text.endswith('"'):
                        text = text[1:-1]
                    text = re.sub(r'\\([\\\'"$])', r'\1', text)
                    labels[item.attrib['name']] = text
        if set(labels) != set(LABEL_KEYS):
            raise RuntimeError('Missing production labels in '+folder)
        maps[language] = labels
    r = stage/'resource-ids/com/waenhancer/app/R.java'
    r.parent.mkdir(parents=True, exist_ok=True)
    r.write_text('package com.waenhancer.app; public final class R {public static final class string {'+
        ''.join(f'public static final int {key}={index+1};' for index, key in enumerate(LABEL_KEYS))+'}}')
    def kotlin_string(value):
        return json.dumps(value, ensure_ascii=False).replace('$', '\\$')
    labels_file = stage/'FixtureLabels.kt'
    labels_file.write_text('package com.waenhancer.fixture\nimport com.waenhancer.app.R\n'
        'internal object FixtureLabels { fun forLanguage(language:String):Map<Int,String> = when(language) {\n'+
        ''.join(kotlin_string(language)+' -> mapOf('+', '.join('R.string.'+key+' to '+kotlin_string(value)
            for key, value in labels.items())+')\n' for language, labels in maps.items())+
        'else -> error("Unsupported fixture language")\n}}\n')
    return r, labels_file, identity


def execute(root, out, deps, compiler, kotlin_home, args, baseline):
    out.mkdir(parents=True)
    stage, classes = out/'sources', out/'classes'
    stage.mkdir(); classes.mkdir()
    snapshots = [NAV, DASH] + [DESIGN/(name+'.kt') for name in
        ('WaexTypography', 'WaexRadius', 'WaexColors', 'WaexIcons')]
    identities = {}
    for path in snapshots:
        # Shared style sources are unchanged by these fixes; a minimal V39
        # snapshot may carry only the two baseline screen files.
        source = root/path if (root/path).is_file() else ROOT/path
        target = stage/path; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target); identities[str(path)] = sha(target)
    nav_source, dash_source = (stage/NAV).read_text(), (stage/DASH).read_text()
    bottom, nav_range = extract(nav_source, '        bottomBar = {\n            if (isRoot) {\n',
        '\n            }\n        },', 'bottom navigation')
    counter, counter_range = extract(dash_source,
        '                                        val totalCount = app.injectedHooks.size\n\n',
        '\n                                    }\n                                }\n                            }', 'hook counter')
    (out/'layout-ranges.json').write_text(json.dumps({str(NAV): nav_range, str(DASH): counter_range}, indent=2)+'\n')
    header = '''package com.waenhancer.fixture
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.waenhancer.app.R
import com.waenhancer.ui.designsystem.*
import kotlinx.coroutines.launch
'''
    fragment_file = stage/'LayoutFragments.kt'
    fragment_file.write_text(header+'\n@Composable internal fun BottomFragment() {\n'
        'val colors=lightColors;val typography=WaexTypography();val currentScreen:Screen=Screen.MainDashboard\n'
        'val coroutineScope=rememberCoroutineScope();val pagerState=remember { FixturePager() }\n'+bottom+
        '\n}\n@Composable internal fun CounterFragment() {\n'
        'val colors=lightColors;val typography=WaexTypography();val radius=WaexRadius()\n'
        'val activeCount=58;val totalCount=58;val app=FixtureApp();val navController=remember { FixtureNavigation() }\n'+counter+'\n}\n')
    jars, java_sources = prepare_resources(deps, stage)
    # The complete style implementation is compiled. Only its font-resource
    # loader changes from Android R IDs to the identical checked-in TTF files.
    typography = stage/DESIGN/'WaexTypography.kt'
    style = typography.read_text().replace('import com.waenhancer.app.R\n', 'import java.io.File\n')
    for kind in ('regular', 'bold'):
        source = ROOT/'app/src/main/res/font'/('outfit_'+kind+'.ttf')
        font = stage/'fonts'/source.name; font.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, font); identities[str(source.relative_to(ROOT))] = sha(font)
        style = style.replace('R.font.outfit_'+kind,
            'File(System.getProperty("waex.fixture.fonts"), "'+source.name+'")')
    typography.write_text(style)
    resource_r, label_source, resource_ids = resources(ROOT, stage)
    identities.update(resource_ids); java_sources.append(resource_r)
    tests = []
    for name in ('ManagerLayoutSupport.kt', 'ManagerLayoutTest.kt'):
        target = stage/name; shutil.copyfile(FIXTURE/name, target); tests.append(target)
        identities[str((FIXTURE/name).relative_to(ROOT))] = sha(target)
    android_api = next(p for p in jars if p.name == 'android-all-14-robolectric-10818077.jar')
    cp = os.pathsep.join(map(str, [classes]+jars))
    framework.run(['javac', '--release', '17', '-cp', str(android_api), '-d', str(classes),
                   *map(str, java_sources)], out/'javac-resource-ids.log')
    # The optional Robolectric parameter injector is a test-runner helper with
    # newer Kotlin metadata. Keep it on runtime only, as in the existing theme
    # fixture. No app dependency or metadata compatibility check is bypassed.
    compile_jars = [p for p in jars if not p.name.startswith('test-parameter-injector-')]
    compile_cp = os.pathsep.join(map(str, [classes]+compile_jars))
    framework.run([str(compiler), '-jvm-target', '17', '-Xplugin='+str(args.compose_plugin),
        '-cp', compile_cp, '-d', str(classes), str(fragment_file), str(label_source),
        *[str(stage/p) for p in snapshots if p.parent == DESIGN], *map(str, tests)], out/'kotlinc-compose.log', timeout=300)
    command = ['java', '-Xmx1g', '-Dwaex.fixture.fonts='+str(stage/'fonts')]
    if args.sdk_jars:
        command += ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir='+str(args.sdk_jars.resolve())]
    command += ['-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
        '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.util=ALL-UNNAMED',
        '--add-opens=java.base/java.io=ALL-UNNAMED', '-cp', cp,
        'org.junit.runner.JUnitCore', 'com.waenhancer.fixture.ManagerLayoutTest']
    status = framework.run(command, out/'junit.log', allow_failure=True, timeout=300)
    log = (out/'junit.log').read_text()
    failure_count = len(re.findall(r'^\d+\) ', log, re.M))
    passed = 'OK (10 tests)' in log
    valid = passed and status == 0 if not baseline else (
        status != 0 and 'Tests run: 10,  Failures: 6' in log and failure_count == 6 and
        log.count('\njava.lang.AssertionError') == 6)
    result = {'baseline': baseline, 'source_root': str(root), 'tests': 10, 'failures': failure_count,
        'exit_code': status, 'accepted': valid, 'source_sha256': identities,
        'layout_ranges': {str(NAV): nav_range, str(DASH): counter_range}, 'scope': __doc__}
    result['qa_sha256'] = {str(p.relative_to(ROOT)): sha(p) for p in
        [Path(__file__), FIXTURE/'dependencies.gradle', ROOT/'qa/android_media_preview_test.py',
         ROOT/'qa/maven_dependencies.py']}
    (out/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    if not valid:
        print(log[-22000:])
        raise RuntimeError('Unexpected Compose layout result; infrastructure failure is not a behavioral negative control')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--baseline-root', type=Path)
    parser.add_argument('--deps', type=Path, help='Reuse flat project dependencies with ../resolved-sha256.json from the Gradle fixture')
    parser.add_argument('--gradle', type=Path, help='Gradle binary for dependency resolution; default uses the checked-in wrapper')
    parser.add_argument('--sdk-jars', type=Path)
    parser.add_argument('--compose-plugin', type=Path, help='Pinned non-embeddable Kotlin Compose compiler plugin 2.0.20; otherwise downloaded from Maven Central')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('--out must be empty')
    out.mkdir(parents=True, exist_ok=True)
    home, compiler = framework.find_kotlin()
    version = tomllib.loads((ROOT/'gradle/libs.versions.toml').read_text())['versions']
    if version['kotlin'] != COMPOSE_PLUGIN_VERSION:
        raise RuntimeError('Update the pinned Compose plugin and QA for the checkout Kotlin version')
    if args.compose_plugin:
        args.compose_plugin = framework.verify_api(args.compose_plugin.resolve(), COMPOSE_PLUGIN_SHA256, 4*1024*1024)
    else:
        args.compose_plugin = framework.download_pinned(
            'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/'+
            COMPOSE_PLUGIN_VERSION+'/kotlin-compose-compiler-plugin-'+COMPOSE_PLUGIN_VERSION+'.jar',
            out/'compiler/kotlin-compose-compiler-plugin.jar', COMPOSE_PLUGIN_SHA256, 4*1024*1024)
    actual = subprocess.run([str(compiler), '-version'], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    if 'kotlinc-jvm '+version['kotlin']+' ' not in actual:
        raise RuntimeError('The compiler must match the checkout: '+actual)
    (out/'toolchain.json').write_text(json.dumps({'compiler_version_output': actual,
        'compose_compiler_plugin_sha256': sha(args.compose_plugin),
        'compose_compiler_coordinates': 'org.jetbrains.kotlin:kotlin-compose-compiler-plugin:'+COMPOSE_PLUGIN_VERSION,
        'kotlin': version['kotlin'], 'compose_bom': version['androidxComposeBom']}, indent=2)+'\n')
    deps = resolve_dependencies(args, out, version)
    manifest = deps.parent/'resolved-sha256.json'
    artifacts = json.loads(manifest.read_text())
    for artifact in artifacts:
        if Path(artifact['file']).name != artifact['file'] or sha(deps/artifact['file']) != artifact['sha256']:
            raise RuntimeError('Resolved dependency identity mismatch: '+artifact['file'])
        if '-desktop:' in artifact['module']:
            raise RuntimeError('Desktop artifacts are not the Android project graph')
    shutil.copyfile(manifest, out/'resolved-dependency-sha256.json')
    (out/'dependency-sha256.json').write_text(json.dumps({str(p): sha(p) for p in sorted(deps.glob('*'))
        if p.suffix in ('.jar', '.aar')}, indent=2)+'\n')
    if args.sdk_jars:
        (out/'sdk-sha256.json').write_text(json.dumps({p.name: sha(p) for p in sorted(args.sdk_jars.glob('*.jar'))}, indent=2)+'\n')
    results = []
    if args.baseline_root:
        results.append(execute(args.baseline_root.resolve(), out/'baseline', deps, compiler, home, args, True))
    results.append(execute(args.source_root.resolve(), out/'candidate', deps, compiler, home, args, False))
    (out/'results.json').write_text(json.dumps({'results': results}, indent=2)+'\n')
    print('PASS: real Compose fragment measurement; not a complete manager or APK build.')


if __name__ == '__main__':
    main()

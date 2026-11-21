#!/usr/bin/env python3
"""Run extracted production row methods on Robolectric Android (not LSPosed).

Requires Java 21+, javac, kotlinc, real SDK AAPT2 and Maven/network on first use.
Dependencies are version-pinned. A resource-only synthetic host APK is linked;
no application APK, private licensing module, signing or account is used.
Use --baseline-root only for a source with the same extracted renderer contract;
it must fail behaviorally, not fail extraction, compilation or framework setup.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RENDERER = Path('app/src/main/java/com/waenhancer/xposed/features/customization/OnlinePresenceIndicatorsHook.kt')
STORE = Path('app/src/main/java/com/waenhancer/xposed/features/automation/PresenceStateStore.java')

def extract(text, marker):
    if text.count(marker) != 1:
        raise ValueError('Expected one production declaration: ' + marker)
    start = text.index(marker)
    opening = text.index('{', start)
    depth, state, i = 0, 'code', opening
    while i < len(text):
        c, pair = text[i], text[i:i+2]
        if state == 'line':
            if c == '\n': state = 'code'
        elif state == 'comment':
            if pair == '*/': state = 'code'; i += 1
        elif state in ('string', 'char'):
            if c == '\\': i += 1
            elif c == ('"' if state == 'string' else "'"): state = 'code'
        elif pair == '//': state = 'line'; i += 1
        elif pair == '/*': state = 'comment'; i += 1
        elif c == '"': state = 'string'
        elif c == "'": state = 'char'
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return text[start:i+1]
        i += 1
    raise ValueError('Unclosed source declaration: ' + marker)

POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>org.waex.qa</groupId><artifactId>presence-ui-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://maven.google.com</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
</dependencies></project>'''

RENDER_METHODS = ('bindRow', 'ensureDotView', 'ensureTextView', 'render',
                  'recordIdentityAlias', 'queueBindRow', 'queuePresenceRefresh',
                  'clearRow', 'stateForRow')
RENDER_FIELDS = ('mainHandler', 'visibleRows', 'pendingBindings', 'refreshPending')
SDK_LEVELS = (28, 34, 36)
COMPILE_JARS = (
    'android-all-14-robolectric-10818077.jar', 'annotations-4.17.jar',
    'junit-4.13.2.jar', 'junit-4.17.jar', 'robolectric-4.17.jar',
    'sandbox-4.17.jar', 'shadowapi-4.17.jar', 'shadows-framework-4.17.jar',
)

def harness(source, baseline=False):
    imports = '\n'.join(line for line in source.splitlines() if line.startswith('import ') and
                         (line.startswith('import android.') or line.startswith('import java.') or
                          'PresenceStateStore' in line or 'import com.waenhancer.app.R' in line or 'import com.waenhancer.utils.UiText' in line))
    # Baseline and candidate must execute the same methods and expectations.
    # These bodies are copied verbatim; accessors below cannot create/size/render views.
    methods = '\n\n'.join(extract(source, 'private fun ' + name + '(') for name in RENDER_METHODS)
    dp = next(line for line in source.splitlines() if 'private fun dp(' in line)
    companion = extract(source, 'companion object {')
    fields = []
    for name in RENDER_FIELDS:
        matches = [line.strip() for line in source.splitlines()
                   if line.strip().startswith('private val ' + name + ' =')]
        if len(matches) != 1:
            raise ValueError('Expected one production renderer field: ' + name)
        fields.append(matches[0])
    fields = '\n'.join(fields)
    # Explicit adapter: discovery/Xposed hook installation is outside this renderer test.
    listener = 'init { PresenceStateStore.addListener { _, _ -> queuePresenceRefresh() } }'
    return imports + '''
class RendererHarness(private val context: Context, private val showDot: Boolean = true,
                      private val showText: Boolean = true) {
''' + fields + '\n' + listener + '\n' + methods + '\n' + dp + '\n' + companion + '''
    fun bind(row: View, jid: String) = bindRow(row, jid)
    fun alias(alias: String, canonical: String) = recordIdentityAlias(alias, canonical)
    fun dot(row: View): View? = row.findViewById<View>(ID_DOT)
    fun text(row: View): TextView? = row.findViewById<TextView>(ID_TEXT)
    fun bound(row: View): Any? = row.getTag(TAG_BOUND_JID)
    fun queue(row: View, jid: String?) = queueBindRow(row, jid)
    fun refresh() = queuePresenceRefresh()
    fun visibleTracking(): MutableMap<View, String> = visibleRows
    fun pendingTracking(): MutableMap<View, Any> = pendingBindings
}\n'''

TESTS = (Path(__file__).parent / 'fixtures/PresenceRowRenderingTest.kt').read_text(encoding='utf-8')
TEST_NAMES = re.findall(r'@Test\s+fun\s+([A-Za-z0-9_]+)', TESTS)
if not TEST_NAMES or len(set(TEST_NAMES)) != len(TEST_NAMES):
    raise ValueError('Expected a nonempty set of unique renderer test scenarios')
EXPECTED_TESTS = len(TEST_NAMES) * len(SDK_LEVELS)

def behavioral_failures(log, result, expected_tests, test_names):
    """Reject failed setup/compilation and require every JUnit failure to be an assertion."""
    summaries=re.findall(r'Tests run: (\d+),\s+Failures: (\d+)', log)
    if result == 0 or len(summaries) != 1:
        raise RuntimeError('Negative must exit nonzero with one complete JUnit summary')
    count, failed=map(int,summaries[0])
    if count != expected_tests or failed == 0:
        raise RuntimeError('Negative must execute every declared scenario and fail assertions')
    if any(marker in log for marker in ('initializationError', 'ClassNotFoundException',
            'UnsupportedClassVersionError', 'PresenceRowRenderingTest.reset(')):
        raise RuntimeError('Framework/setup errors do not qualify as behavioral rejection')
    headers=list(re.finditer(r'^\d+\) ([A-Za-z0-9_]+)(?:\[[^\]]+\])?\(PresenceRowRenderingTest\)\r?\n', log, re.M))
    if len(headers) != failed:
        raise RuntimeError('Every failure must identify a declared behavioral test scenario')
    names=[]
    for index, header in enumerate(headers):
        name=header.group(1)
        body=log[header.end():headers[index+1].start() if index+1 < len(headers) else len(log)]
        if name not in test_names or not re.match(r'(?:java\.lang\.AssertionError|org\.junit\.ComparisonFailure)(?::|\r?\n)',body):
            raise RuntimeError('Negative failed outside an expected JUnit assertion: '+name)
        if 'PresenceRowRenderingTest.'+name+'(' not in body:
            raise RuntimeError('Assertion did not originate from its declared test: '+name)
        names.append(name)
    return names

def find_aapt2(explicit=None):
    if explicit:
        path = Path(explicit).resolve()
        if not path.is_file(): raise RuntimeError('AAPT2 file missing: ' + str(path))
        return path
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    names = ['aapt2.exe'] if os.name == 'nt' else ['aapt2']
    candidates = [p for name in names for p in (Path(sdk)/'build-tools').glob('*/'+name)] if sdk else []
    if not candidates:
        raise RuntimeError('Real SDK AAPT2 required: pass --aapt2 or configure ANDROID_SDK_ROOT')
    return sorted(candidates, key=lambda p: tuple(map(int, re.findall(r'\d+', p.parent.name))))[-1]

def fresh_output(path):
    """Create a new evidence directory; never overwrite a previous run."""
    path = Path(path).resolve()
    try:
        path.mkdir(parents=True, exist_ok=False)
    except FileExistsError as exc:
        raise RuntimeError('Refusing to overwrite existing evidence: ' + str(path)) from exc
    return path

def prepare_runtime_dependencies(deps, out):
    """Prepare a complete, explicit runtime classpath for this run only.

    Both entrypoints must extract their own AAR classes. An existing positive
    run's aar-jars directory is not an implicit dependency of the companion.
    Reused input directories are read-only, even if they contain old extractions.
    """
    deps, out = Path(deps).resolve(), Path(out).resolve()
    jars = sorted(deps.glob('*.jar'))
    if not jars:
        raise RuntimeError('Dependency directory contains no JAR files: ' + str(deps))
    extracted_root = fresh_output(out/'aar-jars')
    inputs = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(deps.iterdir()) if p.is_file()}
    (out/'dependency-sha256.json').write_text(json.dumps(inputs, indent=2), encoding='utf-8')
    extracted, output_names = [], set()
    for archive in sorted(deps.glob('*.aar')):
        with zipfile.ZipFile(archive) as z:
            members = [entry for entry in z.infolist() if entry.filename == 'classes.jar'
                       or (entry.filename.startswith('libs/') and entry.filename.endswith('.jar'))]
            for entry in members:
                name = archive.stem + '-' + entry.filename.replace('/', '_')
                if name.casefold() in output_names:
                    raise RuntimeError('Ambiguous extracted AAR output name: ' + name)
                output_names.add(name.casefold())
                contents = z.read(entry)
                previous = deps/name
                if previous.is_file():
                    if previous.read_bytes() != contents:
                        raise RuntimeError('Reused extracted dependency differs from AAR: ' + str(previous))
                    # Always use this run's verified extraction, not a warm
                    # cache's copy that a fresh CI dependency resolution lacks.
                    jars.remove(previous)
                target = extracted_root/name
                with target.open('xb') as stream:
                    stream.write(contents)
                extracted.append({'path': str(target),
                                  'sha256': hashlib.sha256(contents).hexdigest(),
                                  'archive': str(archive),
                                  'archive_sha256': inputs[archive.name],
                                  'member': entry.filename})
    runtime = [{'path': str(p), 'sha256': inputs[p.name]} for p in jars] + extracted
    (out/'runtime-classpath.json').write_text(json.dumps(runtime, indent=2), encoding='utf-8')
    return tuple(Path(entry['path']) for entry in runtime)

def build_host_resources(out, aapt2):
    out = fresh_output(out)
    resources = out/'res/values'; resources.mkdir(parents=True, exist_ok=True)
    (resources/'ids.xml').write_text('<resources><item name="contact_selector" type="id"/></resources>', encoding='utf-8')
    manifest = out/'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.waex.qa.presence.host"/>', encoding='utf-8')
    run([str(aapt2), 'version'], out/'aapt2-version.log')
    run([str(aapt2), 'compile', '--dir', str(out/'res'), '-o', str(out/'compiled.zip')], out/'aapt2-compile.log')
    apk = out/'presence-host-resources.apk'
    run([str(aapt2), 'link', '--manifest', str(manifest), '-o', str(apk), str(out/'compiled.zip')], out/'aapt2-link.log')
    run([str(aapt2), 'dump', 'resources', str(apk)], out/'aapt2-dump.log')
    dumped = (out/'aapt2-dump.log').read_text(encoding='utf-8')
    if 'org.waex.qa.presence.host' not in dumped or 'id/contact_selector' not in dumped:
        raise RuntimeError('Synthetic host resource identity was not linked correctly')
    (out/'identity.json').write_text(json.dumps({'package':'org.waex.qa.presence.host',
        'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(), 'aapt2':str(aapt2),
        'scope':'Synthetic resource table only; never installed; not a WhatsApp APK'},indent=2), encoding='utf-8')
    return apk

def execute(root, out, deps, baseline, host_resources, runtime_jars, sdk_dir=None, mutation=None):
    out = fresh_output(out)
    source=(root/RENDERER).read_text(encoding='utf-8')
    if mutation:
        old,new = MUTATIONS[mutation]
        if source.count(old) != 1: raise ValueError('Mutation anchor must match exactly once: '+mutation)
        source=source.replace(old,new)
    (out/'RendererHarness.kt').write_text(harness(source,baseline),encoding='utf-8')
    (out/'PresenceRowRenderingTest.kt').write_text(TESTS,encoding='utf-8')
    classes=out/'classes'; classes.mkdir(exist_ok=True)
    if not runtime_jars or any(not Path(p).is_file() for p in runtime_jars):
        raise RuntimeError('Every prepared runtime dependency must exist')
    cp=os.pathsep.join(str(p) for p in runtime_jars)
    # Compile only against APIs this fixture imports. Runtime still receives
    # every transitive dependency. In particular, unused test-parameter-injector
    # 1.22 carries Kotlin 2.2 metadata that the project's Kotlin 2.0 compiler
    # cannot consume; no metadata checks are disabled and no runtime JAR removed.
    compile_paths=[deps/name for name in COMPILE_JARS]
    for dependency in compile_paths:
        if not dependency.is_file(): raise RuntimeError('Required compile API missing: '+str(dependency))
    compile_cp=os.pathsep.join(str(p) for p in compile_paths)
    java_sources=[root/STORE]
    if 'UiText.' in source:
        # Numeric R IDs are compile bindings only. Actual UiText selects its
        # explicit English fallback here; resource localization is another gate.
        generated = out/'generated/com/waenhancer/app/R.java'
        generated.parent.mkdir(parents=True, exist_ok=True)
        generated.write_text('package com.waenhancer.app; public final class R { public static final class string { public static final int waex_ui_online=0x7f030001, waex_ui_last_seen_1_s=0x7f030002; }}', encoding='utf-8')
        java_sources.append(generated)
        java_sources += [root/'app/src/main/java/com/waenhancer/utils'/name
                         for name in ('UiText.java','DisplayLocalePolicy.java')]
    run(['javac','--release','17','-classpath',compile_cp,'-d',str(classes),*map(str,java_sources)],out/'javac.log')
    kotlin_args=[str(out/'RendererHarness.kt'),str(out/'PresenceRowRenderingTest.kt'),'-jvm-target','17',
                 '-classpath',compile_cp+os.pathsep+str(classes),'-include-runtime','-d',str(out/'tests.jar')]
    if os.name == 'nt':
        # The Windows batch launcher splits a semicolon classpath passed directly.
        # Kotlin's argument file preserves the exact file list and classpath.
        argfile=out/'kotlinc.args'
        def arg_token(value):
            token=str(value).replace('\\','/')
            return '"'+token.replace('"','\\"')+'"' if any(c.isspace() for c in token) else token
        argfile.write_text('\n'.join(arg_token(value) for value in kotlin_args)+'\n',encoding='utf-8')
        kotlin_args=['@'+str(argfile)]
    run(['kotlinc',*kotlin_args],out/'kotlinc.log')
    opens=['java.base/java.lang','java.base/java.util','java.base/java.io','java.base/java.net',
           'java.base/java.security','java.base/java.text','java.base/jdk.internal.access',
           'java.desktop/java.awt.font','jdk.compiler/com.sun.tools.javac.api']
    sdk_args = ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir='+str(sdk_dir)] if sdk_dir else []
    cmd=['java',*[f'--add-opens={p}=ALL-UNNAMED' for p in opens],*sdk_args,
         '-Dwaex.presence.hostResources='+str(host_resources),
         '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
         '-cp',str(out/'tests.jar')+os.pathsep+str(classes)+os.pathsep+cp,
         'org.junit.runner.JUnitCore','PresenceRowRenderingTest']
    rejection=baseline or mutation is not None
    result=run(cmd,out/'junit.log',allow_failure=rejection,timeout=600)
    log=(out/'junit.log').read_text()
    if rejection:
        failures=behavioral_failures(log, result, EXPECTED_TESTS, TEST_NAMES)
        if mutation and failures.count(MUTATION_ORACLES[mutation]) != len(SDK_LEVELS):
            raise RuntimeError('Mutant must fail its discriminating oracle on every declared Android API')
    elif 'OK ('+str(EXPECTED_TESTS)+' tests)' not in log:
        raise RuntimeError('Expected every declared scenario on all three Android API levels')
    return {'source_root':str(root),'baseline':baseline,'exit_code':result,
            'mutation':mutation,'unique_scenarios':len(TEST_NAMES),'sdk_levels':SDK_LEVELS,
            'scope':'Extracted production renderer/store, real Android layout/Skia, synthetic compiled host IDs; no Xposed/WhatsApp',
            'renderer_sha256':hashlib.sha256((root/RENDERER).read_bytes()).hexdigest(),
            'store_sha256':hashlib.sha256((root/STORE).read_bytes()).hexdigest(),
            'extracted_harness_sha256':hashlib.sha256((out/'RendererHarness.kt').read_bytes()).hexdigest(),
            'test_fixture_sha256':hashlib.sha256(TESTS.encode()).hexdigest()}

MUTATIONS = {
    'wrong_dot_gravity': ('Gravity.BOTTOM or Gravity.END','Gravity.BOTTOM or Gravity.START'),
    'ignore_known_state': ('state == null || !state.known ->','state == null ->'),
    'omit_dot_attachment': ('targetGroup.addView(dot, params)','Unit // negative control: omit normal child attachment'),
}
MUTATION_ORACLES = {
    'wrong_dot_gravity':'rtlFollowsHostDirection',
    'ignore_known_state':'deniedPresenceClearsVisibleDotAndTextWithoutLastSeen',
    'omit_dot_attachment':'exactCachedOnlineMeasuresVisibleDot',
}

def run(cmd,log,allow_failure=False,timeout=300):
    cmd = list(cmd)
    cmd[0] = shutil.which(cmd[0]) or cmd[0]
    start=time.monotonic()
    metadata={'command':cmd,'timeout_seconds':timeout,'exit_code':None,'timed_out':False}
    try:
        with open(log,'w',encoding='utf-8') as f:
            p=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=timeout)
            metadata['exit_code']=p.returncode
    except subprocess.TimeoutExpired:
        metadata['timed_out']=True
        raise
    finally:
        metadata['elapsed_seconds']=round(time.monotonic()-start,6)
        log.with_suffix('.command.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    print(log.name, 'exit='+str(p.returncode),flush=True)
    if p.returncode and not allow_failure:
        print(log.read_text()[-20000:]); raise RuntimeError('Command failed: '+cmd[0])
    return p.returncode

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source-root',type=Path,default=ROOT)
    ap.add_argument('--baseline-root',type=Path)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--aapt2',type=Path)
    ap.add_argument('--deps',type=Path,help='Reuse an existing dependency directory; hashes are still recorded')
    ap.add_argument('--sdk-dir',type=Path,help='Use verified local Robolectric SDK JARs in offline mode')
    ap.add_argument('--mutations',action='store_true',help='Require compiled behavioral rejection of focused renderer mutants')
    args=ap.parse_args(); args.out=fresh_output(args.out)
    for tool in ['java','javac','kotlinc'] + ([] if args.deps else ['mvn']):
        if not shutil.which(tool): raise RuntimeError('Required tool missing: '+tool)
    run(['java','-version'],args.out/'java-version.log')
    run(['javac','-version'],args.out/'javac-version.log')
    run(['kotlinc','-version'],args.out/'kotlinc-version.log')
    (args.out/'pom.xml').write_text(POM)
    deps=args.deps.resolve() if args.deps else args.out/'deps'
    if not args.deps:
        deps.mkdir()
        from maven_dependencies import resolve_dependencies
        resolve_dependencies([shutil.which('mvn'),'-B','-f',str(args.out/'pom.xml'),'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
             '-DoutputDirectory='+str(deps)],args.out/'dependencies.log',timeout=600)
    runtime_jars=prepare_runtime_dependencies(deps,args.out)
    sdk_dir = args.sdk_dir.resolve() if args.sdk_dir else None
    if sdk_dir:
        if not sdk_dir.is_dir(): raise RuntimeError('SDK dependency directory missing: '+str(sdk_dir))
        manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(sdk_dir.glob('*.jar'))}
        if not manifest: raise RuntimeError('SDK dependency directory contains no JAR files')
        (args.out/'sdk-sha256.json').write_text(json.dumps(manifest,indent=2))
    host_resources=build_host_resources(args.out/'host-resources',find_aapt2(args.aapt2))
    results=[execute(args.source_root.resolve(),args.out/'corrected',deps,False,host_resources,runtime_jars,sdk_dir)]
    if args.baseline_root: results.append(execute(args.baseline_root.resolve(),args.out/'baseline',deps,True,host_resources,runtime_jars,sdk_dir))
    if args.mutations:
        for name in MUTATIONS:
            results.append(execute(args.source_root.resolve(),args.out/name,deps,False,host_resources,runtime_jars,sdk_dir,name))
    (args.out/'results.json').write_text(json.dumps(results,indent=2))
    print('PASS: Android framework/Robolectric row tests. NOT a full APK or LSPosed test.')
if __name__=='__main__':
    try: main()
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as exc:
        print('ERROR:',exc,flush=True); raise SystemExit(2)

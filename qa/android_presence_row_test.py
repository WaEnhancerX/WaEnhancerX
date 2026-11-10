#!/usr/bin/env python3
"""Run extracted production row methods on Robolectric Android (not LSPosed).

Requires Java 21, javac, kotlinc and Maven/network on first use. Dependencies are
version-pinned. No APK, private licensing module or signing material is used.
Use --baseline-root to require behavioral rejection of a pre-fix source snapshot.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RENDERER = Path('app/src/main/java/com/waenhancer/xposed/features/customization/OnlinePresenceIndicatorsHook.kt')
STORE = Path('app/src/main/java/com/waenhancer/xposed/features/automation/PresenceStateStore.java')

def extract(text, marker):
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

def harness(source, baseline=False):
    imports = '\n'.join(line for line in source.splitlines() if line.startswith('import ') and
                         (line.startswith('import android.') or line.startswith('import java.') or
                          'PresenceStateStore' in line or 'import com.waenhancer.app.R' in line or 'import com.waenhancer.utils.UiText' in line))
    names = ['bindRow', 'ensureOverlay', 'render', 'recordIdentityAlias']
    if not baseline:
        names += ['queueBindRow', 'queuePresenceRefresh', 'clearRow', 'stateForRow', 'layoutOverlay']
    methods = '\n\n'.join(extract(source, 'private fun ' + name + '(') for name in names)
    dp = next(line for line in source.splitlines() if 'private fun dp(' in line)
    companion = extract(source, 'companion object {')
    fields = '\n'.join(line.strip() for line in source.splitlines()
                       if any(line.strip().startswith('private val ' + name + ' =') for name in
                              ['mainHandler','visibleRows','rowOverlays','pendingBindings','refreshPending']))
    listener = '' if baseline else 'init { PresenceStateStore.addListener { _, _ -> queuePresenceRefresh() } }'
    return imports + '''
class RendererHarness(private val context: Context, private val showDot: Boolean = true,
                      private val showText: Boolean = true) {
''' + fields + '\n' + listener + '\n' + methods + '\n' + dp + '\n' + companion + '''
    fun bind(row: View, jid: String) = bindRow(row, jid)
    fun alias(alias: String, canonical: String) = recordIdentityAlias(alias, canonical)
    fun overlay(row: View): FrameLayout? = ''' + ('rowOverlays[row]' if baseline else 'rowOverlays[row]?.get()') + '''
    fun dot(row: View): View? = overlay(row)?.findViewById<View>(ID_DOT)
    fun text(row: View): TextView? = overlay(row)?.findViewById<TextView>(ID_TEXT)
    fun bound(row: View): Any? = row.getTag(TAG_BOUND_JID)
''' + ('' if baseline else '''
    fun queue(row: View, jid: String?) = queueBindRow(row, jid)
    fun refresh() = queuePresenceRefresh()
    fun weakValues(): Boolean = rowOverlays.values.all { it is java.lang.ref.WeakReference<*> }
''') + '\n}\n'

TESTS = (Path(__file__).parent / 'fixtures/PresenceRowRenderingTest.kt').read_text(encoding='utf-8')
BASELINE_TESTS = TESTS[:TESTS.index('    @Test fun sameOnlineReobserved')] + '\n}\n'

def execute(root, out, deps, baseline):
    out.mkdir(parents=True, exist_ok=True)
    source=(root/RENDERER).read_text(encoding='utf-8')
    (out/'RendererHarness.kt').write_text(harness(source,baseline),encoding='utf-8')
    (out/'PresenceRowRenderingTest.kt').write_text(BASELINE_TESTS if baseline else TESTS,encoding='utf-8')
    classes=out/'classes'; classes.mkdir(exist_ok=True)
    cp=os.pathsep.join(str(p) for p in deps.glob('*.jar'))
    java_sources=[root/STORE]
    if 'UiText.' in source:
        # Actual helper and policy, not an English UiText test replacement. Synthetic
        # R IDs are only compile bindings here; localized resource selection is tested
        # against a real AAPT2 table in verify_runtime_framework.py.
        from localization_support import write_r
        java_sources.append(write_r(root,out/'generated')[0])
        java_sources += [root/'app/src/main/java/com/waenhancer/utils'/name
                         for name in ('UiText.java','DisplayLocalePolicy.java')]
    run(['javac','--release','17','-classpath',cp,'-d',str(classes),*map(str,java_sources)],out/'javac.log')
    run(['kotlinc',str(out/'RendererHarness.kt'),str(out/'PresenceRowRenderingTest.kt'),'-jvm-target','17',
         '-classpath',cp+os.pathsep+str(classes),'-include-runtime','-d',str(out/'tests.jar')],out/'kotlinc.log')
    opens=['java.base/java.lang','java.base/java.util','java.base/java.io','java.base/java.net',
           'java.base/java.security','java.base/java.text','java.base/jdk.internal.access',
           'java.desktop/java.awt.font','jdk.compiler/com.sun.tools.javac.api']
    cmd=['java',*[f'--add-opens={p}=ALL-UNNAMED' for p in opens],
         '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
         '-cp',str(out/'tests.jar')+os.pathsep+str(classes)+os.pathsep+cp,
         'org.junit.runner.JUnitCore','PresenceRowRenderingTest']
    result=run(cmd,out/'junit.log',allow_failure=baseline,timeout=600)
    log=(out/'junit.log').read_text()
    if baseline:
        if result == 0 or 'AssertionError' not in log or 'Tests run: 6,  Failures: 6' not in log:
            raise RuntimeError('Baseline must fail all 6 assertions, not fail to compile/load/run')
    elif 'OK (75 tests)' not in log:
        raise RuntimeError('Expected all 25 scenarios on 3 Android API levels')
    return {'source_root':str(root),'baseline':baseline,'exit_code':result,
            'renderer_sha256':hashlib.sha256((root/RENDERER).read_bytes()).hexdigest(),
            'store_sha256':hashlib.sha256((root/STORE).read_bytes()).hexdigest()}

def run(cmd,log,allow_failure=False,timeout=300):
    with open(log,'w') as f:
        p=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,timeout=timeout)
    print(log.name, 'exit='+str(p.returncode),flush=True)
    if p.returncode and not allow_failure:
        print(log.read_text()[-20000:]); raise RuntimeError('Command failed: '+cmd[0])
    return p.returncode

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source-root',type=Path,default=ROOT)
    ap.add_argument('--baseline-root',type=Path)
    ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args(); args.out=args.out.resolve(); args.out.mkdir(parents=True,exist_ok=True)
    for tool in ['java','javac','kotlinc','mvn']:
        if not shutil.which(tool): raise RuntimeError('Required tool missing: '+tool)
    (args.out/'pom.xml').write_text(POM)
    deps=args.out/'deps'; deps.mkdir(exist_ok=True)
    from maven_dependencies import resolve_dependencies
    resolve_dependencies(['mvn','-B','-f',str(args.out/'pom.xml'),'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
         '-DoutputDirectory='+str(deps)],args.out/'dependencies.log',timeout=600)
    for archive in deps.glob('*.aar'):
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                    target=deps/(archive.stem+'-'+name.replace('/','_'))
                    target.write_bytes(z.read(name))
    manifest={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(deps.iterdir()) if p.is_file()}
    (args.out/'dependency-sha256.json').write_text(json.dumps(manifest,indent=2))
    results=[execute(args.source_root.resolve(),args.out/'corrected',deps,False)]
    if args.baseline_root: results.append(execute(args.baseline_root.resolve(),args.out/'baseline',deps,True))
    (args.out/'results.json').write_text(json.dumps(results,indent=2))
    print('PASS: Android framework/Robolectric row tests. NOT a full APK or LSPosed test.')
if __name__=='__main__':
    try: main()
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as exc:
        print('ERROR:',exc,flush=True); raise SystemExit(2)

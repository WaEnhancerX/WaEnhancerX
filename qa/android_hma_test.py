#!/usr/bin/env python3
"""Compile complete HMA diagnostics/config/root-runner code and exercise Android API 24/28/34.

Robolectric 4.17 provides real Android Context, JSON and Base64 classes. A strictly
enumerated command service replaces privilege elevation and translates logical
Android config paths to disposable files. Generated shell logic then runs using
the local sh and ordinary filesystem tools. No real su, Android data, package
force-stop, LSPosed service or SELinux command is executed. Requires JDK17/Kotlin.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = [
    'app/src/main/java/com/waenhancer/utils/HmaDiagnostics.kt',
    'app/src/main/java/com/waenhancer/utils/HmaConfigPolicy.java',
    'app/src/main/java/com/waenhancer/utils/HmaConfigFile.java',
    'app/src/main/java/com/waenhancer/xposed/utils/RootCommandRunner.java',
    'app/src/main/java/com/waenhancer/utils/DeclaredWhatsAppTargets.java',
    'app/src/main/java/com/waenhancer/utils/AndroidUserScope.java',
]
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>hma-framework-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
</dependencies></project>'''
SERVICE = '''package com.waenhancer.xposed.utils;
/** Explicit manager boundary; delegates to the complete production runner. */
public final class AppRestartHelper {
    public static String runRootCommand(String value) { return RootCommandRunner.run(value, 4000L); }
}
'''


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def execute(command, log, env=None, tolerate=False, timeout=600):
    with log.open('w') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT,
                                timeout=timeout, env=env, check=False)
    print(log.name, 'exit=' + str(result.returncode), flush=True)
    if result.returncode and not tolerate:
        print(log.read_text()[-12000:])
        raise RuntimeError('Command failed; see ' + str(log))
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--sdk-jars', type=Path)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--suite', choices=('all', 'flow', 'contracts'), default='all')
    parser.add_argument('--expect-assertion-failures', type=int, default=0)
    args = parser.parse_args()
    root, out = args.source_root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    deps = args.deps.resolve() if args.deps else out / 'deps'
    deps.mkdir(parents=True, exist_ok=True)
    if not args.deps:
        (out / 'pom.xml').write_text(POM)
        command = [args.maven, '-B', '-f', str(out / 'pom.xml'),
            'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
            '-DoutputDirectory=' + str(deps)]
        if args.maven_repo:
            command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
        from maven_dependencies import resolve_dependencies
        resolve_dependencies(command, out / 'dependencies.log', timeout=600)
        for archive in deps.glob('*.aar'):
            with zipfile.ZipFile(archive) as bundle:
                for name in bundle.namelist():
                    if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                        (deps / (archive.stem + '-' + name.replace('/', '_'))).write_bytes(bundle.read(name))
    compiler = shutil.which('kotlinc')
    if not compiler:
        raise RuntimeError('Kotlin compiler is required')
    kotlin = Path(compiler).resolve().parents[1] / 'lib'
    kotlin_jars = [kotlin / 'kotlin-stdlib.jar', kotlin / 'kotlinx-coroutines-core-jvm.jar']
    for path in kotlin_jars:
        if not path.is_file():
            raise RuntimeError('Missing Kotlin runtime ' + str(path))
    dependency_paths = sorted(deps.glob('*.jar')) + kotlin_jars
    cp = os.pathsep.join(map(str, dependency_paths))
    (out / 'dependency-sha256.json').write_text(json.dumps({str(p): sha(p) for p in dependency_paths}, indent=2))
    stage, classes = out / 'sources', out / 'classes'
    classes.mkdir()
    sources = []
    identities = {}
    for relative in PRODUCTION:
        source = root / relative
        if not source.exists() and args.suite == 'flow' and Path(relative).name in ('HmaConfigFile.java', 'HmaConfigPolicy.java'):
            continue  # Historical baseline predates these helpers; full diagnostics still compiles.
        dest = stage / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        sources.append(dest)
        identities[relative] = sha(source)
    service = stage / 'external-service/com/waenhancer/xposed/utils/AppRestartHelper.java'
    service.parent.mkdir(parents=True)
    service.write_text(SERVICE)
    sources.append(service)
    test_classes = []
    test_files = []
    if args.suite in ('all', 'flow'):
        test_files.append('HmaFlowTest.kt')
        test_classes.append('com.waenhancer.utils.HmaFlowTest')
    if args.suite in ('all', 'contracts'):
        test_files.extend(['HmaContractTest.java', 'RootCommandBoundedChecks.java'])
        test_classes.append('com.waenhancer.utils.HmaContractTest')
    expected = 0
    for name in test_files:
        source = ROOT / 'qa/fixtures' / name
        dest = stage / 'fixtures' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        sources.append(dest)
        identities['qa/fixtures/' + name] = sha(source)
        expected += 3 * len(re.findall(r'@Test\s', source.read_text()))
    fake_bin, case_root = out / 'command-service', out / 'command-cases'
    fake_bin.mkdir()
    case_root.mkdir()
    fake_source = ROOT / 'qa/fixtures/hma_command_service.py'
    for name in ('su', 'cp', 'chown', 'chmod', 'mv', 'chcon', 'restorecon'):
        dest = fake_bin / name
        shutil.copyfile(fake_source, dest)
        dest.chmod(0o755)
    identities['qa/fixtures/hma_command_service.py'] = sha(fake_source)
    identities['qa/android_hma_test.py'] = sha(Path(__file__))
    identity = {'source_root': str(root), 'source_sha256': identities,
                'service_sha256': sha(service), 'suite': args.suite,
                'executor_uid': os.geteuid(),
                'expected_framework_tests': expected,
                'boundaries': ['enumerated command service; no real su or Android data',
                    'logical Android paths map to disposable local files',
                    'complete generated shell logic; local tools and filesystem',
                    'no HMA in-memory reload, SELinux, LSPosed or host injection proof']}
    (out / 'source-identity.json').write_text(json.dumps(identity, indent=2))
    execute(['javac', '--release', '17', '-cp', cp, '-d', str(classes),
             *[str(p) for p in sources if p.suffix == '.java']], out / 'javac.log')
    # This unused Robolectric runtime dependency contains Kotlin 2.2 metadata.
    # Keep it on the runtime classpath; it is not referenced by our Kotlin sources.
    kotlin_cp = os.pathsep.join(str(p) for p in dependency_paths
                               if not p.name.startswith('test-parameter-injector-'))
    execute([compiler, '-jvm-target', '17', '-classpath', str(classes) + os.pathsep + kotlin_cp,
             '-d', str(classes), *[str(p) for p in sources if p.suffix == '.kt']], out / 'kotlinc.log')
    env = dict(os.environ, HMA_QA_ROOT=str(case_root), PATH=str(fake_bin) + os.pathsep + os.environ['PATH'])
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', '-Xmx1g', *['--add-opens=' + p + '=ALL-UNNAMED' for p in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2']
    if args.maven_repo:
        command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        command += ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
        (out / 'sdk-sha256.json').write_text(json.dumps({p.name: sha(p) for p in sorted(args.sdk_jars.glob('*.jar'))}, indent=2))
    command += ['-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore', *test_classes]
    code = execute(command, out / 'junit.log', env=env, tolerate=True)
    if args.maven_repo and not args.sdk_jars:
        jars = args.maven_repo.resolve() / 'org/robolectric/android-all-instrumented'
        (out / 'sdk-sha256.json').write_text(json.dumps({p.name: sha(p) for p in sorted(jars.glob('*/*.jar'))}, indent=2))
    log = (out / 'junit.log').read_text()
    passed = re.search(r'OK \((\d+) tests?\)', log)
    failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', log)
    count = int(passed.group(1)) if passed else int(failed.group(1)) if failed else 0
    failures = int(failed.group(2)) if failed else 0
    failure_blocks = re.split(r'(?m)^\d+\) ', log)[1:]
    assertion_failures = sum(bool(re.search(r'(?m)^(?:java\.lang\.AssertionError|org\.junit\.ComparisonFailure):?', block)) for block in failure_blocks)
    bounded = 0
    bounded_code = 0
    if args.suite in ('all', 'contracts'):
        bounded_code = execute(['java', '-cp', str(classes),
            'com.waenhancer.xposed.utils.RootCommandBoundedChecks'], out / 'bounded-runner.log', tolerate=True)
        match = re.search(r'ROOT_BOUNDED_CHECKS=(\d+)', (out / 'bounded-runner.log').read_text())
        bounded = int(match.group(1)) if match else 0
    result = {**identity, 'framework_tests': count, 'failures': failures,
        'assertion_failures': assertion_failures, 'junit_exit': code,
        'bounded_checks': bounded, 'bounded_exit': bounded_code,
        'expected_assertion_failures': args.expect_assertion_failures}
    result['accepted'] = count == expected and failures == assertion_failures == args.expect_assertion_failures \
        and code == (1 if failures else 0) and bounded_code == 0 \
        and (args.suite == 'flow' or bounded == 20)
    (out / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({key: result[key] for key in ('framework_tests', 'failures', 'assertion_failures', 'bounded_checks', 'accepted')}))
    if not result['accepted']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

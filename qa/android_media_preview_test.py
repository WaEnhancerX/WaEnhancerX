#!/usr/bin/env python3
"""Compile complete MediaPreviewHook and exercise lifecycle on Robolectric Views.

Uses public Robolectric 4.17, actual DexKit 2.2 and OkHttp 4.12 APIs, Kotlin
2.0.20 and JDK 17+. Optional paths reuse existing dependencies and offline SDK
JARs; otherwise Maven and pinned public downloads bootstrap them. Android classes,
BaseFeature and HKDFv3 are real; network/host/Xposed/text/tint boundaries are
explicit fixtures. No host injection, network download or media decoder is
certified by these lifecycle tests.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_TESTS = 36
JAVA = Path('app/src/main/java/com/waenhancer')
SOURCES = [JAVA/'xposed/features/media/MediaPreviewHook.kt',
           JAVA/'xposed/core/BaseFeature.kt', JAVA/'xposed/utils/HKDFv3.kt']
MAVEN_CENTRAL = 'https://repo.maven.apache.org/maven2'
DEXKIT_URL = MAVEN_CENTRAL + '/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
DEXKIT_AAR_SHA256 = 'cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6'
DEXKIT_SHA256 = '195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1'
OKHTTP_URL = MAVEN_CENTRAL + '/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar'
OKHTTP_SHA256 = 'b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0'
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>media-preview-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
</dependencies></project>
'''


def run(command, log, allow_failure=False, timeout=180):
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    print(log.name, 'exit=' + str(result.returncode), flush=True)
    if result.returncode and not allow_failure:
        print(log.read_text()[-14000:])
        raise RuntimeError('Command failed: ' + command[0])
    return result.returncode


def verify_api(path, expected, limit):
    if not path.is_file() or path.stat().st_size > limit:
        raise RuntimeError('Missing or oversized pinned API: ' + str(path))
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError('SHA-256 mismatch for pinned API: ' + str(path))
    return path


def download_pinned(url, path, expected, limit):
    with urllib.request.urlopen(url, timeout=30) as response:
        data = response.read(limit + 1)
    if len(data) > limit or hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError('Public artifact size or SHA-256 mismatch: ' + url)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def resolve_apis(args, out):
    apis = out / 'public-apis'
    dexkit = args.dexkit_jar.resolve() if args.dexkit_jar else apis / 'dexkit-2.2.0.jar'
    if not args.dexkit_jar:
        archive = download_pinned(DEXKIT_URL, apis / 'dexkit-2.2.0.aar', DEXKIT_AAR_SHA256, 4 * 1024 * 1024)
        with zipfile.ZipFile(archive) as bundle:
            matches = [entry for entry in bundle.infolist() if entry.filename == 'classes.jar']
            if len(matches) != 1 or matches[0].file_size > 2 * 1024 * 1024:
                raise RuntimeError('Pinned DexKit AAR must contain one bounded classes.jar')
            dexkit.write_bytes(bundle.read(matches[0]))
    verify_api(dexkit, DEXKIT_SHA256, 2 * 1024 * 1024)
    http = args.http_api_jar.resolve() if args.http_api_jar else apis / 'okhttp-4.12.0.jar'
    if not args.http_api_jar:
        download_pinned(OKHTTP_URL, http, OKHTTP_SHA256, 2 * 1024 * 1024)
    verify_api(http, OKHTTP_SHA256, 2 * 1024 * 1024)
    return dexkit, http


def resolve_framework(args, out):
    if args.deps:
        return args.deps.resolve()
    deps = out / 'deps'
    deps.mkdir(parents=True, exist_ok=True)
    pom = out / 'pom.xml'
    pom.write_text(POM)
    command = [args.maven, '-B', '-f', str(pom),
               'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
               '-DoutputDirectory=' + str(deps)]
    if args.maven_repo:
        command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.maven_settings:
        command.extend(['--settings', str(args.maven_settings.resolve())])
    from maven_dependencies import resolve_dependencies
    resolve_dependencies(command, out / 'dependencies.log', timeout=600)
    for archive in deps.glob('*.aar'):
        with zipfile.ZipFile(archive) as bundle:
            for name in bundle.namelist():
                if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                    # Only flat, generated JAR filenames are written, never ZIP paths.
                    (deps / (archive.stem + '-' + name.replace('/', '_'))).write_bytes(bundle.read(name))
    return deps


def find_kotlin():
    configured = os.environ.get('KOTLIN_HOME')
    compiler = shutil.which('kotlinc')
    if configured:
        home = Path(configured).resolve()
    elif compiler:
        home = Path(compiler).resolve().parents[1]
    else:
        raise RuntimeError('Kotlin 2.0.20 is required: set KOTLIN_HOME or place kotlinc on PATH')
    binary = home / 'bin/kotlinc'
    if not binary.is_file() or not (home / 'lib/kotlin-stdlib.jar').is_file():
        raise RuntimeError('Kotlin installation lacks bin/kotlinc or lib/kotlin-stdlib.jar: ' + str(home))
    return home, binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--deps', type=Path, help='Reuse a flat directory of Robolectric dependencies instead of resolving with Maven')
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--maven-settings', type=Path, help='Optional Maven settings for the public dependency bootstrap')
    parser.add_argument('--dexkit-jar', type=Path, help='Reuse the pinned DexKit 2.2 API JAR instead of downloading its public AAR')
    parser.add_argument('--http-api-jar', type=Path,
                        help='Real OkHttp 4.12.0 JAR for an additional complete production API compilation')
    parser.add_argument('--sdk-jars', type=Path, help='Reuse instrumented API 24/28/34 JARs offline; otherwise Robolectric uses public Maven')
    parser.add_argument('--expect-assertion-failures', type=int, default=0,
                        help='Require this exact failure count and only assertion failures, for a known negative control')
    args = parser.parse_args()
    root,out = args.source_root.resolve(),args.out.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('--out must be empty; use a new directory to avoid stale classes or overwritten evidence')
    if args.expect_assertion_failures < 0 or args.expect_assertion_failures > EXPECTED_TESTS:
        parser.error('--expect-assertion-failures is outside the suite size')
    out.mkdir(parents=True,exist_ok=True)
    kotlin_home, kotlin_compiler = find_kotlin()
    deps = resolve_framework(args, out)
    dexkit_jar, http_api_jar = resolve_apis(args, out)
    bootstrap = {'framework': 'provided' if args.deps else 'public-maven',
                 'dexkit': str(args.dexkit_jar.resolve()) if args.dexkit_jar else DEXKIT_URL,
                 'okhttp': str(args.http_api_jar.resolve()) if args.http_api_jar else OKHTTP_URL,
                 'kotlin_home': str(kotlin_home), 'kotlin_compiler': str(kotlin_compiler),
                 'sdk_mode': 'offline-directory' if args.sdk_jars else 'public-maven-resolver'}
    (out / 'bootstrap.json').write_text(json.dumps(bootstrap, indent=2) + '\n')
    stage,classes = out/'sources',out/'classes'
    classes.mkdir(parents=True,exist_ok=True)
    identities={'qa/android_media_preview_test.py':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    for relative in SOURCES:
        dest=stage/relative;dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(root/relative,dest)
        identities[str(relative)]=hashlib.sha256(dest.read_bytes()).hexdigest()
    fixture=ROOT/'qa/fixtures/media_preview/runtime_doubles.py'
    stubs=runpy.run_path(str(fixture))['STUBS']
    identities[str(fixture.relative_to(ROOT))]=hashlib.sha256(fixture.read_bytes()).hexdigest()
    hook=(root/SOURCES[0]).read_text()
    ids=sorted(set(re.findall(r'(?<!Android)R\.string\.([A-Za-z_][A-Za-z_0-9]*)',hook)))
    stubs['com/waenhancer/app/R.java']='package com.waenhancer.app; public final class R {public static final class string {'+''.join(f'public static final int {name}={index+1};' for index,name in enumerate(ids))+'}}'
    stub_sources=[]
    for name,content in stubs.items():
        dest=stage/'doubles'/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(content);stub_sources.append(dest)
    dependencies=sorted(deps.glob('*.jar'))+[dexkit_jar,kotlin_home/'lib/kotlin-stdlib.jar']
    cp=os.pathsep.join(map(str,dependencies))
    # Framework test helpers may use newer Kotlin metadata. They are not app
    # dependencies; production needs only Android, DexKit and the Kotlin runtime.
    production_dependencies=[p for p in dependencies if p.name=='android-all-14-robolectric-10818077.jar' or p==dexkit_jar or p.name=='kotlin-stdlib.jar']
    production_cp=os.pathsep.join(map(str,production_dependencies))
    api_classes=out/'classes-real-http';api_classes.mkdir(parents=True,exist_ok=True)
    api_cp=production_cp+os.pathsep+str(http_api_jar)
    non_network=[p for p in stub_sources if 'okhttp3' not in p.parts]
    run(['javac','--release','17','-cp',api_cp,'-d',str(api_classes),*map(str,non_network)],out/'javac-api-adapters.log')
    run([str(kotlin_compiler),'-jvm-target','17','-cp',str(api_classes)+os.pathsep+api_cp,'-d',str(api_classes),*[str(stage/p) for p in SOURCES]],out/'kotlinc-real-http-api.log')
    run(['javac','--release','17','-cp',cp,'-d',str(classes),*map(str,stub_sources)],out/'javac-doubles.log')
    run([str(kotlin_compiler),'-jvm-target','17','-cp',str(classes)+os.pathsep+production_cp,'-d',str(classes),*[str(stage/p) for p in SOURCES]],out/'kotlinc-production.log')
    test=ROOT/'qa/fixtures/media_preview/MediaPreviewLifecycleTest.java'
    identities[str(test.relative_to(ROOT))]=hashlib.sha256(test.read_bytes()).hexdigest()
    staged_test=stage/test.name
    shutil.copyfile(test,staged_test)
    run(['javac','--release','17','-cp',str(classes)+os.pathsep+cp,'-d',str(classes),str(staged_test)],out/'javac-test.log')
    opens=['java.base/java.lang','java.base/java.util','java.base/java.util.concurrent','java.base/java.io','java.base/java.net','java.base/java.security','java.base/java.text','java.base/jdk.internal.access','java.desktop/java.awt.font','jdk.compiler/com.sun.tools.javac.api']
    command=['java','-Xmx1g',*['--add-opens='+p+'=ALL-UNNAMED' for p in opens],
             '-Drobolectric.dependency.repo.url='+MAVEN_CENTRAL,
             '-cp',str(classes)+os.pathsep+cp,'org.junit.runner.JUnitCore','com.waenhancer.xposed.features.media.MediaPreviewLifecycleTest']
    if args.maven_repo:
        command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
    result=run(command,out/'junit.log',allow_failure=True,timeout=180 if args.sdk_jars else 600)
    junit=(out/'junit.log').read_text()
    passed=re.search(r'OK \((\d+) tests?\)',junit)
    failed=re.search(r'Tests run: (\d+),\s+Failures: (\d+)',junit)
    tests=int(passed.group(1)) if passed else int(failed.group(1)) if failed else 0
    failures=int(failed.group(2)) if failed else 0
    assertions=junit.count('\njava.lang.AssertionError:')
    expected=args.expect_assertion_failures
    valid=tests==EXPECTED_TESTS and failures==expected
    valid=valid and (result!=0 and assertions==expected if expected else result==0 and passed is not None)
    sdk_root = args.sdk_jars.resolve() if args.sdk_jars else (args.maven_repo.resolve() if args.maven_repo else Path.home()/'.m2/repository')/'org/robolectric/android-all-instrumented'
    sdk_files = sorted(sdk_root.glob('*.jar' if args.sdk_jars else '*/*.jar'))
    summary={'source_root':str(root),'junit_exit_code':result,'source_sha256':identities,'complete_real_http_api_compile':True,'bootstrap':bootstrap,
             'tests':tests,'failures':failures,'assertion_failures':assertions,'expected_assertion_failures':expected,'accepted_result':valid,
             'dependency_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in dependencies+[http_api_jar]},
             'sdk_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sdk_files},'scope':__doc__}
    (out/'result.json').write_text(json.dumps(summary,indent=2)+'\n')
    if not valid:
        print(junit[-14000:])
        raise RuntimeError('Unexpected framework lifecycle result')
    print(json.dumps({'junit_exit_code':result,'tests':tests,'failures':failures,'source_files':len(SOURCES),'source_root':str(root)}),flush=True)


if __name__=='__main__':
    main()

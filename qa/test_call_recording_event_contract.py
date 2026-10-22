#!/usr/bin/env python3
"""Verify complete production call-event resolution against isolated host declarations.

Each fixture uses one of the two supported named callback packages. Real Java
reflection resolves the production contract in a fresh class loader. No Android
audio, native DexKit, Xposed installation, or real WhatsApp call is performed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = Path('app/src/main/java/com/waenhancer/xposed/features/media/CallRecordingEventContract.java')
PACKAGES = (
    ('com.whatsapp.calling.service', 'com.whatsapp.calling.infra.voipcalling'),
    ('com.whatsapp.voipcalling', 'com.whatsapp.voipcalling'),
)
EXACT = 'public void callEnding(CallLogInfo log, int interval, String series, String path) {}'
ALTERNATE = 'public void callEnding(CallLogInfo log, long interval, boolean finalEvent) {}'
CASES = (
    ('verified_exact', EXACT, 'CallLogInfo,int,String,String'),
    ('exact_over_unrelated', EXACT + 'public void callEnding(String value) {}', 'CallLogInfo,int,String,String'),
    ('exact_over_typed_variants', EXACT + ALTERNATE, 'CallLogInfo,int,String,String'),
    ('one_typed_alternate', ALTERNATE, 'CallLogInfo,long,boolean'),
    ('one_typed_argument', 'public void callEnding(CallLogInfo log) {}', 'CallLogInfo'),
    ('typed_over_unrelated', 'public void callEnding(int value) {}' + ALTERNATE, 'CallLogInfo,long,boolean'),
    ('typed_over_wrong_suffix', 'public void callEnding(unrelated.CallLogInfo value) {}' + ALTERNATE, 'CallLogInfo,long,boolean'),
    ('typed_over_wrong_return', 'public boolean callEnding(CallLogInfo log, int interval) {return true;}' + ALTERNATE, 'CallLogInfo,long,boolean'),
    ('typed_over_static', 'public static void callEnding(CallLogInfo log, int interval) {}' + ALTERNATE, 'CallLogInfo,long,boolean'),
    ('typed_after_invalid_exact_return', EXACT.replace('public void', 'public boolean').replace('{}', '{return true;}') + ALTERNATE, 'CallLogInfo,long,boolean'),
    ('typed_after_invalid_exact_static', EXACT.replace('public void', 'public static void') + ALTERNATE, 'CallLogInfo,long,boolean'),
    ('reject_unrelated_argument', 'public void callEnding(int value) {}', 'reject'),
    ('reject_no_arguments', 'public void callEnding() {}', 'reject'),
    ('reject_unrelated_calllog_suffix', 'public void callEnding(unrelated.CallLogInfo log) {}', 'reject'),
    ('reject_ambiguous_typed', 'public void callEnding(CallLogInfo log, int interval) {}' + ALTERNATE, 'reject'),
    ('reject_wrong_return', 'public boolean callEnding(CallLogInfo log) {return true;}', 'reject'),
    ('reject_static', 'public static void callEnding(CallLogInfo log) {}', 'reject'),
    ('reject_native', 'public native void callEnding(CallLogInfo log);', 'reject'),
    ('reject_abstract', 'public abstract void callEnding(CallLogInfo log);', 'reject'),
    ('reject_missing', 'public void someOtherEvent(CallLogInfo log) {}', 'reject'),
)

HARNESS = '''import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
public final class CallEndingContractChecks {
  public static void main(String[] args) throws Exception {
    try (URLClassLoader loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL(),
            Path.of(args[1]).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      Class<?> contract = loader.loadClass("com.waenhancer.xposed.features.media.CallRecordingEventContract");
      Class<?> callback = loader.loadClass(args[2]);
      Method resolve = contract.getDeclaredMethod("resolve", Class.class);
      resolve.setAccessible(true);
      Object resolved;
      try {
        resolved = resolve.invoke(null, callback);
      } catch (InvocationTargetException error) {
        if (!"reject".equals(args[3]) || !(error.getCause() instanceof NoSuchMethodException)) {
          throw new AssertionError("Usable typed call-ending event was rejected", error.getCause());
        }
        System.out.println("PASS rejected unsupported or ambiguous event");
        return;
      }
      if ("reject".equals(args[3])) throw new AssertionError("Unsupported or ambiguous event was accepted");
      Field field = contract.getDeclaredField("callEnding");
      field.setAccessible(true);
      Method ending = (Method) field.get(resolved);
      String actual = String.join(",", Arrays.stream(ending.getParameterTypes()).map(Class::getSimpleName).toArray(String[]::new));
      if (!args[3].equals(actual)) throw new AssertionError("Wrong overload: " + actual + "; expected " + args[3]);
      Class<?> logInfo = loader.loadClass(args[4] + ".CallLogInfo");
      if (ending.getParameterTypes()[0] != logInfo || ending.getDeclaringClass() != callback
          || ending.getReturnType() != void.class || Modifier.isStatic(ending.getModifiers())
          || Modifier.isAbstract(ending.getModifiers()) || Modifier.isNative(ending.getModifiers())) {
        throw new AssertionError("Resolved event does not satisfy the usable host declaration");
      }
      System.out.println("PASS selected " + actual);
    }
  }
}
'''


def write(directory, relative, text):
    destination = directory / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text)
    return destination


def run(command, log):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=60)
    log.write_text(result.stdout)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    output = args.out or Path(tempfile.mkdtemp(prefix='waex-call-ending-contract-'))
    output.mkdir(parents=True, exist_ok=True)
    common = output / 'common'
    classes = output / 'classes'
    source = args.root.resolve() / CONTRACT
    sources = [source]
    for _, package in PACKAGES:
        for name, body in (
            ('CallState', 'public enum CallState {NONE, ACTIVE}'),
            ('CallInfo', 'public final class CallInfo {public final String callId="call"; public final CallState callState=CallState.ACTIVE;}'),
            ('CallLogInfo', 'public class CallLogInfo {}'),
        ):
            sources.append(write(common, package.replace('.', '/') + '/' + name + '.java',
                                 'package ' + package + ';\n' + body))
    sources.append(write(common, 'unrelated/CallLogInfo.java', 'package unrelated; public class CallLogInfo {}'))
    sources.append(write(common, 'CallEndingContractChecks.java', HARNESS))
    compile_result = run(['javac', '--release', '17', '-d', str(classes), *map(str, sources)], output / 'compile-common.log')
    if compile_result.returncode:
        print(compile_result.stdout)
        raise RuntimeError('Complete production event contract did not compile')
    results = []
    for callback_package, info_package in PACKAGES:
        for name, methods, expected in CASES:
            case = output / (callback_package.replace('.', '_') + '-' + name)
            case.mkdir()
            callback = write(case, 'src/' + callback_package.replace('.', '/') + '/VoiceServiceEventCallback.java',
                'package ' + callback_package + ';\nimport ' + info_package + '.*;\n'
                'public abstract class VoiceServiceEventCallback {\n'
                'private CallInfo getCallInfo() {return new CallInfo();}\n'
                'public void callStateChanged(CallState old, CallInfo info) {}\n'
                'public void soundPortCreated(int engine) {}\n' + methods + '\n}')
            compiled = run(['javac', '--release', '17', '-cp', str(classes), '-d', str(case / 'classes'), str(callback)], case / 'compile.log')
            if compiled.returncode:
                print(compiled.stdout)
                raise RuntimeError('Invalid test declaration: ' + name)
            checked = run(['java', '-cp', str(classes), 'CallEndingContractChecks', str(case / 'classes'),
                           str(classes), callback_package + '.VoiceServiceEventCallback', expected, info_package], case / 'behavior.log')
            results.append({'case': name, 'package': callback_package, 'expected': expected,
                            'exit_code': checked.returncode, 'passed': checked.returncode == 0,
                            'fixture_sha256': hashlib.sha256(callback.read_bytes()).hexdigest(),
                            'output': checked.stdout})
    report = {'production': str(CONTRACT), 'production_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
              'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'cases': results, 'count': len(results), 'passed': sum(case['passed'] for case in results),
              'scope': __doc__}
    (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n')
    for case in results:
        if not case['passed']:
            print(case['package'], case['case'], case['output'])
    print('Call-ending contract:', report['passed'], '/', report['count'], 'passed')
    if report['passed'] != report['count']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Full preserved-message sender/provider/store contracts on Robolectric API 24/28/34.

No private licensing sources, actual WhatsApp, native DexKit scan or LSPosed are
used. The Xposed dispatcher, host message graph and unrelated service boundaries
are explicit fixtures. Cached dependencies can be supplied for offline checks.
"""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess,zipfile,urllib.request,io

ROOT=Path(__file__).resolve().parents[1]
EXPECTED_TESTS=84
PKG=Path('app/src/main/java/com/waenhancer')
PRODUCTION=[
 PKG/'xposed/features/conversation/PreserveDeleteForMeHook.kt',
 PKG/'xposed/core/BaseFeature.kt',
 PKG/'xposed/core/db/PreservedMessageStore.kt',
 PKG/'utils/ContactIdentityPolicy.java',
 PKG/'utils/DeclaredWhatsAppTargets.java',
 PKG/'config/PreferenceSchema.java',
 PKG/'xposed/bridge/HookProvider.java',
 PKG/'xposed/bridge/CallerScopePolicy.java',
 PKG/'xposed/bridge/ProviderDerivedKeyPolicy.java',
 PKG/'xposed/bridge/PreferenceWriteTypePolicy.java',
 PKG/'xposed/core/devkit/DexSearchEngine.java',
 PKG/'xposed/core/devkit/DexCacheManager.java',
 PKG/'xposed/core/devkit/DexNegativeLookupCache.java',
]
XPOSED='''package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public class XposedBridge {
 public static final Map<Member,List<XC_MethodHook>> hooks=new LinkedHashMap<>();
 public static final List<String> logs=new ArrayList<>();
 public static void log(String text){logs.add(text);}
 public static void hookMethod(Member method,XC_MethodHook callback){hooks.computeIfAbsent(method,k->new ArrayList<>()).add(callback);}
 public static void hookAllMethods(Class<?> type,String name,XC_MethodHook callback){for(Method m:type.getDeclaredMethods())if(m.getName().equals(name))hookMethod(m,callback);}
 public static XC_MethodHook.MethodHookParam fireBefore(Member method,Object owner,Object[] args)throws Throwable {
  List<XC_MethodHook> callbacks=hooks.get(method); if(callbacks==null)throw new AssertionError("Production callback missing");
  XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.method=method;param.thisObject=owner;param.args=args;
  for(XC_MethodHook callback:callbacks)callback.beforeHookedMethod(param);return param;
 }
 public static void fireAfter(XC_MethodHook.MethodHookParam param)throws Throwable {for(XC_MethodHook callback:hooks.get(param.method))callback.afterHookedMethod(param);}
 public static void reset(){hooks.clear();logs.clear();}
}'''
METHOD_HOOK='''package de.robv.android.xposed;
public abstract class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam param)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam param)throws Throwable{}
 public static final class MethodHookParam {
  public Object thisObject;public Object[] args;public java.lang.reflect.Member method;
  public boolean resultWasSet;private Object result;
  public Object getResult(){return result;}public void setResult(Object value){result=value;resultWasSet=true;}
 }
}'''

XPOSED_HELPERS='package de.robv.android.xposed;\npublic final class XposedHelpers {\n    public static Class<?> findClass(String name, ClassLoader loader) {\n        try { return Class.forName(name, false, loader); }\n        catch (ClassNotFoundException failure) { throw new IllegalArgumentException(failure); }\n    }\n    public static Class<?> findClassIfExists(String name, ClassLoader loader) {\n        if (name.contains("StatusPlaybackActivity") || name.contains("ComposerActivity"))\n            throw new AssertionError("Host navigation is outside this fixture");\n        try { return Class.forName(name, false, loader); }\n        catch (ClassNotFoundException absent) { return null; }\n    }\n    public static java.lang.reflect.Field findField(Class<?> type, String name) {\n        for (Class<?> current = type; current != null; current = current.getSuperclass()) {\n            try { java.lang.reflect.Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }\n            catch (NoSuchFieldException absent) { }\n        }\n        throw new IllegalArgumentException(name);\n    }\n    public static java.lang.reflect.Method findMethodExact(Class<?> type, String name, Class<?>... parameters) {\n        try { java.lang.reflect.Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }\n        catch (ReflectiveOperationException failure) { throw new IllegalArgumentException(failure); }\n    }\n}\n'

POM='''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>org.waex.qa</groupId><artifactId>preserved-dispatch-contract-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
</dependencies></project>'''


def execute(command,log,allow=False):
    with log.open('w') as handle:
        result=subprocess.run(list(map(str,command)),stdout=handle,stderr=subprocess.STDOUT,timeout=600)
    print(log.name,'exit='+str(result.returncode),flush=True)
    if result.returncode and not allow:
        raise RuntimeError(log.read_text()[-16000:])
    return result.returncode


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root',type=Path,default=ROOT)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--deps',type=Path)
    parser.add_argument('--sdk-jars',type=Path)
    parser.add_argument('--dexkit',type=Path)
    parser.add_argument('--maven',default='mvn')
    parser.add_argument('--maven-repo',type=Path)
    parser.add_argument('--expect-assertion-failures',type=int,default=0)
    args=parser.parse_args()
    root=args.source_root.resolve();out=args.out.resolve();out.mkdir(parents=True,exist_ok=False)
    deps=args.deps.resolve() if args.deps else out/'deps'
    if args.deps:
        if not deps.is_dir(): raise RuntimeError('Dependency directory missing')
    else:
        deps.mkdir();(out/'pom.xml').write_text(POM)
        command=[args.maven,'-B','-f',str(out/'pom.xml'),'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies','-DoutputDirectory='+str(deps)]
        if args.maven_repo: command.append('-Dmaven.repo.local='+str(args.maven_repo.resolve()))
        from maven_dependencies import resolve_dependencies
        resolve_dependencies(command,out/'dependencies.log',timeout=600)
        for archive in deps.glob('*.aar'):
            with zipfile.ZipFile(archive) as bundle:
                for name in bundle.namelist():
                    if name=='classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                        (deps/(archive.stem+'-'+name.replace('/','_'))).write_bytes(bundle.read(name))
    compiler=shutil.which('kotlinc')
    if not compiler:raise RuntimeError('Kotlin compiler required for full production sources')
    kotlin=Path(compiler);stdlib=kotlin.resolve().parents[1]/'lib/kotlin-stdlib.jar'
    dexkit=args.dexkit.resolve() if args.dexkit else out/'dexkit-2.2.0.jar'
    if not args.dexkit:
        url='https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
        with urllib.request.urlopen(url,timeout=30) as response:body=response.read(8*1024*1024+1)
        if len(body)>8*1024*1024 or hashlib.sha256(body).hexdigest()!='cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6':
            raise RuntimeError('DexKit public AAR identity mismatch')
        with zipfile.ZipFile(io.BytesIO(body)) as archive:dexkit.write_bytes(archive.read('classes.jar'))
    if hashlib.sha256(dexkit.read_bytes()).hexdigest()!='195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1':
        raise RuntimeError('DexKit classes JAR identity mismatch')
    stage=out/'sources';classes=out/'classes';classes.mkdir();identities={};sources=[]
    for relative in PRODUCTION:
        source=root/relative;dest=stage/relative;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest);sources.append(dest)
        identities[str(relative)]=hashlib.sha256(source.read_bytes()).hexdigest()
    fixtures={
        'com/waenhancer/app/BuildConfig.java':'package com.waenhancer.app;public final class BuildConfig{public static final int VERSION_CODE=20100;public static final String VERSION_NAME="fixture";}',
        'androidx/annotation/NonNull.java':'package androidx.annotation;public @interface NonNull{}',
        'androidx/annotation/Nullable.java':'package androidx.annotation;public @interface Nullable{}',
        'de/robv/android/xposed/XposedBridge.java':XPOSED,
        'de/robv/android/xposed/XC_MethodHook.java':METHOD_HOOK,
        'de/robv/android/xposed/XposedHelpers.java':XPOSED_HELPERS,
        'com/waenhancer/utils/WhatsAppPackageDetector.java':'package com.waenhancer.utils;public class WhatsAppPackageDetector{public static java.util.Set<String> getSupportedPackages(android.content.Context c){return DeclaredWhatsAppTargets.packages();}}',
        'com/waenhancer/licensing/LicenseManager.java':'package com.waenhancer.licensing;public class LicenseManager{public static String getProStatus(android.content.Context c){throw new AssertionError("License endpoint is outside fixture");}}',
        'com/waenhancer/update/UpdateCheckCoordinator.java':'package com.waenhancer.update;public class UpdateCheckCoordinator{public static final String TRIGGER_WHATSAPP="unused";public static void request(android.content.Context c,String s,boolean b){throw new AssertionError("Update endpoint is outside fixture");}}',
    }
    provider=(root/PKG/'xposed/bridge/HookProvider.java').read_text()
    for type_,package in [('ProFeatureGate','com.waenhancer.licensing'),('BootloaderSpooferFeature','com.waenhancer.licensing.features')]:
        names=sorted(set(re.findall(type_+r'\.([A-Z][A-Z_]+)',provider)))
        fields=''.join('public static final String '+name+'="'+name+'";' for name in names)
        extra='public static boolean isEntitled(android.content.Context c,String key){throw new AssertionError("License endpoint is outside fixture");}' if type_=='ProFeatureGate' else ''
        fixtures[package.replace('.','/')+'/'+type_+'.java']='package '+package+';public class '+type_+'{'+fields+extra+'}'
    fixture_paths=[];fixture_identities={}
    for relative,text in fixtures.items():
        dest=stage/'explicit-service-fixtures'/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(text);fixture_paths.append(dest);fixture_identities[relative]=hashlib.sha256(dest.read_bytes()).hexdigest()
    android=deps/'android-all-14-robolectric-10818077.jar'
    if not android.is_file():raise RuntimeError('Pinned Android compilation API missing')
    cp=os.pathsep.join(map(str,[classes,*sorted(deps.glob('*.jar')),dexkit,stdlib]))
    prod_cp=os.pathsep.join(map(str,[classes,android,dexkit,stdlib]))
    first=[source for source in sources if source.suffix=='.java' and source.name!='HookProvider.java']+fixture_paths
    execute(['javac','--release','17','-cp',cp,'-d',classes,*first],out/'javac-production-support.log')
    execute([kotlin,'-jvm-target','17','-classpath',prod_cp,'-d',classes,*[s for s in sources if s.suffix=='.kt']],out/'kotlinc-production.log')
    test=stage/'PreservedDispatchContractTest.java';shutil.copyfile(ROOT/'qa/fixtures'/test.name,test)
    identities['qa/fixtures/'+test.name]=hashlib.sha256(test.read_bytes()).hexdigest()
    execute(['javac','--release','17','-cp',cp,'-d',classes,stage/PKG/'xposed/bridge/HookProvider.java',test],out/'javac-provider-tests.log')
    opens=['java.base/java.lang','java.base/java.util','java.base/java.io','java.base/java.net','java.base/java.security','java.base/java.text','java.base/jdk.internal.access','java.desktop/java.awt.font','jdk.compiler/com.sun.tools.javac.api']
    command=['java',*['--add-opens='+part+'=ALL-UNNAMED' for part in opens],'-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2','-cp',cp,'org.junit.runner.JUnitCore','com.waenhancer.audit.PreservedDispatchContractTest']
    if args.sdk_jars:command[1:1]=['-Drobolectric.offline=true','-Drobolectric.dependency.dir='+str(args.sdk_jars.resolve())]
    elif args.maven_repo:command.insert(1,'-Dmaven.repo.local='+str(args.maven_repo.resolve()))
    result=execute(command,out/'junit.log',allow=True)
    log=(out/'junit.log').read_text();passed=re.search(r'OK \((\d+) tests?\)',log);failed=re.search(r'Tests run: (\d+),\s+Failures: (\d+)',log)
    count=int(passed.group(1)) if passed else int(failed.group(1)) if failed else 0
    failures=int(failed.group(2)) if failed else 0
    assertions=len(re.findall(r'^java\.lang\.AssertionError(?::|$)',log,re.MULTILINE))
    expected=args.expect_assertion_failures
    accepted=count==EXPECTED_TESTS and failures==expected and (result==0 and passed is not None if expected==0 else result!=0 and assertions==expected)
    report={'source_root':str(root),'tests':count,'failures':failures,'assertion_failures':assertions,'expected_tests':EXPECTED_TESTS,'expected_assertion_failures':expected,'junit_exit':result,'accepted_result':accepted,'source_sha256':identities,'external_service_fixture_sha256':fixture_identities,'limits':'Complete production sender/provider/store and support policies/cache classes; Robolectric Android ContentResolver, ContentProvider, SQLite files and Binder/UID/package shadows on API24/28/34. Explicit Xposed dispatch and synthetic host Runnable/message graph/SQL schema. Package detector facade delegates to real DeclaredWhatsAppTargets; unrelated private license and update entry points throw if called. No native DexKit discovery, actual WhatsApp/LSPosed, device disk fault or kernel cross-process Binder test.'}
    (out/'result.json').write_text(json.dumps(report,indent=2)+'\n')
    dependency_paths=[*sorted(deps.glob('*.jar')),dexkit]
    (out/'dependency-sha256.json').write_text(json.dumps({path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in dependency_paths},indent=2)+'\n')
    if args.sdk_jars:sdk_paths=sorted(args.sdk_jars.resolve().glob('*.jar'))
    else:
        maven_repo=args.maven_repo.resolve() if args.maven_repo else Path.home()/'.m2/repository'
        sdk_paths=sorted((maven_repo/'org/robolectric/android-all-instrumented').glob('*/*.jar'))
    (out/'sdk-sha256.json').write_text(json.dumps({path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in sdk_paths},indent=2)+'\n')
    if not accepted:raise RuntimeError('Unexpected complete-suite result:\n'+log[-18000:])
    print(json.dumps({key:report[key] for key in ['tests','failures','assertion_failures','accepted_result']},indent=2))


if __name__=='__main__':main()

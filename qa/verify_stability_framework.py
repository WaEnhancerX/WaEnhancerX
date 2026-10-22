#!/usr/bin/env python3
"""Isolated real Android/Compose compilation and regressions, not a full WAEX APK.
Modes allow standalone updater/filter/clock branches without borrowing production
code from another PR. Xposed, private Pro behavior and design theme are fixtures.
"""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess, tomllib
import xml.etree.ElementTree as ET
from framework_localization import stage_localization

ROOT = Path(__file__).resolve().parents[1]

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,default=ROOT)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--mode',choices=['all','update','filter','clock'],default='all')
    a=ap.parse_args();root=a.root.resolve();out=a.out.resolve()
    out.mkdir(parents=True,exist_ok=False);e=out/'evidence';e.mkdir()
    p=out/'fixture';p.mkdir();v=tomllib.loads((root/'gradle/libs.versions.toml').read_text())['versions']
    (p/'settings.gradle.kts').write_text('pluginManagement{repositories{google();mavenCentral();gradlePluginPortal()}}\ndependencyResolutionManagement{repositories{google();mavenCentral()}}\nrootProject.name="stability-fixture"\ninclude(":probe")\n')
    (p/'gradle.properties').write_text('android.useAndroidX=true\nandroid.builtInKotlin=false\nandroid.newDsl=false\norg.gradle.jvmargs=-Xmx2g\n')
    probe=p/'probe';probe.mkdir()
    def write(path,text):
        f=probe/path;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
    build='''plugins {
 id("com.android.library") version "AGP"
 id("org.jetbrains.kotlin.android") version "KOTLIN"
 id("org.jetbrains.kotlin.plugin.compose") version "KOTLIN"
}
android {
 namespace="com.waenhancer.app"
 compileSdk=36
 defaultConfig {minSdk=24}
 buildFeatures {compose=true}
 compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlinOptions {jvmTarget="17"}
 testOptions {unitTests.isIncludeAndroidResources=true}
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:BOM"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.runtime:runtime")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:LIFE")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:LIFE")
 implementation("androidx.core:core-ktx:CORE")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:CORO")
 implementation("com.squareup.okhttp3:okhttp:OKHTTP")
 testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:CORO")
 testImplementation("org.robolectric:robolectric:4.17")
 testImplementation("junit:junit:4.13.2")
}
'''
    for key,val in {'AGP':v['androidGradlePlugin'],'KOTLIN':v['kotlin'],'BOM':v['androidxComposeBom'],'LIFE':v['androidxLifecycle'],'CORO':v['coroutines'],'CORE':v['androidxCore'],'OKHTTP':v['okhttp']}.items():build=build.replace(key,val)
    write('build.gradle.kts',build)
    write('src/main/AndroidManifest.xml','<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>')
    identity={};modes=['update','filter','clock'] if a.mode=='all' else [a.mode]
    sources={'update':['update/UpdateDownloader.kt','update/UpdateTransferPolicy.kt','update/UpdateDownloadSession.kt','update/ReleaseRepository.kt','ui/components/UpdateDownloadSheet.kt','xposed/utils/RootCommandRunner.java','utils/AndroidUserScope.java'],
             'filter':['xposed/features/customization/FilterItemsHook.kt','model/FilterItem.kt'],
             'clock':['ui/components/RuntimeObservationClock.kt','utils/InjectionEvidence.kt']}
    expected={'update':5,'filter':3,'clock':1}
    for mode in modes:
        for rel in sources[mode]:
            source=root/'app/src/main/java/com/waenhancer'/rel
            write('src/main/java/com/waenhancer/'+rel,source.read_text())
            identity[str(source.relative_to(root))]=hashlib.sha256(source.read_bytes()).hexdigest()
        source=root/('qa/fixtures/Stability'+mode.title()+'Test.kt')
        write('src/test/java/com/waenhancer/fixture/'+source.name,source.read_text())
        shutil.copy2(source,e/source.name)
    # Only a theme adapter for compilation; no fonts or licensing implementation copied.
    write('src/main/java/com/waenhancer/ui/designsystem/WaexTheme.kt','''package com.waenhancer.ui.designsystem
import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
object WaexTheme {
 val colors @Composable get()=MaterialTheme.colorScheme
 val typography @Composable get()=FixtureTypography(MaterialTheme.typography)
}
class FixtureTypography(val t:androidx.compose.material3.Typography){val headlineMd get()=t.headlineMedium;val bodyMd get()=t.bodyMedium}
''')
    if 'filter' in modes:
        write('src/main/res/values/ids.xml','<resources><item name="fixture_target" type="id"/><item name="fixture_other" type="id"/></resources>')
        write('src/main/java/com/waenhancer/xposed/core/BaseFeature.kt','''package com.waenhancer.xposed.core
import android.content.*
abstract class BaseFeature(protected val context:Context,protected val classLoader:ClassLoader,protected val prefs:SharedPreferences){abstract val name:String;abstract fun hook();fun isEnabled(k:String,d:Boolean)=prefs.getBoolean(k,d)}
''')
        write('src/main/java/com/waenhancer/licensing/features/FilterItemsProFeature.kt','''package com.waenhancer.licensing.features
object FilterItemsProFeature {fun applyProFilter(view:android.view.View,behavior:String,color:Int,opacity:Int,scale:Float,prefs:android.content.SharedPreferences){error("Private Pro implementation must not execute in this fixture")}}
''')
        write('src/main/java/com/waenhancer/xposed/utils/ActivityTracker.kt','''package com.waenhancer.xposed.utils
object ActivityTracker {interface ActivityListener {fun onActivityResumed(a:android.app.Activity)};fun getCurrentActivity():android.app.Activity?=null;fun addListener(l:ActivityListener){error("No hook installation in fixture")}}
''')
        write('src/main/java/de/robv/android/xposed/XC_MethodHook.java','''package de.robv.android.xposed;
public class XC_MethodHook {public static class MethodHookParam {public Object thisObject,result;public Object[] args;}protected void beforeHookedMethod(MethodHookParam p){}protected void afterHookedMethod(MethodHookParam p){}}
''')
        write('src/main/java/de/robv/android/xposed/XposedBridge.java','package de.robv.android.xposed;public class XposedBridge{public static void log(String s){}}')
        write('src/main/java/de/robv/android/xposed/XposedHelpers.java','''package de.robv.android.xposed;
public class XposedHelpers {public static void findAndHookMethod(Object... args){throw new AssertionError("No actual hooks in this fixture");} public static Object callMethod(Object o,String n,Object... a){throw new AssertionError("Unexpected reflective hook");}public static Object getAdditionalInstanceField(Object o,String n){return null;}public static void setAdditionalInstanceField(Object o,String n,Object v){}}
''')
    localized = stage_localization(root, probe, identity)
    (e/'source-sha256.json').write_text(json.dumps(identity,indent=2));(e/'build.gradle.kts').write_text(build)
    cmd=['bash',str(root/'gradlew'),'-p',str(p),'--no-daemon',':probe:testDebugUnitTest']
    with (e/'framework-build.log').open('w') as log:
        result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=900)
    for f in (probe/'build/test-results/testDebugUnitTest').glob('*.xml'):shutil.copy2(f,e/f.name)
    reports=list(e.glob('TEST-*.xml'));counts=[0,0,0,0]
    for f in reports:
        t=ET.parse(f).getroot()
        for i,k in enumerate(['tests','failures','errors','skipped']):counts[i]+=int(t.get(k,0))
    summary={'modes':modes,'tests':counts[0],'failures':counts[1],'errors':counts[2],'skipped':counts[3],'build_returncode':result.returncode,'limits':'Real Android Resources/Views/Handler and real Compose Composition/Recomposer; design theme, BaseFeature, Xposed and private Pro service are controlled fixtures, not full WAEX or LSPosed.'}
    (e/'result.json').write_text(json.dumps(summary,indent=2))
    if result.returncode or counts != [sum(expected[m] for m in modes),0,0,0]:
        print((e/'framework-build.log').read_text()[-22000:]);raise RuntimeError('Stability framework failed: '+str(summary))
    print('PASS: '+json.dumps(summary))
if __name__=='__main__':main()

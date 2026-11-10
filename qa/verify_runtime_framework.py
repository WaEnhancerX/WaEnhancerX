#!/usr/bin/env python3
"""Compile runtime utilities and the observation clock with pinned real Android/Compose APIs.
Execute version decisions using public, hash-pinned module APK resource tables on API 34.
Isolated test library only: no private licensing, signing, host APK or LSPosed execution.
"""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,tomllib,urllib.request
import xml.etree.ElementTree as ET
from framework_localization import stage_localization, build_localization_table

TEST=r'''package com.waenhancer.utils
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.waenhancer.utils.UniversalVersionValidator.Decision as D

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE,application=Application::class)
class RuntimeResourcesTest {
 private fun resourceContext(variant:String,tag:String):Context {
  val assets=AssetManager::class.java.getDeclaredConstructor().newInstance()
  val cookie=AssetManager::class.java.getDeclaredMethod("addAssetPath",String::class.java)
   .invoke(assets,System.getProperty("waex.apks")+"/"+variant+".apk") as Int
  assertTrue(cookie>0)
  val res=Resources(assets,DisplayMetrics().apply{setToDefaults()},Configuration().apply{setLocale(Locale.forLanguageTag(tag))})
  return object:ContextWrapper(RuntimeEnvironment.getApplication()) {
   override fun getResources()=res
   override fun getPackageName()="com.waenhancer"
  }
 }
 private fun host(module:Context,visible:Boolean=true):Context=object:ContextWrapper(module){
  override fun getPackageName()="com.whatsapp"
  override fun createPackageContext(pkg:String,flags:Int):Context {
   if(!visible)throw PackageManager.NameNotFoundException()
   assertEquals("com.waenhancer",pkg)
   assertEquals(0, flags and Context.CONTEXT_INCLUDE_CODE)
   return module
  }
 }
 @Test fun compiledRulesInElevenLocales(){
  var contexts=0
  for(variant in listOf("debug","release"))for(tag in listOf("en","es-CL","ar","pt","id","ru","tr","hi","ur","de","fr")){
   val module=resourceContext(variant,tag)
   val id=module.resources.getIdentifier("universal_supported_versions","array","com.waenhancer")
   assertTrue(id!=0)
   assertEquals("com.waenhancer:array/universal_supported_versions",module.resources.getResourceName(id))
   assertEquals(module.resources.getStringArray(id).map{it.trim()},UniversalVersionValidator.getBuiltinSupportedVersions(module))
   assertEquals(module.resources.getStringArray(id).map{it.trim()},UniversalVersionValidator.getBuiltinSupportedVersions(host(module)))
   assertEquals(D.BUILTIN_RULE,UniversalVersionValidator.evaluate(module,"2.26.40.76",null))
   assertEquals(D.BUILTIN_RULE,UniversalVersionValidator.evaluate(host(module),"2.26.40.76",null))
   assertEquals(D.UNSUPPORTED,UniversalVersionValidator.evaluate(host(module),"99.99.99.99",null))
   assertEquals(D.BUILTIN_RULE,UniversalVersionValidator.evaluate(host(module,false),"2.26.40.76",null))
   assertEquals(D.UNSUPPORTED,UniversalVersionValidator.evaluate(host(module,false),"99.99.99.99",null))
   contexts++
  }
  println("PASSED $contexts real compiled-resource contexts; host visibility wrappers, not HMA")
 }
 @Test fun actualPreferenceTypeFailureDoesNotAdmitHost(){
  val p=RuntimeEnvironment.getApplication().getSharedPreferences("bad-type-test",0)
  assertTrue(p.edit().clear().putString("bypass_version_check","not a boolean").commit())
  assertEquals(D.PREFERENCES_UNAVAILABLE,UniversalVersionValidator.evaluate(resourceContext("release","es-CL"),"2.26.40.76",p))
 }
 @Test fun numericFallbackCannotUseTheFixtureResourceTable(){
  val application=RuntimeEnvironment.getApplication()
  val resourceName=application.resources.getResourceName(com.waenhancer.app.R.array.universal_supported_versions)
  // AGP packages library unit-test resources under namespace + ".test".
  assertEquals("com.waenhancer.app.test:array/universal_supported_versions",resourceName)
  assertArrayEquals(arrayOf("9.99.1"),application.resources.getStringArray(com.waenhancer.app.R.array.universal_supported_versions))
  assertEquals(0,application.resources.getIdentifier("universal_supported_versions","array","com.waenhancer"))
  val misleading=object:ContextWrapper(application){
   override fun getPackageName()="com.waenhancer"
  }
  assertEquals(D.UNSUPPORTED,UniversalVersionValidator.evaluate(misleading,"9.99.1",null))
  assertEquals(D.BUILTIN_RULE,UniversalVersionValidator.evaluate(misleading,"2.26.40.76",null))
 }
 @Test fun explicitPermissionsRemainDistinct(){
  val p=RuntimeEnvironment.getApplication().getSharedPreferences("explicit-test",0)
  val hidden=host(resourceContext("release","es-CL"),false)
  assertTrue(p.edit().clear().putBoolean("customize_supported_versions",true).putStringSet("custom_supported_versions",setOf("9.99.1")).commit())
  assertEquals(D.CUSTOM_RULE,UniversalVersionValidator.evaluate(hidden,"9.99.1",p))
  assertEquals(D.UNSUPPORTED,UniversalVersionValidator.evaluate(hidden,"9.99.2",p))
  assertEquals(D.BUILTIN_RULE,UniversalVersionValidator.evaluate(hidden,"2.26.40.76",p))
  assertTrue(p.edit().putBoolean("bypass_version_check",true).commit())
  assertEquals(D.EXPLICIT_BYPASS,UniversalVersionValidator.evaluate(hidden,"99.99.99.99",p))
 }
 @Test fun fatalResourceFailuresPropagate(){
  val fatal=OutOfMemoryError("fixture resource failure; no memory exhaustion")
  val broken=object:ContextWrapper(RuntimeEnvironment.getApplication()){
   override fun getPackageName()="com.waenhancer"
   override fun getResources():Resources=throw fatal
  }
  try {
   UniversalVersionValidator.evaluate(broken,"2.26.40.76",null)
   fail("Fatal resource failures must not become compatibility decisions")
  } catch(actual:OutOfMemoryError){
   assertSame(fatal,actual)
  }
 }
}
'''

def main():
 ap=argparse.ArgumentParser();ap.add_argument('--root',type=Path,default=Path.cwd());ap.add_argument('--out',type=Path,required=True);a=ap.parse_args()
 root=a.root.resolve();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False);e=out/'evidence';e.mkdir();project=out/'fixture';project.mkdir();apks=out/'apks';apks.mkdir()
 pins={'debug':'c4531d92851c5f251614e8af822f340737981adeb7128341463f8897e3d42114','release':'f19d64239b493785b48540dce068a32a295f33bd33b18e48b46d168896c4ac0f'}
 for variant,digest in pins.items():
  url=f'https://github.com/WaEnhancerX/WaEnhancerX/releases/download/2.0.3/WaEnhancerX-v2.0.3_{variant}.apk'
  with urllib.request.urlopen(url,timeout=60) as response:data=response.read(64*1024*1024+1)
  if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('Public APK changed; do not use stale acceptance: '+variant)
  (apks/(variant+'.apk')).write_bytes(data)
 v=tomllib.loads((root/'gradle/libs.versions.toml').read_text())['versions']
 (project/'settings.gradle.kts').write_text('pluginManagement{repositories{google();mavenCentral();gradlePluginPortal()}}\ndependencyResolutionManagement{repositories{google();mavenCentral()}}\nrootProject.name="runtime-fixture"\ninclude(":probe")\n')
 (project/'gradle.properties').write_text('android.useAndroidX=true\nandroid.builtInKotlin=false\nandroid.newDsl=false\norg.gradle.jvmargs=-Xmx2g\n')
 probe=project/'probe';probe.mkdir()
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
 testOptions {unitTests.isIncludeAndroidResources=true;unitTests.all {it.systemProperty("waex.apks", "APKS")}}
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:BOM"))
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.runtime:runtime")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:LIFE")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:LIFE")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:CORO")
 testImplementation("org.robolectric:robolectric:4.17")
 testImplementation("junit:junit:4.13.2")
}
'''
 for key,val in {'AGP':v['androidGradlePlugin'],'KOTLIN':v['kotlin'],'BOM':v['androidxComposeBom'],'LIFE':v['androidxLifecycle'],'CORO':v['coroutines'],'APKS':str(apks)}.items():build=build.replace(key,val)
 (probe/'build.gradle.kts').write_text(build)
 def write(path,text):
  p=probe/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
 identities={}
 # Compile all of these source files unchanged; unrelated private services are test doubles.
 files=['utils/UniversalVersionValidator.kt','utils/WhatsAppPackageDetector.kt','utils/XposedRuntimeInspector.kt','utils/InjectionEvidence.kt','utils/TargetRuntimePresentation.kt','ui/components/RuntimeObservationClock.kt','xposed/core/VersionGuard.kt']
 if (root/'app/src/main/java/com/waenhancer/utils/DeclaredWhatsAppTargets.java').exists():files+=['utils/DeclaredWhatsAppTargets.java']
 for name in files:
  p=root/'app/src/main/java/com/waenhancer'/name
  write('src/main/java/com/waenhancer/'+name,p.read_text());identities[name]=hashlib.sha256(p.read_bytes()).hexdigest()
 arrays_source=root/'app/src/main/res/values/arrays.xml'
 arrays=ET.fromstring(arrays_source.read_bytes())
 foreign_rules=arrays.find("string-array[@name='universal_supported_versions']")
 if foreign_rules is None:raise RuntimeError('Missing fixture version-rule array')
 # This test-library table has a foreign owner. Its unique rule must never
 # authorize a host through the module's numeric resource-ID fallback.
 for item in list(foreign_rules):foreign_rules.remove(item)
 ET.SubElement(foreign_rules,'item').text='9.99.1'
 fixture_arrays=ET.tostring(arrays,encoding='unicode')
 write('src/main/res/values/arrays.xml',fixture_arrays)
 (e/'fixture-arrays.xml').write_text(fixture_arrays)
 identities['app/src/main/res/values/arrays.xml']=hashlib.sha256(arrays_source.read_bytes()).hexdigest()
 identities['fixture/src/main/res/values/arrays.xml']=hashlib.sha256(fixture_arrays.encode()).hexdigest()
 write('src/main/AndroidManifest.xml','<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>')
 write('src/main/java/com/waenhancer/config/PreferenceStores.java','package com.waenhancer.config; import android.content.*; public final class PreferenceStores {public static SharedPreferences publicStore(Context c){return c.getSharedPreferences("probe",0);} public static SharedPreferences privateStore(Context c){return c.getSharedPreferences("probe-private",0);}}')
 write('src/main/java/com/waenhancer/xposed/utils/ModuleStatus.java','package com.waenhancer.xposed.utils; public final class ModuleStatus {public static boolean isModuleActive(){return false;}}')
 write('src/main/java/com/waenhancer/xposed/utils/AppRestartHelper.java','package com.waenhancer.xposed.utils; public final class AppRestartHelper {public static String runRootCommand(String s){return null;}}')
 write('src/main/java/de/robv/android/xposed/XposedBridge.java','package de.robv.android.xposed;public class XposedBridge{public static void log(String s){}}')
 write('src/main/java/com/waenhancer/xposed/core/components/WaexBottomSheet.kt','''package com.waenhancer.xposed.core.components
import android.app.Activity
class Dialog {fun dismiss(){}}
class WaexBottomSheet(a:Activity){fun setTitle(s:String)=this;fun setMessage(s:String)=this;fun setPositiveButton(s:String,f:(Dialog,Int)->Unit)=this;fun show(){}}
''')
 localized = stage_localization(root, probe, identities)
 if localized:
  locale_apk = build_localization_table(root, out/'locale-table')
  build += '\ntasks.withType<Test>().configureEach { systemProperty("waex.locale.apk", "'+str(locale_apk)+'") }\n'
  (probe/'build.gradle.kts').write_text(build)
  fixture=root/'qa/fixtures/LocalizationResourcesTest.kt'
  write('src/test/java/com/waenhancer/utils/LocalizationResourcesTest.kt',fixture.read_text())
  shutil.copy2(fixture,e/fixture.name)
  identities[str(fixture.relative_to(root))]=hashlib.sha256(fixture.read_bytes()).hexdigest()
 write('src/test/java/com/waenhancer/utils/RuntimeResourcesTest.kt',TEST)
 identities['fixture/RuntimeResourcesTest.kt']=hashlib.sha256(TEST.encode()).hexdigest()
 (e/'source-sha256.json').write_text(json.dumps(identities,indent=2));(e/'RuntimeResourcesTest.kt').write_text(TEST);(e/'build.gradle.kts').write_text(build)
 cmd=['bash',str(root/'gradlew'),'-p',str(project),'--no-daemon',':probe:testDebugUnitTest']
 with (e/'framework-build.log').open('w') as log:
  result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=720)
 for p in (probe/'build/test-results/testDebugUnitTest').glob('*.xml'):shutil.copy2(p,e/p.name)
 if result.returncode:
  print((e/'framework-build.log').read_text()[-18000:]);raise RuntimeError('Isolated framework validation failed')
 results=list(e.glob('TEST-*.xml'))
 assert results,'No JUnit reports'
 tests=failures=errors=skipped=0
 for p in results:
  t=ET.parse(p).getroot();tests+=int(t.get('tests',0));failures+=int(t.get('failures',0));errors+=int(t.get('errors',0));skipped+=int(t.get('skipped',0))
 assert tests==(12 if localized else 5) and failures==0 and errors==0 and skipped==0,(tests,failures,errors,skipped)
 (e/'result.json').write_text(json.dumps({'tests':tests,'contexts':22,'locale_resource_tests':7 if localized else 0,'failures':failures,'errors':errors,'skipped':skipped,'limits':'Compiled public utilities and clock with test service doubles. No full WAEX APK, Compose screen rendering, HMA or LSPosed.'},indent=2))
 print(f'PASS: real Android/Compose API compilation; {tests} Android 14 JUnit methods, 22 compiled-resource contexts; localized tests={localized}; no full app/LSPosed')

if __name__=='__main__':main()

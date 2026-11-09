#!/usr/bin/env python3
"""Compile production locale/routing classes; run with explicit Android resource/context doubles.

All real template formatting uses java.util.Formatter. This is not Android instrumentation.
"""
from pathlib import Path
import tempfile,subprocess,base64,re
from localization_support import entries,write_r
ROOT=Path(__file__).resolve().parents[1];SRC=ROOT/'app/src/main/java/com/waenhancer/utils'
DOUBLES={
'android/os/LocaleList.java':'''package android.os; public class LocaleList { private final java.util.Locale[] v; public LocaleList(java.util.Locale... v){this.v=v;} public java.util.Locale get(int i){return v[i];} }''',
'android/os/Build.java':'''package android.os; public class Build {public static class VERSION {public static int SDK_INT=34;} public static class VERSION_CODES {public static final int N=24;} }''',
'android/content/SharedPreferences.java':'''package android.content; public interface SharedPreferences {String getString(String k,String v); Editor edit(); interface Editor {Editor putString(String k,String v); void apply();} }''',
'android/content/res/Configuration.java':'''package android.content.res;
public class Configuration { public static int copies; public java.util.Locale locale=java.util.Locale.ENGLISH; private android.os.LocaleList locales=new android.os.LocaleList(locale);
public Configuration(){} public Configuration(Configuration c){copies++;locale=c.locale;locales=c.locales;} public void setLocales(android.os.LocaleList v){locales=v;locale=v.get(0);} public android.os.LocaleList getLocales(){return locales;} public int hashCode(){return locale.toLanguageTag().hashCode();}}
''',
'android/content/res/Resources.java':'''package android.content.res;
import java.util.*; public class Resources {
 public static class NotFoundException extends RuntimeException {}
 public static final Map<Integer,String> EN=new HashMap<>(),ES=new HashMap<>();
 public static Resources SYSTEM=new Resources("android"); public static int foreignReads;
 public final String owner; private Configuration config=new Configuration();
 public Resources(String owner){this.owner=owner;} public static Resources getSystem(){return SYSTEM;}
 public Configuration getConfiguration(){return config;} public Object getDisplayMetrics(){return null;}
 public void updateConfiguration(Configuration c,Object metrics){config=c;}
 public String getResourcePackageName(int id){return owner;}
 public String getString(int id){if(!owner.equals("com.waenhancer"))foreignReads++;String s=(config.locale.getLanguage().equals("es")?ES:EN).get(id);if(s==null)throw new NotFoundException();return s;}
 public String getQuantityString(int id,int quantity){if(!owner.equals("com.waenhancer"))foreignReads++;if(config.locale.getLanguage().equals("es"))return quantity==1?"Se eliminó %1$d grabación":"Se eliminaron %1$d grabaciones";return quantity==1?"Deleted %1$d recording":"Deleted %1$d recordings";}
}
''',
'android/content/Context.java':'''package android.content;
import android.content.res.*; public class Context {
 public static int packageQueries,configurationContexts; public String pkg; public boolean hidden,wrongOwner; public Context application; public SharedPreferences preferences; public Resources res;
 public Context(String p){pkg=p;res=new Resources(p);} public Context getApplicationContext(){return application!=null?application:this;} public String getPackageName(){return pkg;}
 public Resources getResources(){return res;} public Context createPackageContext(String name,int flags) throws Exception {packageQueries++;if(hidden)throw new Exception("hidden");return new Context(wrongOwner?"com.whatsapp":name);}
 public Context createConfigurationContext(Configuration c){configurationContexts++;Context n=new Context(pkg);n.preferences=preferences;n.res.updateConfiguration(c,null);return n;}
}
''',
'com/waenhancer/config/PreferenceStores.java':'''package com.waenhancer.config; public class PreferenceStores {public static android.content.SharedPreferences publicStore(android.content.Context c){return c.preferences;}}''',
}
RUNNER=r'''
package qa; import android.content.*; import android.content.res.*; import java.util.*; import java.nio.file.*; import java.util.regex.*; import com.waenhancer.utils.*;
public class TestEs419Runtime {
 static int n; static void ok(boolean b,String label){n++;if(!b)throw new AssertionError(label);}
 static class Prefs implements SharedPreferences,SharedPreferences.Editor {Map<String,String> values=new HashMap<>();boolean bad; public String getString(String k,String d){if(bad)throw new ClassCastException();return values.getOrDefault(k,d);} public Editor edit(){return this;} public Editor putString(String k,String v){values.put(k,v);return this;} public void apply(){} }
 static String unbase(String s){return new String(Base64.getDecoder().decode(s),java.nio.charset.StandardCharsets.UTF_8);}
 public static void main(String[] args) throws Exception {
 List<String[]> input=new ArrayList<>(); for(String line:Files.readAllLines(Path.of(args[0]))){String[] v=line.split("\\t",-1);int id=Integer.parseInt(v[0]);v[1]=unbase(v[1]);v[2]=unbase(v[2]);Resources.EN.put(id,v[1]);Resources.ES.put(id,v[2]);input.add(v);}
 Prefs p=new Prefs();Context host=new Context("com.whatsapp");host.preferences=p;Locale previous=Locale.getDefault();Locale.setDefault(Locale.GERMAN);
 p.values.put("app_language","es-419");UiText.initialize(host,p);int queries=Context.packageQueries;
 for(String[] v:input)ok(UiText.get(Integer.parseInt(v[0]),v[1]).equals(v[2]),"Spanish resource "+v[0]);
 ok(Locale.getDefault().equals(Locale.GERMAN),"host default locale changed");
 int contexts=Context.configurationContexts,copies=Configuration.copies;int first=Integer.parseInt(input.get(0)[0]);for(int i=0;i<10000;i++)UiText.get(first,"fallback");
 ok(Context.configurationContexts==contexts && Configuration.copies==copies,"unbounded per-label configuration creation");ok(Context.packageQueries==queries,"per-label package lookup");
 for(String code:new String[]{"es","es-419","es-CL","es-MX","es-AR","es-CO","es-PE","es-US"}){p.values.put("app_language",code);ok(UiText.get(first,"fallback").equals(input.get(0)[2]),"Spanish locale "+code);}
 p.values.put("app_language","en");for(String[] v:input)ok(UiText.get(Integer.parseInt(v[0]),v[1]).equals(v[1]),"English resource "+v[0]);
 // Full live Formatter behavior, without evaluating dynamic templates a second time.
 Pattern format=Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([sdif])");
 for(String[] v:input){if(!v[3].equals("true"))continue;String source=v[1];Matcher matcher=format.matcher(source.replace("%%",""));Map<Integer,Character> types=new HashMap<>();int seq=0;
 while(matcher.find()){int index=matcher.group(1)==null?++seq:Integer.parseInt(matcher.group(1));char t=matcher.group(2).charAt(0);if(t!='s'||!types.containsKey(index))types.put(index,t);}
 if(types.isEmpty())continue;Object[] values=new Object[Collections.max(types.keySet())];Arrays.fill(values,"dato %1$s sin traducir");for(Map.Entry<Integer,Character> e:types.entrySet()){char t=e.getValue();if(t=='d'||t=='i')values[e.getKey()-1]=7;if(t=='f')values[e.getKey()-1]=3.5;}
 p.values.put("app_language","es-419");String expected=String.format(Locale.forLanguageTag("es-419"),v[2],values);ok(UiText.get(Integer.parseInt(v[0]),source,values).equals(expected),"format "+v[0]);}
 p.values.put("app_language","es-419");for(int count:new int[]{0,1,2,99}){String result=UiText.quantity(0x7f010000,count,"Deleted %d recording(s)",count);ok(result.equals(count==1?"Se eliminó 1 grabación":"Se eliminaron "+count+" grabaciones"),"plural "+count);}
 p.values.put("app_language","system");Resources.SYSTEM.getConfiguration().setLocales(new android.os.LocaleList(Locale.forLanguageTag("es-CL")));ok(UiText.displayLocale().toLanguageTag().equals("es-CL"),"system selection");
 p.bad=true;ok(UiText.displayLocale().getLanguage().equals("es"),"unreadable preference fallback");p.bad=false;
 ok(DisplayLocalePolicy.canonicalCode("es").equals("es-419"),"legacy selection");ok(DisplayLocalePolicy.resolve("in",Locale.ENGLISH).getLanguage().equals("id"),"Indonesian alias");ok(DisplayLocalePolicy.resolve("",Locale.FRENCH).equals(Locale.FRENCH),"empty selection");
 p.values.put("app_language","es-419");host.hidden=true;UiText.initialize(host,p);ok(UiText.get(first,"original").equals("original"),"hidden module fallback");ok(Resources.foreignReads==0,"foreign resource ID used");
 host.hidden=false;host.wrongOwner=true;UiText.initialize(host,p);ok(UiText.get(first,"original").equals("original"),"wrong module owner");ok(Resources.foreignReads==0,"wrong owner read");
 host.wrongOwner=false;UiText.initialize(host,p);ok(UiText.get(-1,"missing resource").equals("missing resource"),"missing ID fallback");
 Resources.ES.put(-2,"%2$s");ok(UiText.get(-2,"Source %1$s","value").equals("Source value"),"malformed target must not crash");
 Context activity=new Context("not-an-application");activity.application=host;UiText.initialize(activity,p);ok(UiText.get(first,"original").equals(input.get(0)[2]),"uses application context");
 ok(InternalLabels.text("ACTIVE").equals("Activo"),"presentation status");ok(InternalLabels.text("not-a-label").equals("not-a-label"),"unknown metadata unchanged");
 ok(PreferenceLabels.text("disable_sensor_proximity").contains("proximidad"),"catalog restart title");
 ok(InternalLabels.hmaReason("Not configured in HMA scope").equals("No está incluida en el ámbito de HMA"),"HMA not_configured");
 ok(InternalLabels.hmaReason("Active in HMA, module visible").equals("Activa en HMA; el módulo es visible"),"HMA visible");
 ok(InternalLabels.hmaReason("Hidden via Whitelist mode (in invisible apps list)").equals("Oculto por la lista de permitidos (incluido entre las aplicaciones no visibles)"),"HMA allowlist_invisible");
 ok(InternalLabels.hmaReason("Hidden via Whitelist mode (module not included in visible apps)").equals("Oculto por la lista de permitidos (el módulo no está entre las aplicaciones visibles)"),"HMA allowlist_missing");
 ok(InternalLabels.hmaReason("Hidden via Blacklist mode (in additional apps to hide)").equals("Oculto por la lista de bloqueados (incluido entre las aplicaciones adicionales que se ocultan)"),"HMA blocklist_extra");
 ok(InternalLabels.hmaReason("Hidden by HMA \"xposed\" preset").equals("Oculto por el ajuste predefinido «xposed» de HMA"),"HMA preset");
 String template="Mine %1$s \" 50%";
 ok(InternalLabels.hmaReason("Hidden by blacklist template \""+template+"\"").equals("Oculto por la plantilla de bloqueo «"+template+"»"),"template stays literal");
 ok(InternalLabels.hmaReason("unrecognized local reason").equals("unrecognized local reason"),"unknown reason unchanged");
 ok(InternalLabels.hmaReason("Hidden by blacklist template \"unfinished").equals("Hidden by blacklist template \"unfinished"),"malformed template unchanged");
 p.values.put("app_language","en");
 ok(InternalLabels.hmaReason("Hidden by blacklist template \""+template+"\"").equals("Hidden by blacklist template \""+template+"\""),"default template preserved");
 p.values.put("app_language","es-419");

 Locale.setDefault(previous);System.out.println("PASS: "+n+" production resource/format/locale checks; 10,000 cache-hit reads; explicit Android doubles, not Android runtime");
 }
}
'''
KOTLIN=r'''
import android.content.*
import com.waenhancer.utils.*
fun main() {
 val prefs=object:SharedPreferences,SharedPreferences.Editor {
  var code="es"
  override fun getString(k:String,d:String):String=code
  override fun edit():SharedPreferences.Editor=this
  override fun putString(k:String,v:String):SharedPreferences.Editor {code=v;return this}
  override fun apply(){}
 }
 val c=Context("com.waenhancer");c.preferences=prefs;UiText.initialize(c,prefs)
 check(LocaleHelper.getSelectedLanguageCode(c)=="es-419")
 check(prefs.code=="es") // Read-time alias does not rewrite data.
 check(LocaleHelper.SUPPORTED_LANGUAGES.count{it.code=="es-419"}==1)
 check(LocaleHelper.getLanguageDisplayName("es")=="Español (Latinoamérica)")
 check(LocaleHelper.getLanguageExplanation("en").lowercase().contains("inglés"))
 val wrapped=LocaleHelper.wrapContext(c)
 check(wrapped.resources.configuration.locales[0].toLanguageTag()=="es-419")
 LocaleHelper.applyLanguage(c,"es-419");check(prefs.code=="es-419")
 println("PASS: 7 production Kotlin picker/migration/wrapped-context checks with Android doubles")
}
'''
with tempfile.TemporaryDirectory(prefix='waex_es419_') as temp:
    tmp=Path(temp);sources=[]
    for path,code in DOUBLES.items():
        p=tmp/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code);sources.append(p)
    r,ids=write_r(ROOT,tmp);sources.append(r)
    runner=tmp/'qa/TestEs419Runtime.java';runner.parent.mkdir();runner.write_text(RUNNER);sources.append(runner)
    sources += [SRC/p for p in ['UiText.java','DisplayLocalePolicy.java','InternalLabels.java','PreferenceLabels.java']]
    sources.append(ROOT/'app/src/main/java/com/waenhancer/config/PreferenceKeyAliases.java')
    en=entries(ROOT,'values');es=entries(ROOT,'values-b+es+419');fixture=tmp/'resource-values.tsv'
    def b64(s):return base64.b64encode(s.encode()).decode()
    fixture.write_text('\n'.join(f'{ids[k]}\t{b64(en[k][0])}\t{b64(es[k][0])}\t{str(en[k][1]).lower()}' for k in sorted(ids)))
    classes=tmp/'classes';classes.mkdir()
    subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True,timeout=90)
    subprocess.run(['java','-Dfile.encoding=UTF-8','-cp',str(classes),'qa.TestEs419Runtime',str(fixture)],check=True,timeout=45)
    kt=tmp/'TestLocale.kt';kt.write_text(KOTLIN)
    subprocess.run(['kotlinc','-cp',str(classes),str(SRC/'LocaleHelper.kt'),str(kt),'-d',str(tmp/'test.jar')],check=True,timeout=90)
    subprocess.run(['kotlin','-cp',str(classes)+':'+str(tmp/'test.jar'),'TestLocaleKt'],check=True,timeout=45)

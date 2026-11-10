package com.waenhancer.xposed.features.media;
import java.lang.reflect.Field;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import com.waenhancer.xposed.features.customization.PropsOverrideHook;
public final class MediaProcessFieldContractTest {
 private static int checks=0,failed=0;
 private static void check(boolean ok,String label) {
  checks++;if(!ok){failed++;System.out.println("FAIL "+label);}
 }
 static class Quality {
  public int kb=1024, percent=75, edge=4096;
  public boolean swapped,throwing,malformed;
  @Override public String toString(){
   if(throwing)throw new IllegalStateException("private-image-fixture-payload");
   if(malformed)return "unknown-format";
   return "ProcessImageQuality(maxKb="+(swapped?edge:kb)+", quality="+percent+", maxEdge="+(swapped?kb:edge)+")";
  }
 }
 static final class Equal {
  public int kb=1024, percent=75, edge=1024;
  @Override public String toString(){
   return "ProcessImageQuality(maxKb="+kb+", quality="+percent+", maxEdge="+edge+")";
  }
 }
 static final class Invalid {
  public int kb=1024; public float percent=75;public int edge=4096;
  @Override public String toString(){return "not ProcessImageQuality";}
 }
 static final class StaticQuality {
  public static int kb=1024, percent=75, edge=4096;
  @Override public String toString(){return "ProcessImageQuality(maxKb="+kb+", quality="+percent+", maxEdge="+edge+")";}
 }
 static final class Foreign {public int kb=1024, percent=75, edge=4096;}
 static final class Throwing {
  public int kb=1024, percent=75, edge=4096;
  @Override public String toString(){throw new IllegalStateException("private-fixture-payload");}
 }
 static final class ChildQuality extends Quality {}
 static final class Preferences implements SharedPreferences {
  final Map<String,Boolean> data=new HashMap<>();
  @Override public boolean getBoolean(String key,boolean fallback){return data.getOrDefault(key,fallback);}
 }
 private static XC_MethodHook.MethodHookParam invokeAfter(XC_MethodHook cb,Object value,Throwable failure)throws Throwable {
  XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();
  param.thisObject=value;param.args=new Object[]{1024,75,4096};
  if(failure!=null)param.setThrowable(failure);
  cb.after(param);return param;
 }
 private static void callbackChecks()throws Throwable {
  Field kb=Quality.class.getField("kb"),percent=Quality.class.getField("percent"),edge=Quality.class.getField("edge");
  Preferences prefs=new Preferences();prefs.data.put("imagequality",true);
  MediaQualityBypassHook hook=new MediaQualityBypassHook(new Context(),MediaProcessFieldContractTest.class.getClassLoader(),prefs);
  XC_MethodHook cb=hook.createProcessImageCallback(Quality.class,kb,percent,edge);
  MediaQualityBypassHook.configurePropertyOverrides(true);
  XposedBridge.logs.clear();
  Quality a=new Quality(); invokeAfter(cb,a,null);
  check(a.kb==51200 && a.percent==100 && a.edge==6000,"callback applies expected defaults");
  Quality b=new Quality();b.malformed=true;invokeAfter(cb,b,null);
  check(b.kb==1024 && b.percent==75 && b.edge==4096,"callback revalidates each instance after first success");
  Quality c=new Quality();c.kb=80000;c.edge=9000;invokeAfter(cb,c,null);
  check(c.kb==80000 && c.percent==100 && c.edge==9000,"callback does not lower larger host limits");
  Throwable failure=new IllegalArgumentException("private-constructor-failure");
  Quality d=new Quality(); XC_MethodHook.MethodHookParam result=invokeAfter(cb,d,failure);
  check(result.getThrowable()==failure,"constructor exception object preserved");
  check(d.kb==1024 && d.percent==75 && d.edge==4096,"failed constructor not modified");
  prefs.data.put("imagequality",false);Quality off=new Quality();invokeAfter(cb,off,null);
  check(off.kb==1024 && off.percent==75 && off.edge==4096,"live opt-out leaves original fields");
  prefs.data.put("imagequality",true);Quality reenabled=new Quality();invokeAfter(cb,reenabled,null);
  check(reenabled.percent==100,"live re-enable works on next instance");
  ChildQuality child=new ChildQuality();invokeAfter(cb,child,null);
  check(child.kb==1024 && child.percent==75 && child.edge==4096,"subclass route left untouched");
  invokeAfter(cb,null,null);check(true,"null constructor receiver tolerated");
  Quality invalid=new Quality();invalid.percent=-1;invokeAfter(cb,invalid,null);
  check(invalid.kb==1024 && invalid.percent==-1 && invalid.edge==4096,"callback preserves unknown sentinel tuple");
  Quality thrown=new Quality();thrown.throwing=true;invokeAfter(cb,thrown,null);
  check(thrown.kb==1024 && thrown.percent==75 && thrown.edge==4096,"toString failure does not mutate fields");
  check(XposedBridge.logs.stream().noneMatch(x->x.contains("private-")),"logs exclude host error payload");
  for(int i=0;i<20;i++){Quality bad=new Quality();bad.malformed=true;invokeAfter(cb,bad,null);}
  check(XposedBridge.logs.size()==1,"invalid field map warning bounded per callback");
  PropsOverrideHook.INTEGERS.put(6029,84);Quality custom=new Quality();invokeAfter(cb,custom,null);
  check(custom.kb==1024 && custom.percent==75 && custom.edge==4096,"callback defers to conflicting alternate custom ID");
  check(PropsOverrideHook.INTEGERS.get(6029)==84,"callback preserves custom map entry");
  PropsOverrideHook.INTEGERS.put(6029,100);PropsOverrideHook.BOOLEANS.put(6033,false);
  Quality customFalse=new Quality();invokeAfter(cb,customFalse,null);
  check(customFalse.percent==75,"custom false prevents constructor enforcement");
  check(Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(6033)),"callback never erases explicit false");
  PropsOverrideHook.BOOLEANS.put(6033,true);Quality normalAgain=new Quality();invokeAfter(cb,normalAgain,null);
  check(normalAgain.percent==100,"valid defaults may be re-applied after config recovery");
  XC_MethodHook shuffled=hook.createProcessImageCallback(Quality.class,edge,kb,percent);
  Quality reordered=new Quality();invokeAfter(shuffled,reordered,null);
  check(reordered.kb==51200 && reordered.percent==100 && reordered.edge==6000,"metadata field order irrelevant");
  Field[] association=MediaQualityBypassHook.resolveImageFields(new Quality(),edge,percent,kb);
  check(association!=null && association[0].equals(kb) && association[1].equals(percent) && association[2].equals(edge),"label association independent of list order");
  int originalMax=normalAgain.kb;invokeAfter(cb,normalAgain,null);
  check(normalAgain.kb==originalMax && normalAgain.percent==100,"repeated completion does not accumulate changes");
 }
 public static void main(String[] args)throws Throwable {
  Quality value=new Quality();
  Field kb=Quality.class.getField("kb"),percent=Quality.class.getField("percent"),edge=Quality.class.getField("edge");
  check(MediaQualityBypassHook.distinctPrimitiveIntFields(kb,percent,edge),"distinct fields");
  check(MediaQualityBypassHook.verifiedImageFieldValues(value,kb,percent,edge),"exact field-to-label observation");
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,edge,percent,kb),"reject swapped maxKb/maxEdge");
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,kb,kb,edge),"reject duplicate fields");
  value.kb=1200;
  check(MediaQualityBypassHook.restoreField(kb,value,1024) && value.kb==1024,
        "successful rollback is acknowledged");
  check(!MediaQualityBypassHook.restoreField(kb,null,1024),
        "failed rollback must not claim success");

  check(!MediaQualityBypassHook.verifiedImageFieldValues(null,kb,percent,edge),"reject null receiver");
  check(!MediaQualityBypassHook.verifiedImageFieldValues(new Equal(),Equal.class.getField("kb"),Equal.class.getField("percent"),Equal.class.getField("edge")),"reject coinciding label values");
  Invalid invalid=new Invalid();
  check(!MediaQualityBypassHook.verifiedImageFieldValues(invalid,Invalid.class.getField("kb"),Invalid.class.getField("percent"),Invalid.class.getField("edge")),"reject float field / malformed text");
  Field duplicate=Quality.class.getField("kb");
  check(kb.equals(duplicate), "reflection returns equal descriptor");
  check(!MediaQualityBypassHook.distinctPrimitiveIntFields(kb,duplicate,edge), "reject equal descriptor even from separate Field instances");
  check(!MediaQualityBypassHook.distinctPrimitiveIntFields(StaticQuality.class.getField("kb"),StaticQuality.class.getField("percent"),StaticQuality.class.getField("edge")), "reject static fields");
  check(!MediaQualityBypassHook.distinctPrimitiveIntFields(kb,Foreign.class.getField("percent"),edge), "reject mixed declaring classes");
  check(!MediaQualityBypassHook.verifiedImageFieldValues(new StaticQuality(),StaticQuality.class.getField("kb"),StaticQuality.class.getField("percent"),StaticQuality.class.getField("edge")), "no static receiver verification");
  check(!MediaQualityBypassHook.verifiedImageFieldValues(new Throwing(),Throwing.class.getField("kb"),Throwing.class.getField("percent"),Throwing.class.getField("edge")), "toString failure rejected");
  value.percent=-1;
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,kb,percent,edge), "negative quality sentinel unchanged");
  value.percent=0;
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,kb,percent,edge), "zero unverified quality unchanged");
  value.percent=101;
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,kb,percent,edge), "out of range quality rejected");
  value.percent=75; value.kb=0;
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,kb,percent,edge), "nonpositive size sentinel rejected");
  value.kb=1024;value.edge=-1;
  check(!MediaQualityBypassHook.verifiedImageFieldValues(value,kb,percent,edge), "negative dimension sentinel rejected");
  value.edge=4096;
  MediaQualityBypassHook.configurePropertyOverrides(true);
  check(Integer.valueOf(51200).equals(PropsOverrideHook.INTEGERS.get(1577)),"max size property installed");
  check(Integer.valueOf(100).equals(PropsOverrideHook.INTEGERS.get(1578)),"quality property installed");
  check(Integer.valueOf(6000).equals(PropsOverrideHook.INTEGERS.get(1576)),"max edge property installed");
  check(Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(6033)),"boolean true property");
  check(Boolean.FALSE.equals(PropsOverrideHook.BOOLEANS.get(9569)),"boolean false preserved");
  int[] defaultProfile=MediaQualityBypassHook.effectiveImageParameters();
  check(defaultProfile!=null && defaultProfile[0]==51200 && defaultProfile[1]==100 && defaultProfile[2]==6000,
        "default profile reads current property map");
  PropsOverrideHook.INTEGERS.put(1578, 77);
  check(MediaQualityBypassHook.effectiveImageParameters()==null, "custom quality disables conflicting constructor write");
  PropsOverrideHook.INTEGERS.put(2475, 61);
  check(MediaQualityBypassHook.effectiveImageParameters()==null,
        "unknown alternate host property route blocks unsafe constructor override");
  PropsOverrideHook.INTEGERS.remove(2475);
  PropsOverrideHook.INTEGERS.put(1578, 0);
  check(MediaQualityBypassHook.effectiveImageParameters()==null, "invalid quality preserved without write");
  PropsOverrideHook.INTEGERS.put(1578, 100);
  PropsOverrideHook.INTEGERS.put(6029, 84);
  check(MediaQualityBypassHook.effectiveImageParameters()==null, "custom alternative quality ID respected");
  check(PropsOverrideHook.INTEGERS.get(6029)==84, "read policy does not erase custom");
  PropsOverrideHook.INTEGERS.put(6029,100);
  PropsOverrideHook.BOOLEANS.put(6033,false);
  check(MediaQualityBypassHook.effectiveImageParameters()==null, "explicit custom false respected");
  PropsOverrideHook.BOOLEANS.put(6033,true);
  PropsOverrideHook.INTEGERS.remove(1577);
  check(MediaQualityBypassHook.effectiveImageParameters()==null, "missing early property configuration must not invent defaults");
  MediaQualityBypassHook.configurePropertyOverrides(false);
  check(!PropsOverrideHook.INTEGERS.containsKey(1577),"disable removes image override");
  check(!PropsOverrideHook.BOOLEANS.containsKey(6033),"disable removes boolean override");
  callbackChecks();
  System.out.println("FIELD_CONTRACT checks="+checks+" failures="+failed);
  if(failed!=0)throw new AssertionError(failed+" field contract failures");
 }
}

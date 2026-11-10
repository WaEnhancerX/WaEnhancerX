#!/usr/bin/env python3
"""Compile the complete profile-photo hook with explicit Android/Xposed doubles.

Runs the actual executor and menu callback. Bitmap allocations are counted by a
bitmap double, not measured heap usage; storage and UI are not a device test.
"""
from pathlib import Path
import argparse, hashlib, json, subprocess, tempfile
from test_gallery_export import STUBS as STORAGE_STUBS
ROOT=Path(__file__).resolve().parents[1]
HOOK=Path('app/src/main/java/com/waenhancer/xposed/features/media/ProfilePhotoDownloadHook.java')
STUBS=dict(STORAGE_STUBS)
STUBS.update({
'android/R.java': 'package android;public class R{public static class drawable{public static final int stat_sys_download_done=1;}}',
'androidx/annotation/NonNull.java':'package androidx.annotation;public @interface NonNull{}',
'android/content/SharedPreferences.java':'''package android.content;import java.util.*;public interface SharedPreferences{boolean getBoolean(String key,boolean fallback);Set<String> getStringSet(String key,Set<String> fallback);Editor edit();interface Editor{Editor putStringSet(String key,Set<String> value);boolean commit();}}''',
'android/content/Context.java':'''package android.content;public class Context{public final ContentResolver r;public Context(ContentResolver value){r=value;}public ContentResolver getContentResolver(){return r;}public Context getApplicationContext(){return this;}}''',
'android/app/Activity.java':'''package android.app;
import android.content.*;import android.view.*;import java.util.concurrent.*;
public class Activity extends Context{
 public boolean destroyed,finishing;public Window window;public Context app;public final ConcurrentLinkedQueue<Runnable> ui=new ConcurrentLinkedQueue<>();
 public Activity(ContentResolver r){super(r);app=new Context(r);}public Window getWindow(){return window;}
 public boolean isFinishing(){return finishing;}public boolean isDestroyed(){return destroyed;}
 public Context getApplicationContext(){return app;}public void runOnUiThread(Runnable r){ui.add(r);}public void drain(){Runnable r;while((r=ui.poll())!=null)r.run();}
}''',
'android/view/Window.java':'package android.view;public class Window{public View root;public Window(View v){root=v;}public View getDecorView(){return root;}}',
'android/view/View.java':'package android.view;import android.content.Context;public class View{public static final int VISIBLE=0,GONE=8;public int width=64,height=64,visibility=0;public Context context;public View(Context c){context=c;}public int getVisibility(){return visibility;}public int getWidth(){return width;}public int getHeight(){return height;}}',
'android/view/ViewGroup.java':'package android.view;import android.content.Context;import java.util.*;public class ViewGroup extends View{public final List<View> children=new ArrayList<>();public ViewGroup(Context c){super(c);}public int getChildCount(){return children.size();}public View getChildAt(int i){return children.get(i);}}',
'android/view/Menu.java':'package android.view;public class Menu{public static final int NONE=0;public MenuItem item;public MenuItem findItem(int id){return item!=null&&item.id==id?item:null;}public MenuItem add(int group,int id,int order,String title){return item=new MenuItem(id);}}',
'android/view/MenuItem.java':'package android.view;public class MenuItem{public static final int SHOW_AS_ACTION_ALWAYS=2;public int id;public MenuItem(int i){id=i;}public interface OnMenuItemClickListener{boolean onMenuItemClick(MenuItem item);}public OnMenuItemClickListener listener;public void setIcon(int id){}public void setShowAsAction(int f){}public void setOnMenuItemClickListener(OnMenuItemClickListener l){listener=l;}public boolean click(){return listener.onMenuItemClick(this);}}',
'android/graphics/Rect.java':'package android.graphics;public class Rect{public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}',
'android/graphics/Bitmap.java':'''package android.graphics;
import java.io.*;import java.util.concurrent.atomic.AtomicInteger;
public class Bitmap{
 public enum Config{ARGB_8888}public enum CompressFormat{JPEG}
 public static AtomicInteger copies=new AtomicInteger(),creates=new AtomicInteger(),recycles=new AtomicInteger();
 public static volatile boolean encodeFalse,encodeFatal;public static volatile Bitmap last;public int width,height;public boolean recycled,copyThrows;
 public Bitmap(int w,int h){width=w;height=h;}public int getWidth(){return width;}public int getHeight(){return height;}
 public boolean isRecycled(){return recycled;}public void recycle(){if(!recycled){recycled=true;recycles.incrementAndGet();}}
 public Bitmap copy(Config c,boolean mutable){copies.incrementAndGet();if(copyThrows)throw new IllegalStateException("private-contact-copy");return last=new Bitmap(width,height);}
 public static Bitmap createBitmap(int w,int h,Config c){creates.incrementAndGet();return last=new Bitmap(w,h);}
 public boolean compress(CompressFormat format,int quality,OutputStream out){if(encodeFatal)throw new SyntheticFatal();if(encodeFalse)return false;try{out.write(new byte[]{(byte)255,(byte)216,(byte)255,1,2,3});return true;}catch(IOException failure){throw new IllegalStateException("private-contact-encode",failure);}}
 public static final class SyntheticFatal extends Error{}
}''',
'android/graphics/Canvas.java':'package android.graphics;public class Canvas{public Canvas(Bitmap b){}}',
'android/graphics/drawable/Drawable.java':'package android.graphics.drawable;import android.graphics.*;public class Drawable{public Rect bounds=new Rect(2,3,7,11);public int width=64,height=64;public boolean failDraw,failRestore;public Rect getBounds(){return bounds;}public void setBounds(int l,int t,int r,int b){bounds=new Rect(l,t,r,b);if(failRestore&&l==2&&t==3)throw new IllegalStateException("private-contact-restore");}public int getIntrinsicWidth(){return width;}public int getIntrinsicHeight(){return height;}public void draw(Canvas c){if(failDraw)throw new IllegalStateException("private-contact-draw");}}',
'android/graphics/drawable/BitmapDrawable.java':'package android.graphics.drawable;import android.graphics.Bitmap;public class BitmapDrawable extends Drawable{public Bitmap bitmap;public BitmapDrawable(Bitmap b){bitmap=b;width=b.width;height=b.height;}public Bitmap getBitmap(){return bitmap;}}',
'android/widget/ImageView.java':'package android.widget;import android.view.View;import android.content.Context;import android.graphics.drawable.Drawable;public class ImageView extends View{public Drawable drawable;public ImageView(Context c,Drawable d){super(c);drawable=d;}public Drawable getDrawable(){return drawable;}}',
'android/widget/Toast.java':'package android.widget;import android.content.Context;import java.util.*;public class Toast{public static final int LENGTH_SHORT=0,LENGTH_LONG=1;public static final List<String> messages=Collections.synchronizedList(new ArrayList<>());public String text;public Toast(String t){text=t;}public static Toast makeText(Context c,String t,int d){return new Toast(t);}public void show(){messages.add(text);}}',
'com/waenhancer/app/R.java':'package com.waenhancer.app;public class R{public static class string{public static final int waex_ui_download_d6eafe82=1,waex_ui_profile_photo_is_not_ready_yet_8ff494b5=2,waex_ui_profile_photo_saved_to_pictures_waex_p_a8c4bd76=3,waex_ui_couldn_t_save_profile_photo_296d0ed9=4,profile_photo_already_saved=5;}}',
'com/waenhancer/utils/UiText.java':'package com.waenhancer.utils;public class UiText{public static String get(int id,String fallback){return fallback;}}',
'com/waenhancer/utils/ContactIdentityPolicy.java':'package com.waenhancer.utils;public class ContactIdentityPolicy{public static String canonicalJid(String jid){return jid!=null&&jid.contains("@")?jid:null;}}',
'com/waenhancer/utils/ContactNameResolver.java':'package com.waenhancer.utils;import android.app.Activity;public class ContactNameResolver{public static final ContactNameResolver INSTANCE=new ContactNameResolver();public String extractJidFromActivity(Activity activity){return ((TestActivity)activity).jid;}}',
'com/waenhancer/utils/TestActivity.java':'package com.waenhancer.utils;import android.app.Activity;import android.content.ContentResolver;public class TestActivity extends Activity{public String jid="12345@s.whatsapp.net";public TestActivity(ContentResolver resolver){super(resolver);}}',
'com/waenhancer/xposed/core/BaseFeature.java':'package com.waenhancer.xposed.core;import android.content.*;public abstract class BaseFeature{protected final Context context;protected final ClassLoader classLoader;protected final SharedPreferences prefs;public BaseFeature(Context c,ClassLoader l,SharedPreferences p){context=c;classLoader=l;prefs=p;}protected boolean isEnabled(String key,boolean fallback){return prefs.getBoolean(key,fallback);}public abstract String getName();public abstract void hook();}',
'de/robv/android/xposed/XC_MethodHook.java':'package de.robv.android.xposed;public class XC_MethodHook{public static class Unhook{}public static class MethodHookParam{public Object thisObject;public Object[] args;public Throwable throwable;public boolean hasThrowable(){return throwable!=null;}}protected void afterHookedMethod(MethodHookParam p){}public void dispatch(MethodHookParam p){afterHookedMethod(p);}}',
'de/robv/android/xposed/XposedBridge.java':'package de.robv.android.xposed;import java.util.*;public class XposedBridge{public static XC_MethodHook hook;public static final List<String> logs=new ArrayList<>();public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c,String name,XC_MethodHook h){hook=h;return new HashSet<>();}public static void log(String message){logs.add(message);}}',
'de/robv/android/xposed/XposedHelpers.java':'package de.robv.android.xposed;public class XposedHelpers{public static Class<?> findClassIfExists(String name,ClassLoader loader){return android.app.Activity.class;}}',
'org/luckypray/dexkit/query/FindClass.java':'package org.luckypray.dexkit.query;public class FindClass{public static FindClass create(){return new FindClass();}public FindClass matcher(Object o){return this;}}',
'org/luckypray/dexkit/query/enums/StringMatchType.java':'package org.luckypray.dexkit.query.enums;public enum StringMatchType{EndsWith}',
'org/luckypray/dexkit/query/matchers/ClassMatcher.java':'package org.luckypray.dexkit.query.matchers;public class ClassMatcher{public static ClassMatcher create(){return new ClassMatcher();}public ClassMatcher className(String n,Object m){return this;}}',
'org/luckypray/dexkit/result/ClassData.java':'package org.luckypray.dexkit.result;public class ClassData{public Class<?> getInstance(ClassLoader c){return null;}}',
'com/waenhancer/xposed/core/devkit/DexSearchEngine.java':'package com.waenhancer.xposed.core.devkit;import android.content.Context;import org.luckypray.dexkit.result.ClassData;public class DexSearchEngine{public interface Query{Class<?> resolve(Bridge bridge,ClassLoader loader);}public static class Bridge{public Results findClass(Object o){return new Results();}}public static class Results{public ClassData firstOrNull(){return null;}}public static DexSearchEngine getInstance(){return new DexSearchEngine();}public Class<?> findClassWithCache(Context c,ClassLoader l,String k,Query q){return null;}}',
})
STUBS['android/content/ContentResolver.java'] = '''package android.content;
import android.net.Uri;import java.io.*;import java.util.*;
public class ContentResolver {
 public String fault="";public int creates,updates,successReports; public boolean closed,pendingDuringWrite,publishAfterClose,earlySuccess;
 public Runnable onOpen;public byte[] copied;
 public final List<ContentValues> inserted=new ArrayList<>();
 public final List<String> collections=new ArrayList<>(),deleted=new ArrayList<>();
 public Uri insert(Uri collection,ContentValues values){if(fault.equals("nullInsert"))return null; creates++;inserted.add(values);collections.add(collection.toString());closed=false;return Uri.parse(collection+"/"+creates);}
 public OutputStream openOutputStream(Uri row)throws IOException{return openOutputStream(row,"w");}
 public OutputStream openOutputStream(Uri row,String mode)throws IOException {
  if(onOpen!=null)onOpen.run();if(fault.equals("nullOutput"))return null;
  if(fault.equals("open"))throw new IOException("private-path-open");
  ByteArrayOutputStream data=new ByteArrayOutputStream();
  return new OutputStream(){
   public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
   public void write(byte[] b,int o,int n)throws IOException{
    pendingDuringWrite=Integer.valueOf(1).equals(inserted.get(inserted.size()-1).get("pending"));
    if(fault.equals("write")||fault.equals("delete"))throw new IOException("private-payload-write");data.write(b,o,n);
   }
   public void flush()throws IOException{if(fault.equals("flush"))throw new IOException("flush");}
   public void close()throws IOException{closed=true;copied=data.toByteArray();if(fault.equals("close"))throw new IOException("close");}
  };
 }
 public int update(Uri row,ContentValues values,String selection,String[] args){updates++;publishAfterClose=closed;if(fault.equals("updateThrow"))throw new IllegalStateException("update");return fault.equals("updateZero")?0:fault.equals("updateMany")?2:1;}
 public int delete(Uri row,String selection,String[] args){deleted.add(row.toString());if(fault.equals("delete"))throw new IllegalStateException("rollback");return 1;}
 public void success(){successReports++;if(!closed)earlySuccess=true;}
}'''
STUBS['android/provider/MediaStore.java']=STUBS['android/provider/MediaStore.java'].replace('class Images{public static class Media{','class Images{public static class Media extends MediaColumns{')

TEST=r'''package com.waenhancer.xposed.features.media;
import android.app.*;import android.content.*;import android.graphics.*;import android.graphics.drawable.*;import android.view.*;import android.widget.*;import de.robv.android.xposed.*;
import java.io.*;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
public class TestProfilePhotoSave{
 static int cases,failed,checks;static boolean baseline;
 interface Test{void run()throws Exception;}
 static void test(String name,Test body){cases++;Toast.messages.clear();XposedBridge.logs.clear();Bitmap.encodeFalse=false;Bitmap.encodeFatal=false;try{body.run();System.out.println("PASS "+name);}catch(Throwable t){failed++;System.out.println("FAIL "+name+": "+t.getClass().getSimpleName()+" "+t.getMessage());}}
 static void ck(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static final class Env{
  final ContentResolver resolver;final com.waenhancer.utils.TestActivity activity;final Bitmap original=new Bitmap(256,256);final ImageView image;final Menu menu=new Menu();final ProfilePhotoDownloadHook hook;boolean enabled=true,saveDuplicates=true;final Set<String> savedIdentities=new LinkedHashSet<>();
  final SharedPreferences prefs=new SharedPreferences(){public boolean getBoolean(String key,boolean fallback){if(key.equals("download_profile"))return enabled;if(key.equals("download_profile_save_duplicates"))return saveDuplicates;return fallback;}public Set<String> getStringSet(String key,Set<String> fallback){return key.equals("download_profile_saved_identities")?new LinkedHashSet<>(savedIdentities):fallback;}public Editor edit(){return new Editor(){Set<String> pending;public Editor putStringSet(String key,Set<String> value){if(key.equals("download_profile_saved_identities"))pending=new LinkedHashSet<>(value);return this;}public boolean commit(){if(pending!=null){savedIdentities.clear();savedIdentities.addAll(pending);}return true;}};}};
  Env(ContentResolver r){resolver=r;activity=new com.waenhancer.utils.TestActivity(r);image=new ImageView(activity,new BitmapDrawable(original));ViewGroup root=new ViewGroup(activity);root.children.add(image);activity.window=new Window(root);hook=new ProfilePhotoDownloadHook(activity.app,TestProfilePhotoSave.class.getClassLoader(),prefs);hook.hook();XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=activity;p.args=new Object[]{menu};XposedBridge.hook.dispatch(p);}
  void click(){menu.item.click();}
  void finish()throws Exception{Field f=ProfilePhotoDownloadHook.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(hook)).submit(()->{}).get(10,TimeUnit.SECONDS);activity.drain();((ExecutorService)f.get(hook)).shutdownNow();}
 }
 public static void main(String[] args)throws Exception{
  baseline=args.length>0&&args[0].equals("baseline");
  test("success waits for close and publish",()->{Env e=new Env(new ContentResolver());e.click();e.finish();ck(e.resolver.closed&&e.resolver.publishAfterClose&&e.resolver.updates==1,"ack closed output");ck(e.resolver.deleted.isEmpty(),"published row retained");ck(!e.original.recycled&&Bitmap.last.recycled,"only owned copy recycled");ck(Toast.messages.size()==1&&Toast.messages.get(0).startsWith("Profile photo saved"),"success toast");});
  for(String fault:new String[]{"nullInsert","nullOutput","open","write","flush","close","updateZero","updateMany","updateThrow","delete"})test("storage failure "+fault,()->{ContentResolver r=new ContentResolver();r.fault=fault;Env e=new Env(r);e.click();e.finish();ck(Toast.messages.size()==1&&Toast.messages.get(0).startsWith("Couldn't"),"failure toast only");ck(r.creates==0||r.deleted.size()==1,"owned partial removed");ck(!e.original.recycled&&Bitmap.last.recycled,"copy released");ck(XposedBridge.logs.stream().noneMatch(s->s.contains("private-contact")||s.contains("private-path")||s.contains("private-payload")),"payload-free diagnostics");});
  test("failed encode removes destination",()->{Bitmap.encodeFalse=true;Env e=new Env(new ContentResolver());e.click();e.finish();ck(e.resolver.deleted.size()==1&&Toast.messages.get(0).startsWith("Couldn't"),"encoding failure acknowledged");});
  test("1000 pending clicks allocate one copy",()->{
   CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);ContentResolver r=new ContentResolver();r.onOpen=()->{opened.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("blocked writer timeout");}catch(InterruptedException ex){throw new RuntimeException(ex);}};Env e=new Env(r);int before=Bitmap.copies.get();
   try{e.click();ck(opened.await(5,TimeUnit.SECONDS),"writer started");for(int i=0;i<1000;i++)e.click();System.out.println("MEASURE pending_clicks=1000 bitmap_copy_calls="+(Bitmap.copies.get()-before));ck(Bitmap.copies.get()-before==1,"admit before allocating bitmap");}finally{release.countDown();e.finish();System.out.println("MEASURE exported_rows="+r.creates);}
   ck(r.creates==1,"no obsolete queued saves");
  });
  test("destroyed owner receives no completion toast",()->{Env e=new Env(new ContentResolver());e.activity.destroyed=true;e.click();e.finish();ck(e.resolver.creates==0&&Toast.messages.isEmpty(),"stale menu ignored");});
  test("destroy owner after save request",()->{CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);ContentResolver r=new ContentResolver();r.onOpen=()->{opened.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ex){throw new RuntimeException(ex);}};Env e=new Env(r);e.click();ck(opened.await(5,TimeUnit.SECONDS),"worker started");e.activity.destroyed=true;release.countDown();e.finish();ck(r.updates==1&&Toast.messages.isEmpty(),"finish requested storage without stale UI");});
  test("destroy owner before posted completion dispatch",()->{Env e=new Env(new ContentResolver());e.click();Field f=ProfilePhotoDownloadHook.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(e.hook)).submit(()->{}).get(5,TimeUnit.SECONDS);e.activity.destroyed=true;e.finish();ck(e.resolver.updates==1&&Toast.messages.isEmpty(),"posted callback rechecks owner lifetime");});
  test("live setting rechecked at click",()->{Env e=new Env(new ContentResolver());e.enabled=false;e.click();e.finish();ck(e.resolver.creates==0,"disable before click prevents export");});
  test("host error leaves menu alone",()->{Env e=new Env(new ContentResolver());Menu menu=new Menu();XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=e.activity;p.args=new Object[]{menu};p.throwable=new IllegalStateException("host");XposedBridge.hook.dispatch(p);e.finish();ck(menu.item==null&&p.throwable!=null,"host failure not decorated");});
  test("drawable failure restores host bounds",()->{Env e=new Env(new ContentResolver());Drawable d=new Drawable();d.failDraw=true;e.image.drawable=d;int recycled=Bitmap.recycles.get();boolean escaped=false;try{e.click();}catch(RuntimeException unexpected){escaped=true;}ck(!escaped,"capture failure isolated");ck(d.bounds.left==2&&d.bounds.top==3&&d.bounds.right==7&&d.bounds.bottom==11,"host drawable restored");ck(Bitmap.recycles.get()==recycled+1,"failed render copy freed");e.image.drawable=new BitmapDrawable(e.original);e.click();e.finish();ck(e.resolver.creates==1,"admission recovered");});
  test("bounds restoration error releases rendered copy",()->{Env e=new Env(new ContentResolver());Drawable d=new Drawable();d.failRestore=true;e.image.drawable=d;int recycled=Bitmap.recycles.get();boolean escaped=false;try{e.click();}catch(RuntimeException unexpected){escaped=true;}ck(!escaped&&Bitmap.recycles.get()==recycled+1,"restoration failure releases unreturned bitmap");e.image.drawable=new BitmapDrawable(e.original);e.click();e.finish();ck(e.resolver.creates==1,"admission recovered after restore failure");});
  test("bitmap path applies existing 40MP ceiling before copy",()->{Env e=new Env(new ContentResolver());e.image.drawable=new BitmapDrawable(new Bitmap(8000,6000));int before=Bitmap.copies.get();e.click();e.finish();ck(Bitmap.copies.get()==before&&e.resolver.creates==0,"ceiling before copied allocation");});
  test("executor rejection frees owned copy and admission",()->{Env e=new Env(new ContentResolver());Field f=ProfilePhotoDownloadHook.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(e.hook)).shutdownNow();boolean escaped=false;try{e.click();}catch(RuntimeException wrong){escaped=true;}ck(!escaped&&Bitmap.last.recycled,"rejection cleanup");e.activity.drain();ck(Toast.messages.size()==1&&Toast.messages.get(0).startsWith("Couldn't"),"rejection acknowledged");});
  test("repeated successful names differ",()->{Env e=new Env(new ContentResolver());e.click();Field f=ProfilePhotoDownloadHook.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(e.hook)).submit(()->{}).get(5,TimeUnit.SECONDS);e.click();e.finish();ck(e.resolver.inserted.size()==2,"second deliberate save permitted");ck(!e.resolver.inserted.get(0).get("name").equals(e.resolver.inserted.get(1).get("name")),"unique names");});
  test("duplicate protection remembers only a successful canonical contact save",()->{Env e=new Env(new ContentResolver());e.saveDuplicates=false;e.click();Field f=ProfilePhotoDownloadHook.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(e.hook)).submit(()->{}).get(5,TimeUnit.SECONDS);ck(e.resolver.inserted.size()==1&&e.savedIdentities.size()==1,"successful contact saved once");e.click();e.finish();ck(e.resolver.inserted.size()==1,"saved contact not exported again");ck(Toast.messages.stream().anyMatch(s->s.equals("Profile photo already saved")),"duplicate toast");});
  test("failed export does not suppress a later retry",()->{ContentResolver r=new ContentResolver();r.fault="open";Env e=new Env(r);e.saveDuplicates=false;e.click();Field f=ProfilePhotoDownloadHook.class.getDeclaredField("io");f.setAccessible(true);((ExecutorService)f.get(e.hook)).submit(()->{}).get(5,TimeUnit.SECONDS);r.fault="";e.click();e.finish();ck(r.creates==2&&e.savedIdentities.size()==1,"only published export remembered");});
  System.out.println("RESULT cases="+cases+" failed="+failed+" checks="+checks);if(failed>0)throw new AssertionError(failed+" cases failed");
 }
}'''

def main():
 p=argparse.ArgumentParser();p.add_argument('--source-root',type=Path,default=ROOT);p.add_argument('--out',type=Path);a=p.parse_args()
 production=a.source_root.resolve()/HOOK
 with tempfile.TemporaryDirectory(prefix='waex-profile-save-') as directory:
  root=Path(directory)
  for path,source in STUBS.items():
   file=root/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(source)
  (root/'TestProfilePhotoSave.java').write_text(TEST)
  compile=subprocess.run(['javac','--release','17','-d',str(root/'classes'),str(production),*map(str,root.rglob('*.java'))],capture_output=True,text=True,timeout=60)
  if a.out:a.out.mkdir(parents=True,exist_ok=True);(a.out/'compile.log').write_text(compile.stdout+compile.stderr)
  if compile.returncode:print(compile.stdout+compile.stderr);raise SystemExit(compile.returncode)
  print('COMPILE_OK complete profile hook with explicit Android/Xposed doubles')
  result=subprocess.run(['java','-cp',str(root/'classes'),'com.waenhancer.xposed.features.media.TestProfilePhotoSave'],capture_output=True,text=True,timeout=60)
  print(result.stdout+result.stderr)
  if a.out:
   (a.out/'execution.log').write_text(result.stdout+result.stderr)
   (a.out/'result.json').write_text(json.dumps({'scope':'Complete production hook, explicit Android/Xposed doubles, real executor; allocation counts are simulated','production_path':str(production),'production_sha256':hashlib.sha256(production.read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'returncode':result.returncode},indent=2)+'\n')
  if result.returncode:raise SystemExit(result.returncode)
if __name__=='__main__':main()

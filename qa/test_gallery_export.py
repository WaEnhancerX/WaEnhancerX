#!/usr/bin/env python3
"""Compile the real exporter with explicit Android storage doubles and real file IO.
Not a MediaProvider, Android permission, decoder, host selector or LSPosed test.
--legacy-hook runs the same storage invariants against an extracted old save body.
"""
from pathlib import Path
import argparse, subprocess, tempfile
ROOT = Path(__file__).resolve().parents[1]
STUBS = {
'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
'androidx/annotation/VisibleForTesting.java': 'package androidx.annotation; public @interface VisibleForTesting {}',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
import java.util.ArrayList;import java.util.List;
public final class XposedBridge {
 public static final List<String> messages=new ArrayList<>();
 public static void log(String message){messages.add(message);}
}''',

'android/content/ContentValues.java': '''package android.content;
import java.util.*; public class ContentValues { public final Map<String,Object> m=new HashMap<>();
public void put(String k,String v){m.put(k,v);}public void put(String k,Integer v){m.put(k,v);}
public Object get(String k){return m.get(k);} }''',
'android/net/Uri.java': '''package android.net; public final class Uri {public final String s; private Uri(String v){s=v;} public static Uri parse(String v){return new Uri(v);} public String toString(){return s;} }''',
'android/os/Build.java': '''package android.os; public class Build {public static class VERSION{public static int SDK_INT=34;}public static class VERSION_CODES{public static final int Q=29;}}''',
'android/os/Environment.java': '''package android.os;import java.io.*;public class Environment {public static final String DIRECTORY_MOVIES="Movies",DIRECTORY_PICTURES="Pictures";public static File root;public static File getExternalStoragePublicDirectory(String d){return new File(root,d);}}''',
'android/media/MediaScannerConnection.java': '''package android.media;import android.content.Context; public class MediaScannerConnection{public static int scans;public static boolean fail;public static void scanFile(Context c,String[] p,String[] m,Object cb){scans++;if(fail)throw new IllegalStateException("private scan detail");}}''',
'android/provider/MediaStore.java': '''package android.provider;import android.net.Uri;public class MediaStore {public static class MediaColumns{public static final String DISPLAY_NAME="name",MIME_TYPE="mime",RELATIVE_PATH="path",IS_PENDING="pending";}public static class Images{public static class Media{public static final Uri EXTERNAL_CONTENT_URI=Uri.parse("images");}}public static class Video{public static class Media{public static final Uri EXTERNAL_CONTENT_URI=Uri.parse("videos");}}}''',
'android/content/Context.java': '''package android.content;public class Context{public final ContentResolver r;public Context(ContentResolver v){r=v;}public ContentResolver getContentResolver(){return r;}}''',
'android/content/ContentResolver.java': '''package android.content;
import android.net.Uri;import java.io.*;import java.util.*;
public class ContentResolver {
 public boolean nullInsert,nullOutput,failWrite,failFlush,failClose,failUpdate,failDelete,closed,pendingDuringWrite,publishAfterClose,deleted;
 public int creates,updates;public final List<ContentValues> inserted=new ArrayList<>();public byte[] copied;
 public Uri insert(Uri uri,ContentValues values){if(nullInsert)return null;creates++;inserted.add(values);return Uri.parse(uri+"/"+creates);}
 public OutputStream openOutputStream(Uri row)throws IOException{return openOutputStream(row,"w");}
 public OutputStream openOutputStream(Uri row,String mode)throws IOException{
  if(nullOutput)return null; ByteArrayOutputStream data=new ByteArrayOutputStream();
  return new OutputStream(){
   public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
   public void write(byte[] b,int o,int l)throws IOException{
    pendingDuringWrite=Integer.valueOf(1).equals(inserted.get(inserted.size()-1).get("pending"));
    if(failWrite)throw new IOException("write fixture");data.write(b,o,l);
   }
   public void flush()throws IOException{if(failFlush)throw new IOException("flush fixture");}
   public void close()throws IOException{closed=true;copied=data.toByteArray();if(failClose)throw new IOException("close fixture");}
  };
 }
 public int update(Uri row,ContentValues v,String selection,String[] args){updates++;publishAfterClose=closed;if(failUpdate)return 0;return 1;}
 public int delete(Uri row,String selection,String[] args){deleted=true;if(failDelete)throw new IllegalStateException("delete fixture");return 1;}
}'''
}
TEST = r'''package com.waenhancer.xposed.features.media;
import android.content.*;import android.os.*;import android.media.*;
import java.io.*;import java.nio.file.*;import java.util.*;
import com.waenhancer.xposed.utils.HookErrors;
public class TestGalleryExport {
 static int count;static boolean legacy;
 static void ck(String name,boolean result){if(!result)throw new AssertionError(name);count++;}
 static byte[] header(String kind){byte[] b=new byte[64]; Arrays.fill(b,(byte)42);
 switch(kind){case "jpg":b[0]=(byte)255;b[1]=(byte)216;b[2]=(byte)255;break;
 case "png":byte[] p={(byte)137,80,78,71,13,10,26,10};System.arraycopy(p,0,b,0,8);break;
 case "webp":put(b,0,"RIFF");put(b,8,"WEBP");break;case "gif":put(b,0,"GIF89a");break;
 default:put(b,4,"ftyp");put(b,8,kind);}
 return b;}
 static void put(byte[] b,int at,String s){System.arraycopy(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,b,at,s.length());}
 static void export(Context c,File f)throws Exception {if(legacy)LegacyGalleryExporter.export(c,f);else GalleryMediaExporter.export(c,f);}
 static ContentResolver success(File dir,String kind,String ext,String mime)throws Exception{
  byte[] data=header(kind);File src=new File(dir,"cache.dat");Files.write(src.toPath(),data);
  ContentResolver r=new ContentResolver();export(new Context(r),src);
  ck("mime "+kind,mime.equals(r.inserted.get(0).get("mime")));
  ck("extension "+kind,((String)r.inserted.get(0).get("name")).endsWith(ext));
  ck("bytes unchanged "+kind,Arrays.equals(data,r.copied));
  ck("hidden until close "+kind,r.pendingDuringWrite && r.publishAfterClose && r.updates==1 && !r.deleted);
  return r;
 }
 static void fail(ContentResolver r,File source,String name)throws Exception{
  boolean failed=false;try{export(new Context(r),source);}catch(Exception expected){failed=true;}
  ck("error not success "+name,failed);
  ck("rollback "+name,r.creates==0||r.deleted);
 }
 public static void main(String[] args)throws Exception{
  legacy=args.length>0&&args[0].equals("legacy");File dir=Files.createTempDirectory("media-test").toFile();Environment.root=dir;
  success(dir,"jpg",".jpg","image/jpeg");success(dir,"png",".png","image/png");success(dir,"webp",".webp","image/webp");success(dir,"gif",".gif","image/gif");
  success(dir,"isom",".mp4","video/mp4");success(dir,"qt  ",".mov","video/quicktime");success(dir,"3gp5",".3gp","video/3gpp");success(dir,"3g2a",".3g2","video/3gpp2");
  File src=new File(dir,"cache.dat");Files.write(src.toPath(),header("jpg"));
  for(String fault:List.of("nullInsert","nullOutput","failWrite","failFlush","failClose","failUpdate")){
   ContentResolver r=new ContentResolver();ContentResolver.class.getField(fault).setBoolean(r,true);fail(r,src,fault);
  }
  ContentResolver rollback=new ContentResolver();rollback.failWrite=true;rollback.failDelete=true;fail(rollback,src,"provider rejects rollback");
  ContentResolver r=new ContentResolver();Context ctx=new Context(r);export(ctx,src);export(ctx,src);
  ck("unique successful names",!r.inserted.get(0).get("name").equals(r.inserted.get(1).get("name")));
  Files.write(src.toPath(),header("avif"));fail(new ContentResolver(),src,"AVIF is not mislabeled as MP4");
  Files.write(src.toPath(),new byte[0]);fail(new ContentResolver(),src,"empty");fail(new ContentResolver(),dir,"directory");
  File absent=new File(dir,"absent");fail(new ContentResolver(),absent,"missing");
  for(long n:new long[]{0,-1,GalleryMediaExporter.MAX_BYTES+1}){
   boolean failed=false;try{GalleryMediaExporter.copy(new ByteArrayInputStream(new byte[1]),new ByteArrayOutputStream(),n);}catch(IOException good){failed=true;}
   ck("size bound "+n,failed);
  }
  for(int size:new int[]{1,2,31,32768,32769,100000}){
   byte[] data=new byte[size];new Random(size).nextBytes(data);ByteArrayOutputStream out=new ByteArrayOutputStream();GalleryMediaExporter.copy(new ByteArrayInputStream(data),out,size);ck("copy chunk "+size,Arrays.equals(data,out.toByteArray()));
  }
  for(int expected:new int[]{9,11}){
   boolean failed=false;try{GalleryMediaExporter.copy(new ByteArrayInputStream(new byte[10]),new ByteArrayOutputStream(),expected);}catch(IOException good){failed=true;}
   ck("length changed "+expected,failed);
  }
  boolean stalled=false;try{GalleryMediaExporter.copy(new InputStream(){public int read(){return 0;}public int read(byte[] b){return 0;}},new ByteArrayOutputStream(),10);}catch(IOException good){stalled=true;}ck("no infinite zero-read loop",stalled);
  Build.VERSION.SDK_INT=28;Files.write(src.toPath(),header("png"));export(ctx,src);
  File pictures=new File(dir,"Pictures/WAEX");File[] outputs=pictures.listFiles();
  ck("legacy final only",outputs!=null&&outputs.length==1&&outputs[0].getName().endsWith(".png"));
  ck("legacy preserved bytes",Arrays.equals(header("png"),Files.readAllBytes(outputs[0].toPath())));
  ck("legacy scan requested",MediaScannerConnection.scans==1);
  MediaScannerConnection.fail=true;export(ctx,src);
  ck("scan exception counted",HookErrors.count("GalleryMediaExporter",IllegalStateException.class)==1);
  ck("scan failure diagnosed once",de.robv.android.xposed.XposedBridge.messages.size()==1);
  ck("private scan detail excluded",de.robv.android.xposed.XposedBridge.messages.stream().noneMatch(s->s.contains("private scan detail")));
  System.out.println("PASS: "+count+" storage/header/copy checks (Android storage doubles, real file IO)");
 }
}'''

def legacy_source(path):
    text = path.read_text(encoding='utf-8'); start=text.index('    private void saveMediaFile(');end=text.index('    private void showToast(',start)
    method=text[start:end].replace('private void saveMediaFile(', 'public static void export(')
    return '''package com.waenhancer.xposed.features.media;
import android.content.*;import android.net.*;import android.os.*;import android.provider.*;
import java.io.*;import java.text.*;import java.util.*;
public class LegacyGalleryExporter {
static final String TAG="test";static class XposedBridge{static void log(String x){}}
static void showToast(Context c,String s){}
'''+method+'}\n'

def main():
    p=argparse.ArgumentParser();p.add_argument('--legacy-hook',type=Path);a=p.parse_args()
    with tempfile.TemporaryDirectory(prefix='waex-export-test-') as t:
        d=Path(t)
        for rel,s in STUBS.items():
            f=d/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s,encoding='utf-8')
        (d/'TestGalleryExport.java').write_text(TEST,encoding='utf-8')
        (d/'LegacyGalleryExporter.java').write_text(legacy_source(a.legacy_hook) if a.legacy_hook else 'package com.waenhancer.xposed.features.media;class LegacyGalleryExporter {static void export(android.content.Context c,java.io.File f){throw new AssertionError("Unused legacy adapter");}}',encoding='utf-8')
        production=ROOT/'app/src/main/java/com/waenhancer/xposed/features/media/GalleryMediaExporter.java'
        errors=ROOT/'app/src/main/java/com/waenhancer/xposed/utils/HookErrors.java'
        subprocess.run(['javac','--release','17','-d',str(d/'classes'),str(production),str(errors),*map(str,d.rglob('*.java'))],check=True,timeout=60)
        print('COMPILE_OK: production and explicit test adapters', flush=True)
        subprocess.run(['java','-cp',str(d/'classes'),'com.waenhancer.xposed.features.media.TestGalleryExport',*(['legacy'] if a.legacy_hook else [])],check=True,timeout=60)
    hook=(ROOT/'app/src/main/java/com/waenhancer/xposed/features/media/DownloadViewOnceHook.java').read_text(encoding='utf-8')
    assert 'GalleryMediaExporter.export(ctx, srcFile);' in hook
    assert '"Saved to Gallery (WAEX)"' not in hook
if __name__=='__main__': main()

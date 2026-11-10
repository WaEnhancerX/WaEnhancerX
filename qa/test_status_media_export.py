#!/usr/bin/env python3
"""Production status export with explicit Android storage doubles and real files.

This verifies storage acknowledgement and rollback, not MediaProvider permissions,
Android UI, WhatsApp file selection, decoder validity, or LSPosed injection.
--baseline-hook adapts the unchanged legacy storage body to the same fixture;
its failure toast is translated into a failure result at the adapter boundary.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess
import tempfile
from test_gallery_export import STUBS as GALLERY_STUBS

ROOT = Path(__file__).resolve().parents[1]
MEDIA = Path('app/src/main/java/com/waenhancer/xposed/features/media')
STUBS = dict(GALLERY_STUBS)
STUBS['android/os/Environment.java'] = STUBS['android/os/Environment.java'].replace(
    'DIRECTORY_MOVIES="Movies",', 'DIRECTORY_MUSIC="Music",DIRECTORY_MOVIES="Movies",').replace(
    'public static File root;', 'public static File root;public static Runnable onDirectory;').replace(
    'return new File(root,d);', 'if(onDirectory!=null)onDirectory.run();return new File(root,d);')
STUBS['android/provider/MediaStore.java'] = STUBS['android/provider/MediaStore.java'].replace(
    'public static class Images',
    'public static class Audio{public static class Media{public static final Uri EXTERNAL_CONTENT_URI=Uri.parse("audio");}}public static class Images')
STUBS['android/content/ContentResolver.java'] = '''package android.content;
import android.net.Uri;import java.io.*;import java.util.*;
public class ContentResolver {
 public String fault="";public int creates,updates,successReports,failureReports; public boolean closed,pendingDuringWrite,publishAfterClose,earlySuccess;
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

TEST = r'''package com.waenhancer.xposed.features.media;
import android.content.*;import android.os.*;import android.media.*;
import java.io.*;import java.nio.file.*;import java.util.*;
public class TestStatusExport {
 static int cases,failed,checks;static boolean legacy;
 interface Case {void run()throws Exception;}
 static void test(String name,Case body){cases++;try{body.run();System.out.println("PASS "+name);}catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e.getClass().getSimpleName()+" "+e.getMessage());}}
 static void ck(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
 static byte[] data(String kind){byte[] b=new byte[100003];new Random(17).nextBytes(b);
  switch(kind){case "jpg":b[0]=(byte)255;b[1]=(byte)216;b[2]=(byte)255;break;
  case "png":put(b,0,"\u0089PNG\r\n\u001a\n");b[0]=(byte)137;break;
  case "gif":put(b,0,"GIF89a");break;case "webp":put(b,0,"RIFF");put(b,8,"WEBP");break;
  case "ogg":put(b,0,"OggS");break;case "mp3":put(b,0,"ID3");break;
  case "aac":b[0]=(byte)255;b[1]=(byte)241;break;
  case "mkv":b[0]=26;b[1]=69;b[2]=(byte)223;b[3]=(byte)163;break;
  default:put(b,4,"ftyp");put(b,8,"isom");}
  return b;
 }
 static void put(byte[] b,int at,String value){byte[] v=value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);System.arraycopy(v,0,b,at,v.length);}
 static File source(File root,String name,String kind)throws Exception{File f=new File(root,name);Files.write(f.toPath(),data(kind));return f;}
 static void export(ContentResolver resolver,File source)throws Exception {
  if(legacy){int failures=resolver.failureReports;LegacyStatusExporter.export(new Context(resolver),source);if(resolver.failureReports>failures)throw new IOException("Legacy hook reported failure");}
  else {StatusMediaExporter.export(new Context(resolver),source);resolver.success();}
 }
 static void rejects(ContentResolver resolver,File source)throws Exception {
  boolean rejected=false;try{export(resolver,source);}catch(Exception expected){rejected=true;}
  ck(rejected,"failure must propagate to the hook");ck(resolver.successReports==0,"failure must not announce success");
  ck(resolver.creates==0||resolver.deleted.size()==1,"owned partial row must be removed");
 }
 public static void main(String[] args)throws Exception {
  legacy=args.length>0&&args[0].equals("legacy");File root=Files.createTempDirectory("status-export-").toFile();Environment.root=root;
  String[][] formats={{"jpg","cache",".jpg","image/jpeg","images","Pictures"},{"png","cache",".png","image/png","images","Pictures"},{"gif","cache",".gif","image/gif","images","Pictures"},{"webp","cache",".webp","image/webp","images","Pictures"},{"mp4","cache",".mp4","video/mp4","videos","Movies"},{"mkv","cache",".mkv","video/x-matroska","videos","Movies"},{"ogg","cache",".ogg","audio/ogg","audio","Music"},{"mp3","cache",".mp3","audio/mpeg","audio","Music"},{"aac","cache",".aac","audio/aac","audio","Music"},{"mp4","cache.m4a",".m4a","audio/mp4","audio","Music"}};
  for(String[] f:formats)test("scoped "+f[0]+" "+f[1],()->{
   Build.VERSION.SDK_INT=34;File src=source(root,f[1],f[0]);ContentResolver r=new ContentResolver();export(r,src);
   ck(r.inserted.size()==1&&r.collections.get(0).equals(f[4]),"collection");ContentValues v=r.inserted.get(0);
   ck(v.get("name").toString().startsWith("WAEX_STATUS_")&&v.get("name").toString().endsWith(f[2]),"name and extension");
   ck(f[3].equals(v.get("mime"))&&(f[5]+"/WAEX").equals(v.get("path")),"mime and directory");
   ck(Arrays.equals(data(f[0]),r.copied),"exact complete bytes");
   ck(r.pendingDuringWrite&&r.publishAfterClose&&r.updates==1,"pending until successful close");
   ck(!r.earlySuccess&&r.successReports==1&&r.deleted.isEmpty(),"single acknowledged success");
  });
  for(String fault:new String[]{"nullInsert","nullOutput","open","write","flush","close","updateZero","updateMany","updateThrow","delete"})test("failure "+fault,()->{
   Build.VERSION.SDK_INT=34;ContentResolver r=new ContentResolver();r.fault=fault;rejects(r,source(root,"fault.jpg","jpg"));
   if(fault.equals("close"))ck(r.updates==0,"failed close must not publish");
  });
  test("rollback owns only failed row",()->{
   Build.VERSION.SDK_INT=34;ContentResolver r=new ContentResolver();File src=source(root,"owned.jpg","jpg");export(r,src);r.fault="write";
   try{export(r,src);}catch(Exception expected){}
   ck(r.deleted.equals(List.of("images/2")),"never delete previous export");ck(r.successReports==1,"failed repeat not reported");
  });
  for(String change:new String[]{"grow","shrink","replace"})test("source "+change,()->{
   Build.VERSION.SDK_INT=34;ContentResolver r=new ContentResolver();File src=source(root,"changing.jpg","jpg");
   r.onOpen=()->{try{if(change.equals("replace")){Files.move(src.toPath(),new File(root,"old.jpg").toPath(),StandardCopyOption.REPLACE_EXISTING);Files.write(src.toPath(),new byte[]{1,2,3});}else if(change.equals("grow"))Files.write(src.toPath(),new byte[]{1},StandardOpenOption.APPEND);else Files.write(src.toPath(),new byte[]{1});}catch(IOException e){throw new UncheckedIOException(e);}};
   if(change.equals("replace")){export(r,src);ck(Arrays.equals(data("jpg"),r.copied),"same opened descriptor");}
   else rejects(r,src);
  });
  test("oversize before destination",()->{
   File f=new File(root,"huge.mp4");try(RandomAccessFile rw=new RandomAccessFile(f,"rw")){rw.setLength(GalleryMediaExporter.MAX_BYTES+1);}
   ContentResolver r=new ContentResolver();r.fault="nullOutput";rejects(r,f);ck(r.creates==0,"bound before creating destination");f.delete();
  });
  test("empty and missing",()->{ContentResolver a=new ContentResolver();File empty=new File(root,"empty.mp4");Files.write(empty.toPath(),new byte[0]);rejects(a,empty);rejects(new ContentResolver(),new File(root,"absent"));});
  test("content identity is stable and never exposes a source path",()->{File first=source(root,"identity-a.jpg","jpg"),second=source(root,"identity-b.jpg","jpg");String a=StatusDownloadIdentity.forFile(first),b=StatusDownloadIdentity.forFile(second);ck(a!=null&&a.equals(b)&&a.length()==64,"same status bytes share identity");Files.write(second.toPath(),new byte[]{1,2,3});String changed=StatusDownloadIdentity.forFile(second);ck(changed!=null&&!a.equals(changed)&&!changed.contains("identity"),"changed bytes get opaque identity");});
  test("legacy keeps repeated exports and scans",()->{
   Build.VERSION.SDK_INT=28;Environment.root=new File(root,"legacy");ContentResolver r=new ContentResolver();File src=source(root,"legacy.opus","ogg");export(r,src);export(r,src);
   File[] files=new File(Environment.root,"Music/WAEX").listFiles();ck(files!=null&&files.length==2,"same-second exports must not overwrite");
   for(File f:files)ck(f.getName().endsWith(".ogg")&&Arrays.equals(data("ogg"),Files.readAllBytes(f.toPath())),"only complete final files");
   ck(MediaScannerConnection.scans==2,"scan both published exports");
  });
  test("legacy directory collision preserved",()->{
   Build.VERSION.SDK_INT=28;Environment.root=new File(root,"blocked");File blocking=new File(Environment.root,"Pictures/WAEX");blocking.getParentFile().mkdirs();Files.writeString(blocking.toPath(),"existing user file");
   boolean failed=false;try{export(new ContentResolver(),source(root,"x.jpg","jpg"));}catch(Exception expected){failed=true;}
   ck(failed&&Files.readString(blocking.toPath()).equals("existing user file"),"preserve foreign path and report failure");
  });
  for(String change:new String[]{"grow","shrink"})test("legacy partial cleanup "+change,()->{
   Build.VERSION.SDK_INT=28;Environment.root=new File(root,"legacy-"+change);File folder=new File(Environment.root,"Pictures/WAEX");folder.mkdirs();
   File foreign=new File(folder,"previous-user-file.part");Files.writeString(foreign.toPath(),"keep previous file");
   File src=source(root,"legacy-changing.jpg","jpg");ContentResolver r=new ContentResolver();int scans=MediaScannerConnection.scans;
   Environment.onDirectory=()->{try{if(change.equals("grow"))Files.write(src.toPath(),new byte[]{1},StandardOpenOption.APPEND);else Files.write(src.toPath(),new byte[]{1});}catch(IOException e){throw new UncheckedIOException(e);}};
   try{rejects(r,src);}finally{Environment.onDirectory=null;}
   File[] remaining=folder.listFiles();ck(remaining!=null&&remaining.length==1&&remaining[0].equals(foreign),"remove only this export's incomplete temporary file");
   ck(Files.readString(foreign.toPath()).equals("keep previous file")&&MediaScannerConnection.scans==scans,"foreign file retained and incomplete file not scanned");
  });
  System.out.println("RESULT cases="+cases+" failed="+failed+" checks="+checks);if(failed>0)throw new AssertionError(failed+" cases failed");
 }
}'''

def legacy_source(path):
    source = path.read_text()
    start = source.index('    private void saveMediaFile(')
    end = source.index('    private void copyToClipboard(', start)
    body = source[start:end].replace('private void saveMediaFile(', 'public static void export(').replace('private MediaFormat detectMediaFormat(', 'private static MediaFormat detectMediaFormat(')
    return '''package com.waenhancer.xposed.features.media;
import android.content.*;import android.net.*;import android.os.*;import android.provider.*;
import java.io.*;import java.text.*;import java.util.*;
class LegacyStatusExporter {
 static final String TAG="fixture";static class XposedBridge {static void log(String v){}}
 static class UiText {static String get(int id,String fallback){return fallback;}}
 static class R {static class string {static final int waex_ui_status_saved_to_gallery_waex_9f345669=1,waex_ui_saved_to_b95e47d2=2,waex_ui_error_saving_status_media_de3da11e=3;}}
 static void showToast(Context c,String message){if(message.startsWith("Status saved")||message.startsWith("Saved to:"))c.getContentResolver().success();else if(message.startsWith("Error saving status media:"))c.getContentResolver().failureReports++;}
''' + body + '}\n'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--baseline-hook', type=Path)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    source_root = args.source_root.resolve()
    out = args.out.resolve() if args.out else None
    if out: out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='waex-status-export-') as directory:
        root = Path(directory)
        for name, source in STUBS.items():
            path = root/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(source)
        (root/'TestStatusExport.java').write_text(TEST)
        (root/'LegacyStatusExporter.java').write_text(legacy_source(args.baseline_hook) if args.baseline_hook else 'package com.waenhancer.xposed.features.media;class LegacyStatusExporter {static void export(android.content.Context c,java.io.File f){throw new AssertionError("Unused adapter");}}')
        production = [source_root/MEDIA/'StatusMediaExporter.java', source_root/MEDIA/'GalleryMediaExporter.java', source_root/MEDIA/'StatusDownloadIdentity.java']
        compile_result = subprocess.run(['javac', '--release', '17', '-d', str(root/'classes'), *map(str, production), *map(str, root.rglob('*.java'))], capture_output=True, text=True, timeout=60)
        if out: (out/'compile.log').write_text(compile_result.stdout+compile_result.stderr)
        if compile_result.returncode:
            print(compile_result.stdout+compile_result.stderr); raise SystemExit(compile_result.returncode)
        print('COMPILE_OK production exporter and explicit storage doubles')
        result = subprocess.run(['java', '-cp', str(root/'classes'), 'com.waenhancer.xposed.features.media.TestStatusExport', *(['legacy'] if args.baseline_hook else [])], capture_output=True, text=True, timeout=60)
        print(result.stdout+result.stderr)
        if out:
            (out/'execution.log').write_text(result.stdout+result.stderr)
            (out/'result.json').write_text(json.dumps({'scope':'production Java plus Android storage doubles and real file IO','baseline_hook':str(args.baseline_hook) if args.baseline_hook else None,'baseline_hook_sha256':hashlib.sha256(args.baseline_hook.read_bytes()).hexdigest() if args.baseline_hook else None,'returncode':result.returncode,'production_sha256':{str(p.relative_to(source_root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in production},'fixture_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},indent=2)+'\n')
        if result.returncode: raise SystemExit(result.returncode)
    hook = (source_root/MEDIA/'StatusDownloadHook.java').read_text()
    start = hook.index('    private void saveMediaFile('); end = hook.index('    private void copyToClipboard(',start)
    body = hook[start:end]
    assert body.index('StatusMediaExporter.export(ctx, srcFile);') < body.index('waex_ui_status_saved_to_gallery_waex_9f345669')
    assert 'FileOutputStream' not in body and 'openOutputStream' not in body
    assert 'getMessage()' not in body
    assert 'MenuItem item = menu.findItem(MENU_ID_STATUS_DOWNLOAD);' in hook
    assert 'configureStatusMenuItem(item, sourceObject);' in hook
    assert 'item.setTitle(isTextStatus' in hook
    assert 'hookMenuRefreshMethods(statusContactFragClass);' in hook

if __name__ == '__main__': main()

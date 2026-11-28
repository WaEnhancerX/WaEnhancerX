#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile,argparse,shutil
ROOT=Path(__file__).resolve().parents[1]
FILES={'Call.kt': 'package okhttp3\ninterface Call{fun cancel();fun isCanceled():Boolean}\nclass FakeCall:Call{var cancelled=false;override fun cancel(){cancelled=true};override fun isCanceled()=cancelled}', 'Toast.kt': 'package android.widget\nclass Toast {fun show(){shows++};companion object{const val LENGTH_LONG=1;var shows=0;fun makeText(c:checks.Context,t:String,d:Int)=Toast()}}', 'Downloader.kt': 'package com.waenhancer.update\nimport okhttp3.*\nimport java.io.File\nobject UpdateDownloader {\n interface DownloadCallback{fun onProgress(progress:Int,currentBytes:Long,totalBytes:Long);fun onSuccess(apkFile:File);fun onFailure(error:Exception)}\n lateinit var callback:DownloadCallback;var installs=0;var rootInstalls=0;lateinit var call:FakeCall\n fun downloadApk(c:checks.Context,u:String,v:String,b:DownloadCallback,h:String?,s:Long):Call {callback=b;call=FakeCall();return call}\n fun installApk(c:checks.Context,f:File,v:String){check(f.exists());installs++}\n fun installApkWithRoot(c:checks.Context,f:File,v:String){check(f.exists());rootInstalls++}\n}'}
PROGRAM = ('package checks\n'
 'import java.io.File\n'
 'import java.util.concurrent.atomic.AtomicReference\n'
 'import java.util.concurrent.atomic.AtomicBoolean\n'
 'import com.waenhancer.update.UpdateDownloadSession\n'
 'import com.waenhancer.update.UpdateDownloader\n'
 'import android.widget.Toast\n'
 'class Context\n'
 'class Handler {\n'
 ' val queue=mutableListOf<Runnable>();var accept=true;var posts=0\n'
 ' @Synchronized fun post(r:Runnable):Boolean{posts++;if(!accept)return false;queue.add(r);return '
 'true}\n'
 ' fun post(f:()->Unit)=post(Runnable(f))\n'
 ' @Synchronized fun removeCallbacks(r:Runnable){queue.removeAll{it===r}}\n'
 ' fun flush(){while(queue.isNotEmpty()){val r=synchronized(this){queue.removeAt(0)};r.run()}}\n'
 '}\n'
 'class Disposal(val close:()->Unit)\n'
 'var disposal:Disposal?=null\n'
 'fun onDispose(close:()->Unit)=Disposal(close)\n'
 'fun DisposableEffect(session:UpdateDownloadSession,block:()->Disposal){disposal=block()}\n'
 'val context=Context();val mainHandler=Handler();var session=UpdateDownloadSession();var '
 'useRoot=false\n'
 'var downloadProgress=0;var downloadedBytes=0L;var downloadTotalBytes=0L;var dismisses=0\n'
 'val dismiss:()->Unit={dismisses++};val url="https://example.invalid/update.apk";val '
 'version="2.0.3";val expectedSha256:String?=null;val expectedSize=0L\n'
 'fun setup(){val session=session;val useRoot=useRoot; EFFECT }\n'
 'fun cancelFromButton() CANCEL\n'
 'fun '
 'reset(){mainHandler.queue.clear();mainHandler.posts=0;mainHandler.accept=true;disposal=null;session=UpdateDownloadSession();UpdateDownloader.installs=0;UpdateDownloader.rootInstalls=0;Toast.shows=0;downloadProgress=0;dismisses=0}\n'
 'fun file()=File.createTempFile("waex-test-", '
 '".apk").also{it.writeText("fixture");it.deleteOnExit()}\n'
 'fun main(){\n'
 ' var checks=0\n'
 ' for(root in listOf(false,true)){\n'
 '  reset();useRoot=root;setup();val '
 'f=file();UpdateDownloader.callback.onSuccess(f);cancelFromButton();disposal!!.close();mainHandler.flush();check(UpdateDownloader.installs+UpdateDownloader.rootInstalls==0);check(!f.exists());checks++\n'
 '  reset();useRoot=root;setup();val '
 'good=file();UpdateDownloader.callback.onSuccess(good);mainHandler.flush();check(UpdateDownloader.installs+UpdateDownloader.rootInstalls==1);check(good.exists());disposal!!.close();UpdateDownloader.callback.onSuccess(good);mainHandler.flush();check(good.exists());check(UpdateDownloader.installs+UpdateDownloader.rootInstalls==1);checks++\n'
 ' }\n'
 ' '
 'reset();setup();UpdateDownloader.callback.onFailure(java.io.IOException("fixture"));cancelFromButton();mainHandler.flush();check(Toast.shows==0);checks++\n'
 ' '
 'reset();setup();UpdateDownloader.callback.onProgress(20,20,100);disposal!!.close();mainHandler.flush();check(downloadProgress==0);checks++\n'
 ' '
 'reset();setup();repeat(10000){UpdateDownloader.callback.onProgress(it%100,it.toLong(),10000)};check(mainHandler.queue.size==1&&mainHandler.posts==1);mainHandler.flush();check(downloadedBytes==9999L);checks++\n'
 ' reset();setup();val '
 'old=UpdateDownloader.callback;disposal!!.close();session=UpdateDownloadSession();setup();old.onSuccess(file());mainHandler.flush();check(UpdateDownloader.installs+UpdateDownloader.rootInstalls==0&&session.isActive());checks++\n'
 ' reset();setup();mainHandler.accept=false;val '
 'f=file();UpdateDownloader.callback.onSuccess(f);check(!f.exists()&&session.isCancelled());checks++\n'
 ' val s=UpdateDownloadSession();var '
 'cancels=0;s.cancel();s.attach{cancels++};s.cancel();check(cancels==1&&!s.claimTerminal());checks++\n'
 ' repeat(100){val x=UpdateDownloadSession();val '
 'successes=java.util.concurrent.atomic.AtomicInteger();val '
 'threads=List(8){Thread{if(x.claimTerminal())successes.incrementAndGet()}};threads.forEach{it.start()};threads.forEach{it.join()};check(successes.get()==1)};checks++\n'
 ' println("PASS $checks session/callback scenarios; 100 concurrent terminal races; 10000 progress '
 'updates -> 1 queued/applied latest. No actual installer/root/Compose screen.")\n'
 '}\n')

def block(s,m):
 a=s.index(m);b=s.index('{',a);d=0
 for i in range(b,len(s)):
  d+=(s[i]=='{')-(s[i]=='}')
  if not d:return s[a:i+1]
 raise ValueError(m)
def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=ROOT);a=p.parse_args();j=a.root/'app/src/main/java/com/waenhancer';s=(j/'ui/components/UpdateDownloadSheet.kt').read_text()
 effect=block(s,'DisposableEffect(session)')
 assert s.count('WaexSheetTextButton(')==1, 'Expected the unique production cancellation button'
 button=s[s.index('WaexSheetTextButton('):]
 cancel=block(button,'onClick = ')[len('onClick = '):]
 import re
 ids=sorted(set(re.findall(r'R\.string\.([A-Za-z_][A-Za-z_0-9]*)',effect+cancel)))
 r_stub='object R {object string {'+ ''.join(f'const val {name} = {0x7f060100+i};' for i,name in enumerate(ids))+'}}\n'
 ui_stub='object UiText {fun get(id:Int, fallback:String, vararg args:Any?):String = if(args.isEmpty()) fallback else String.format(java.util.Locale.US, fallback, *args)}\n'
 files=dict(FILES);files['Session.kt']=(j/'update/UpdateDownloadSession.kt').read_text()
 # Network-error presentation is outside this cancellation fixture. A stale
 # callback reaching it is a test failure, never a permissive replacement UI.
 files['NetworkErrorBoundary.kt']='''package checks
object NetworkErrorCenter {
 fun isNetworkError(error:Throwable):Boolean=throw AssertionError("Cancelled failure reached network presentation")
 fun show(error:Throwable):Nothing=throw AssertionError("Cancelled failure reached network presentation")
}
'''
 files['Checks.kt']=PROGRAM.replace('EFFECT',effect).replace('CANCEL',cancel).replace('class Context\n',r_stub+ui_stub+'class Context\n')
 with tempfile.TemporaryDirectory(prefix='waex-session-') as d:
  out=Path(d)
  for n,v in files.items():(out/n).write_text(v)
  subprocess.run([shutil.which('kotlinc') or 'kotlinc','-nowarn','-language-version','2.0',*map(str,out.glob('*.kt')),'-include-runtime','-d',str(out/'test.jar')],check=True,timeout=90)
  print('COMPILE_OK',flush=True);subprocess.run(['java','-jar',str(out/'test.jar')],check=True,timeout=30)
 for n in ['ui/navigation/Navigation.kt','ui/screens/settings/ReleaseDetailsScreen.kt','ui/screens/settings/ChangelogScreen.kt']:
  code=(j/n).read_text();assert 'expectedSha256 =' in code and 'expectedSize =' in code,n
if __name__=='__main__':main()

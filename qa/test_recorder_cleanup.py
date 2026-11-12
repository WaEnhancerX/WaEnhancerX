"""Execute unmodified production recorder cleanup bodies with explicit media/IPC doubles."""
from pathlib import Path
import argparse,hashlib,json,subprocess,re
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--out',type=Path,default=Path(__import__('tempfile').mkdtemp(prefix='waex-recorder-')));a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
s=a.root/'app/src/main/java/com/waenhancer/xposed/features/media/CallRecordingHook.java';text=s.read_text()
def method(marker):
 start=text.index(marker);opening=text.index('{',start);depth=0
 for i in range(opening,len(text)):
  depth+=(text[i]=='{')-(text[i]=='}')
  if not depth:return text[start:i+1]
 raise ValueError(marker)
parts=[method(x) for x in ['private synchronized void stopRecorder(', 'private void releaseRecorder(', 'private void closeDestination(']]
program=r'''
import java.util.concurrent.atomic.AtomicBoolean;
public class RecorderCleanupProbe {
 static final String TAG="[probe]", BRIDGE_URI="fixture";
 final AtomicBoolean recording=new AtomicBoolean(false);
 MediaRecorder recorder; ParcelFileDescriptor destination;
 String outputPath,outputName,outputCallId; Context context=new Context();
 static class MediaRecorder {
  boolean throwsOnStop,throwsOnRelease;int stops,releases,resets;
  void stop(){stops++;if(throwsOnStop)throw new RuntimeException("controlled stop failure");}
  void reset(){resets++;}void release(){releases++;if(throwsOnRelease)throw new IllegalStateException("release failed");}
 }
 static class ParcelFileDescriptor {int closes;void close(){closes++;}}
 static class Bundle {java.util.Map<String,Object> values=new java.util.HashMap<>();
  void putString(String k,String v){values.put(k,v);} void putLong(String k,long v){values.put(k,v);}
  void putBoolean(String k,boolean v){values.put(k,v);}}
 static class Context {int deletes,finishes;Boolean success;Context getContentResolver(){return this;}
  Object call(String u,String op,String arg,Bundle b){
   if("delete_call_recording".equals(op)){deletes++;return null;}
   if("finish_call_recording".equals(op)){
    if(!"fixture-call".equals(b.values.get("call_id")) || !(b.values.get("ended_at") instanceof Long))
      throw new AssertionError("Invalid recording completion metadata");
    finishes++;success=(Boolean)b.values.get("successful");return null;
   }
   throw new AssertionError("Unexpected recorder IPC: "+op);
  }}
 static class XposedBridge {static void log(String s){}}
 BODY
 static RecorderCleanupProbe fixture(boolean stopFails){
  RecorderCleanupProbe p=new RecorderCleanupProbe();p.recording.set(true);p.recorder=new MediaRecorder();p.recorder.throwsOnStop=stopFails;p.destination=new ParcelFileDescriptor();p.outputPath="fixture/call.m4a";p.outputName="call.m4a";p.outputCallId="fixture-call";return p;
 }
 public static void main(String[] args){
  for(boolean failure:new boolean[]{false,true}){
   RecorderCleanupProbe p=fixture(failure);MediaRecorder r=p.recorder;ParcelFileDescriptor fd=p.destination;
   p.stopRecorder("probe");
   System.out.println("OBSERVATION|stop_throws="+failure+"|stop_calls="+r.stops+"|release_calls="+r.releases+"|descriptor_closes="+fd.closes+"|invalid_file_deletes="+p.context.deletes+"|recorder_reference_cleared="+(p.recorder==null));
   if(r.stops!=1||fd.closes!=1||p.recording.get())throw new AssertionError("bad fixture");
   if(r.releases!=1)throw new AssertionError("release omitted on stop failure");
   if(!failure && r.releases!=1)throw new AssertionError("success control failed");
   if(p.context.deletes!=(failure?1:0))throw new AssertionError("unexpected deletion");
   if(p.context.finishes!=1||!Boolean.valueOf(!failure).equals(p.context.success))
    throw new AssertionError("recording completion notification omitted or incorrect");
  }
  for(int i=0;i<2000;i++){
   RecorderCleanupProbe x=fixture(i%2==0);MediaRecorder rr=x.recorder;rr.throwsOnRelease=i%3==0;ParcelFileDescriptor dd=x.destination;
   x.stopRecorder("cycle");x.stopRecorder("duplicate");
   if(rr.releases!=1||rr.stops!=1||dd.closes!=1||x.recorder!=null||x.destination!=null||x.context.finishes!=1)throw new AssertionError("cleanup cycle "+i);
  }
  RecorderCleanupProbe direct=fixture(false);MediaRecorder r=direct.recorder;direct.releaseRecorder(false);if(r.releases!=1||r.resets!=1)throw new AssertionError("release helper control failed");
  RecorderCleanupProbe inactive=fixture(false);r=inactive.recorder;inactive.recording.set(false);inactive.stopRecorder("inactive");if(r.stops!=0||r.releases!=0)throw new AssertionError("inactive control failed");
  System.out.println("COMPILE_OK; PASS: cleanup, once-only completion metadata, stop/release failure, success/helper/inactive controls and 2000 repeated cycles. No microphone, Android media server, root or real recording.");
 }
}
'''.replace('BODY','\n'.join(parts))
(a.out/'RecorderCleanupProbe.java').write_text(program)
with (a.out/'compile.log').open('w') as f:r=subprocess.run(['javac','--release','17','-d',str(a.out),str(a.out/'RecorderCleanupProbe.java')],stdout=f,stderr=subprocess.STDOUT,timeout=30)
r.check_returncode()
print("COMPILE_OK", flush=True)
with (a.out/'behavior.log').open('w') as f:r=subprocess.run(['java','-cp',str(a.out),'RecorderCleanupProbe'],stdout=f,stderr=subprocess.STDOUT,timeout=30)
print((a.out/'behavior.log').read_text());r.check_returncode()
(a.out/'source-identity.json').write_text(json.dumps({'path':str(s.relative_to(a.root)),'sha256':hashlib.sha256(s.read_bytes()).hexdigest(),'extracted_methods':['stopRecorder','releaseRecorder','closeDestination'],'limitations':'Exact method bodies; field environment, MediaRecorder, Context/IPC and file descriptor are explicit doubles; no Android cleanup or microphone observation.'},indent=2))

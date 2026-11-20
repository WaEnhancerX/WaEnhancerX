"""Build permission-free DexKit comparison APKs from verified cached dependencies."""
from pathlib import Path
import argparse, datetime, hashlib, json, os, subprocess, sys, time, uuid, zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--variant', choices=['baseline','candidate'], required=True)
parser.add_argument('--jdk-root',type=Path,required=True)
parser.add_argument('--sdk-root',type=Path,required=True)
parser.add_argument('--dependencies',type=Path,required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--signing-dir',type=Path,help='Optional private development key directory to reuse')
args=parser.parse_args()

ROOT=Path(__file__).resolve().parent
JDK=args.jdk_root.resolve()/'bin'
EXE='.exe' if os.name=='nt' else ''
SDK=args.sdk_root.resolve()
BT=SDK/'build-tools/36.0.0'; ANDROID=SDK/'platforms/android-36/android.jar'
DEPS=args.dependencies.resolve()
IDENTITIES={
    'dexkit-2.2.0.aar':'cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6',
    'dexkit-2.3.0.aar':'8df532c4126b58490667f71127dc57aef62ccf3578d1d17330f2a1cb8836218e',
    'dexkit-2.2.0.pom':'01d99eec00d290bc2ab740bbda67c52668dc041f15a51276272894a1b0eb132e',
    'dexkit-2.3.0.pom':'3cd1c82d60f25654ae54810b29bab93c00c7a2c2e6d2126522aebd312cea7c07',
    'flatbuffers-java-23.5.26.jar':'8d10cac2ea9878896077ba437d76fdb1b9a07f55a863c560bb8a024b04103f8b',
    'kotlin-stdlib-1.5.0.jar':'52283996fe4067cd7330288b96ae67ecd463614dc741172c54d9d349ab6a9cd7',
    'annotations-13.0.jar':'ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478'}
OUT=args.out.resolve()
OUT.mkdir(parents=True,exist_ok=False)
report=dict(status='BUILDING',output=str(OUT),variant=args.variant,steps=[],artifacts=[],inputs=[])

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def save():(OUT/'BUILD.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
def run(name,args,env=None):
    t=time.monotonic();p=OUT/('%02d-%s.log'%(len(report['steps'])+1,name)); row=dict(name=name,argv=[str(x) for x in args],log=str(p),status='RUNNING');report['steps'].append(row);save()
    try:
        r=subprocess.run(row['argv'],capture_output=True,timeout=60,env=env)
        p.write_bytes(r.stdout+r.stderr);row.update(status='PASS' if r.returncode==0 else 'FAIL',exit_code=r.returncode)
        if r.returncode:raise RuntimeError(name+' failed: '+str(p))
        return r.stdout.decode(errors='replace')
    except subprocess.TimeoutExpired as error:
        p.write_bytes((error.stdout or b'')+(error.stderr or b''));row.update(status='BLOCKED',timeout_s=60);raise
    finally:
        row['duration_s']=round(time.monotonic()-t,3)
        if p.exists():row['sha256']=sha(p)
        save();print(name+': '+row['status'],flush=True)

try:
    for name,expected in IDENTITIES.items():
        p=DEPS/name;assert p.is_file() and sha(p)==expected,name
        report['inputs'].append(dict(path=str(p),sha256=expected,bytes=p.stat().st_size))
    for p in [ROOT/'MainActivity.java',Path(__file__).resolve(),ANDROID,JDK/('javac'+EXE),JDK/('java'+EXE),BT/'lib/d8.jar',BT/('aapt2'+EXE),BT/'lib/apksigner.jar']:
        assert p.is_file();report['inputs'].append(dict(path=str(p),sha256=sha(p),bytes=p.stat().st_size))
    run('java-version',[JDK/('java'+EXE),'-version']);run('aapt2-version',[BT/('aapt2'+EXE),'version'])
    key=(args.signing_dir.resolve() if args.signing_dir else OUT/'isolated-signing')/'dexkit-lab-debug.jks';key.parent.mkdir(parents=True,exist_ok=True)
    env=os.environ.copy();env['WAEX_DK_LAB_PASSWORD']='android'
    if not key.exists():run('create-own-debug-key',[JDK/('keytool'+EXE),'-genkeypair','-keystore',key,'-storetype','JKS','-alias','waex-dexkit-lab','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Private WAEX DexKit Laboratory','-storepass:env','WAEX_DK_LAB_PASSWORD','-keypass:env','WAEX_DK_LAB_PASSWORD'],env=env)
    common=[DEPS/x for x in ('flatbuffers-java-23.5.26.jar','kotlin-stdlib-1.5.0.jar','annotations-13.0.jar')]
    fixture_hashes=[]
    for version,label in [('2.2.0','baseline'),('2.3.0','candidate')]:
        if label != args.variant: continue
        pkg='org.thekillermax.waexlab.dexkit'+label;work=OUT/label;work.mkdir()
        src=work/'src';classes=work/'classes';dex=work/'dex'
        for p in (src,classes,dex):p.mkdir()
        with zipfile.ZipFile(DEPS/('dexkit-'+version+'.aar')) as z:
            assert z.testzip() is None
            lib=z.read('jni/x86_64/libdexkit.so');aar_classes=work/'dexkit-classes.jar';aar_classes.write_bytes(z.read('classes.jar'))
        build_config=src/'BuildConfig.java'
        build_config.write_text('package org.thekillermax.waexlab.dexkitlab;\npublic final class BuildConfig { public static final String APPLICATION_ID="'+pkg+'", DEXKIT_VERSION="'+version+'", AAR_SHA256="'+IDENTITIES['dexkit-'+version+'.aar']+'"; }\n',encoding='utf-8')
        fixtures=[]
        for c in range(16):
            p=src/('C%02d.java'%c)
            text='package org.thekillermax.waexlab.fixture;\npublic final class C%02d {\n'%c
            text+=''.join(' public static String m%02d(){ return "WAEX_DK_C%02d_M%02d"; }\n'%(m,c,m) for m in range(16))+'}\n'
            p.write_text(text,encoding='utf-8');fixtures.append(p)
        fixture_hashes=[sha(p) for p in fixtures]
        cp=os.pathsep.join(str(p) for p in [ANDROID,aar_classes,*common])
        run(label+'-javac',[JDK/('javac'+EXE),'-source','17','-target','17','-encoding','UTF-8','-classpath',cp,'-d',classes,ROOT/'MainActivity.java',build_config,*fixtures])
        ownjar=work/'own-classes.jar'
        with zipfile.ZipFile(ownjar,'w',zipfile.ZIP_DEFLATED) as z:
            for p in sorted(classes.rglob('*.class')):z.write(p,p.relative_to(classes).as_posix())
        run(label+'-d8',[JDK/('java'+EXE),'-Xmx768m','-cp',BT/'lib/d8.jar','com.android.tools.r8.D8','--debug','--min-api','24','--lib',ANDROID,'--output',dex,ownjar,aar_classes,*common])
        manifest=work/'AndroidManifest.xml'
        manifest.write_text('<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'+pkg+'" android:versionCode="1" android:versionName="'+version+'-lab"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36"/><application android:label="WAEX DexKit '+version+' Lab" android:debuggable="true" android:allowBackup="false" android:extractNativeLibs="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"><activity android:name="org.thekillermax.waexlab.dexkitlab.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>',encoding='utf-8')
        resources=work/'resources.apk';run(label+'-resources',[BT/('aapt2'+EXE),'link','-I',ANDROID,'--manifest',manifest,'-o',resources])
        unsigned=work/'unsigned.apk'
        with zipfile.ZipFile(resources) as a,zipfile.ZipFile(unsigned,'w') as z:
            for item in a.infolist():z.writestr(item,a.read(item.filename))
            for p in sorted(dex.glob('classes*.dex')):z.write(p,p.name,compress_type=zipfile.ZIP_STORED)
            z.writestr('lib/x86_64/libdexkit.so',lib,compress_type=zipfile.ZIP_DEFLATED)
            z.writestr('assets/experiment.json',json.dumps(dict(permission_free=True,synthetic_only=True,expected_marker_methods=256,fixture_hashes=fixture_hashes,harness_sha256=sha(ROOT/'MainActivity.java'),version=version,aar_sha256=IDENTITIES['dexkit-'+version+'.aar'])),compress_type=zipfile.ZIP_DEFLATED)
            z.write(DEPS/('dexkit-'+version+'.pom'),'assets/dexkit-pom.xml',compress_type=zipfile.ZIP_DEFLATED)
        aligned=work/'aligned.apk';run(label+'-align',[BT/('zipalign'+EXE),'-f','4',unsigned,aligned])
        apk=work/('dexkit-'+label+'-lab.apk')
        run(label+'-sign',[JDK/('java'+EXE),'-jar',BT/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','waex-dexkit-lab','--ks-pass','env:WAEX_DK_LAB_PASSWORD','--key-pass','env:WAEX_DK_LAB_PASSWORD','--out',apk,aligned],env=env)
        run(label+'-signature',[JDK/('java'+EXE),'-jar',BT/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
        badging=run(label+'-badging',[BT/('aapt2'+EXE),'dump','badging',apk]);assert "name='"+pkg+"'" in badging
        permissions=run(label+'-permissions',[BT/('aapt2'+EXE),'dump','permissions',apk]);assert 'uses-permission' not in permissions
        report['artifacts'].append(dict(version=version,package=pkg,path=str(apk),bytes=apk.stat().st_size,sha256=sha(apk),native_sha256=hashlib.sha256(lib).hexdigest(),harness_sha256=sha(ROOT/'MainActivity.java'),fixture_hashes=fixture_hashes,permissions=[]));save()
    report['status']='PASS'
except BaseException as failure:
    report.update(status='FAIL',failure=dict(type=type(failure).__name__,message=str(failure)))
finally:
    save();print(json.dumps(dict(status=report['status'],report=str(OUT/'BUILD.json'),artifacts=report['artifacts'],failure=report.get('failure'))))
    if report['status']!='PASS':raise SystemExit(1)

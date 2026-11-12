#!/usr/bin/env python3
"""Compile and execute unchanged source oracles against focused production mutations.
All mutations must compile and complete the suite. Infrastructure errors are INVALID.
"""
from pathlib import Path
import argparse,hashlib,json,os,re,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[1]
PKG='app/src/main/java/com/waenhancer/xposed/'
MEDIA=PKG+'features/media/MediaQualityBypassHook.java'
SELECTOR=PKG+'features/media/MediaQualitySelectorContract.java'
PROCESS=PKG+'features/media/ProcessImageQualityContract.java'
VIEW=PKG+'features/privacy/AntiViewOnceHook.java'
OWNERSHIP=PKG+'features/privacy/ViewOnceMessageContract.java'
GUARD=PKG+'core/devkit/HookReturnTypeGuard.java'
REGISTRY=PKG+'core/FeatureRegistry.java'
FIXTURE='qa/fixtures/media_hooks/MediaProcessFieldContractTest.java'
MUTANTS=[
 ("reject_verified_jid_subtype",OWNERSHIP,'else if (jidSubtype(field.getType()) && chat == null) chat = field;','else if (jidName(field.getType(), "Jid") && chat == null) chat = field;',"viewonce"),
 ("ignore_unverified_host",MEDIA,"if (!MediaHostFingerprint.accepts(context))", "if (false)","status"),
 ('outgoing_instead_of_incoming',OWNERSHIP,'&& !own.getBoolean(key)','&& own.getBoolean(key)','viewonce'),
 ('ignore_message_ownership',VIEW,'if (messages.isIncoming(param.thisObject))','if (true)','viewonce'),
 ('ambiguous_state_interface',OWNERSHIP,'if (setter != null && !setter.equals(method)) return null;','/* unsafe ambiguous state */','viewonce'),
 ('duplicate_state_registration',VIEW,'if (installed.contains(target)) continue;','/* register duplicates */','viewonce'),
 ('ambiguous_direct_setters',VIEW,'if (candidates.size() == 1) targets.add(candidates.iterator().next());','targets.addAll(candidates);','viewonce'),
 ('overwrite_image_sentinels',MEDIA,'if (quality >= 1 && quality <= 100) param.args[0] = 100;','param.args[0] = 100;','media'),
 ('mask_codec_error',MEDIA,'if (!isVideo60FpsEnabled() || param.hasThrowable()\n                            || !(param.getResult() instanceof Integer)) return;','if(isVideo60FpsEnabled()){param.setResult(60);return;}\n                    if (!(param.getResult() instanceof Integer)) return;','media'),
 ('accept_ambiguous_media_method',MEDIA,'if (unique != null && !unique.equals(candidate)) return null;','/* choose last */','media'),
 ('ignore_live_flag_preferences',MEDIA,'if (isImageQualityMaxEnabled() || isVideo60FpsEnabled()) param.setResult(true);','param.setResult(true);','media'),
 ('modify_unrelated_flag',MEDIA,'|| !MediaQualitySelectorContract.FLAG.equals(param.args[1])','/* ignore flag identity */','media'),
 ('ignore_raw_reader_opcodes',SELECTOR,'!MERGED_FLAG_OPS.equals(data.getOpCodes())','false','media'),
 ('omit_media_unavailable',MEDIA,'throw new IllegalStateException("Media quality contract unavailable");','/* omit diagnostic */','status'),
 ('omit_viewonce_unavailable',VIEW,'throw new IllegalStateException("View-once contract unavailable");','/* omit diagnostic */','status'),
 ('reject_working_partial_media',MEDIA,'installedMethods.isEmpty() && installedConstructors.isEmpty()','installedMethods.size() < 2 && installedConstructors.isEmpty()','status'),
 ('reject_working_partial_viewonce',VIEW,'if (installed.isEmpty() && (isEnabled("anti_view_once", false) || isEnabled("viewonce", false))) {','if (installed.size() < 2 && (isEnabled("anti_view_once", false) || isEnabled("viewonce", false))) {','status'),
 ('ignore_custom_alternate_quality',MEDIA,'if (!Integer.valueOf(100).equals(PropsOverrideHook.INTEGERS.get(id))) return null;','/* ignore explicit custom quality */','field'),
 ('ignore_custom_false',MEDIA,'!Boolean.TRUE.equals(PropsOverrideHook.BOOLEANS.get(6033))','false','field'),
 ('lower_existing_size_limit',MEDIA,'Math.max(oldKb, effective[0])','effective[0]','field'),
 ('allow_static_fields',MEDIA,'field.getType() != int.class || Modifier.isStatic(field.getModifiers())','field.getType() != int.class','field'),
 ('collapse_equal_field_descriptors',MEDIA,'first.equals(second) || first.equals(third) || second.equals(third)','first == second || first == third || second == third','field'),
 ('ignore_constructor_failure',MEDIA,'if (!isImageQualityMaxEnabled() || param.hasThrowable()','if (!isImageQualityMaxEnabled()','field'),
 ('restore_positional_field_association',MEDIA,'Field[] resolved = resolveImageFields(instance, maxKb, quality, maxEdge);','Field[] resolved = verifiedImageFieldValues(instance,maxKb,quality,maxEdge) ? new Field[]{maxKb,quality,maxEdge} : null;','field'),
 ('conceal_failed_restore',MEDIA,'// Do not suppress the host result or report private reflective values.\n            return false;','// Bad mutation: claims restoration succeeded despite a failed reflective write.\n            return true;','field'),
]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--dexkit-jar',type=Path,required=True);p.add_argument('--kotlin-stdlib',type=Path,required=True);a=p.parse_args();out=a.out.resolve();out.mkdir(parents=True,exist_ok=False)
 originals={name:(ROOT/name).read_text(encoding='utf-8') for name in [MEDIA,SELECTOR,PROCESS,VIEW,OWNERSHIP,GUARD,REGISTRY,FIXTURE]}
 results=[]
 for name,path,before,after,suite in MUTANTS:
  if originals[path].count(before)!=1:raise AssertionError(name+': nonunique/absent mutation anchor')
  with tempfile.TemporaryDirectory(prefix='waex_v206_mutant_') as temp:
   root=Path(temp)
   for rel,content in originals.items():
    target=root/rel;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content.replace(before,after,1) if rel==path else content,encoding='utf-8')
   if suite=='field':
    result_dir=out/(name+'-field');command=[sys.executable,str(ROOT/'qa/test_media_v206_field_contract.py'),'--source-root',str(root),'--dexkit-jar',str(a.dexkit_jar.resolve()),'--kotlin-stdlib',str(a.kotlin_stdlib.resolve()),'--out',str(result_dir)]
   else:command=[sys.executable,str(ROOT/'qa/test_media_hook_contracts.py'),'--source-root',str(root),'--suite',suite]
   run=subprocess.run(command,capture_output=True,encoding='utf-8',errors='replace',timeout=120)
   text=run.stdout+run.stderr;(out/(name+'.log')).write_text(text,encoding='utf-8')
   if suite=='field':
    report=json.loads((result_dir/'result.json').read_text(encoding='utf-8'))
    compiled=report['compiler_exit']==0;checks=report['checks'];failures=report['failed'];complete=checks==56 and report['runtime_exit'] is not None
   else:
    match=re.search(r'RESULT checks=(\d+) failures=(\d+) suite=(\w+)',run.stdout)
    compiled=match is not None;checks=int(match[1]) if match else 0;failures=int(match[2]) if match else 0;complete=match is not None
   killed=run.returncode!=0 and compiled and complete and failures>0
   item={'name':name,'suite':suite,'compile_passed':compiled,'completed':complete,'checks':checks,'assertion_failures':failures,'exit':run.returncode,'behaviorally_detected':killed,'mutated_source_sha256':hashlib.sha256((root/path).read_bytes()).hexdigest()}
   results.append(item);print(json.dumps(item),flush=True)
   (out/'results.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
 if not all(x['behaviorally_detected'] for x in results):raise AssertionError('A mutant survived or failed before its behavioral oracle completed')
 print('PASS '+str(len(results))+' compiled behavioral mutation controls',flush=True)
if __name__=='__main__':main()

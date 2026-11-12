#!/usr/bin/env python3
"""Require behavioral rejection of the 15 reviewed renderer/store fault classes.

This companion suite runs all current row scenarios on Android API 34, matching
its original single-API scope. The positive runner separately requires APIs
28, 34 and 36. Compilation, extraction and framework setup failures never count
as detecting a mutation. No application APK or WhatsApp account is used.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('android_rows', ROOT/'qa/android_presence_row_test.py')
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)

# Overlay-specific faults follow the current normal-child contract. The former
# missing overlay measurement, lost overlay direction and duplicate overlay
# faults are now missing child attachment, forced LTR and duplicate dot children.
# No production overlay implementation is restored to satisfy obsolete tests.
VARIANTS = [
    ('denial-is-known-offline', qa.STORE,
     'record(jid, false, false);', 'record(jid, true, false);',
     'deniedPresenceClearsVisibleDotAndTextWithoutLastSeen'),
    ('no-global-clear', qa.STORE,
     '            STATES.clear();', '            // Fault: retain transport observations',
     'transportInvalidationRepaintsAlreadyVisibleRows'),
    ('unknown-cannot-mask-alias', qa.STORE,
     'candidate != null && (latest == null', 'candidate != null && candidate.known && (latest == null',
     'deniedAliasMasksAnOlderCanonicalObservation'),
    ('no-dot-child-attachment', qa.RENDERER,
     'targetGroup.addView(dot, params)', 'Unit // Fault: omit normal child attachment',
     'exactCachedOnlineMeasuresVisibleDot'),
    ('ignore-cached-alias', qa.RENDERER,
     'PresenceStateStore.getLatest(keys)', 'PresenceStateStore.get(jid)',
     'cachedAliasReplayedBeforeFirstBindAndAfterRebind'),
    ('alias-does-not-repaint', qa.RENDERER,
     '        queuePresenceRefresh()\n    }\n\n    companion object', '    }\n\n    companion object',
     'lateAliasRegistrationRepaintsAlreadyBoundRow'),
    ('oldest-alias-wins', qa.STORE,
     'candidate.revision > latest.revision', 'candidate.revision < latest.revision',
     'latestObservationWinsWithoutWallClockOrdering'),
    ('no-observation-order', qa.STORE,
     'long revision = ++nextRevision;\n            long changedAt',
     'long revision = nextRevision;\n            long changedAt',
     'repeatedObservationPreservesChangeTimeAndRefreshesRevision'),
    ('ignore-repeat-observation', qa.STORE,
     'notifyListeners(key, state);', 'if (transition) notifyListeners(key, state);',
     'latestObservationWinsWithoutWallClockOrdering'),
    ('conflicting-alias-overwrites', qa.RENDERER,
     'if (previous == null || previous == jid) jid else ""', 'jid',
     'conflictingAliasCannotTransferPresenceBetweenContacts'),
    ('stale-dot-on-cleared-row', qa.RENDERER,
     '        render(row, null)\n    }', '    }',
     'nullQueuedBindClearsPriorDecoration'),
    ('drop-current-event-render', qa.RENDERER,
     'render(row, stateForRow(boundJid))', 'render(row, null)',
     'exactCachedOnlineMeasuresVisibleDot'),
    ('offline-state-is-visible', qa.RENDERER,
     'if (isOnline) View.VISIBLE', 'if (state != null) View.VISIBLE',
     'invisibleToVisibleTransitionPerformsMeasurement'),
    ('dot-ignores-host-direction', qa.RENDERER,
     '                id = ID_DOT', '                id = ID_DOT\n                layoutDirection = View.LAYOUT_DIRECTION_LTR',
     'rtlFollowsHostDirection'),
    ('duplicate-dot-children', qa.RENDERER,
     'var dot = targetGroup.findViewById<View>(ID_DOT)', 'var dot: View? = null',
     'repeatedBindReusesUniqueDotAndTextChildren'),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    parser.add_argument('deps', type=Path)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--sdk-dir', type=Path)
    args = parser.parse_args()
    out, deps, root = args.out.resolve(), args.deps.resolve(), args.source_root.resolve()
    out = qa.fresh_output(out)
    if not deps.is_dir():
        raise RuntimeError('Verified dependency directory is missing: '+str(deps))
    original_sdk = 'sdk = [28, 34, 36]'
    if qa.TESTS.count(original_sdk) != 1:
        raise AssertionError('Expected the current three-API positive fixture')
    qa.TESTS = qa.TESTS.replace(original_sdk, 'sdk = [34]')
    qa.SDK_LEVELS = (34,)
    qa.EXPECTED_TESTS = len(qa.TEST_NAMES)
    runtime_jars = qa.prepare_runtime_dependencies(deps, out)
    host_resources = qa.build_host_resources(out/'host-resources', qa.find_aapt2(args.aapt2))
    renderer = (root/qa.RENDERER).read_text(encoding='utf-8')
    store = (root/qa.STORE).read_text(encoding='utf-8')
    results = []
    for name, path, old, new, oracle in VARIANTS:
        original = renderer if path == qa.RENDERER else store
        if original.count(old) != 1:
            raise AssertionError('Non-unique reviewed fault '+name)
        if oracle not in qa.TEST_NAMES:
            raise AssertionError('Mutation oracle not declared: '+oracle)
        folder = out/name
        if folder.exists():
            raise RuntimeError('Refusing to overwrite mutation evidence: '+str(folder))
        with tempfile.TemporaryDirectory(prefix='waex_mutant_') as td:
            candidate = Path(td)
            for relative, source in [(qa.RENDERER, renderer), (qa.STORE, store)]:
                dest = candidate/relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(source.replace(old, new, 1) if relative == path else source,
                                encoding='utf-8')
            for name_java in ('UiText.java', 'DisplayLocalePolicy.java'):
                relative = Path('app/src/main/java/com/waenhancer/utils')/name_java
                (candidate/relative).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(root/relative, candidate/relative)
            # Same extraction, compiler, framework and expectations as positive.
            # baseline=True requests behavioral rejection, not a reduced fixture.
            result = qa.execute(candidate, folder, deps, True, host_resources, runtime_jars,
                                args.sdk_dir.resolve() if args.sdk_dir else None)
            log = (folder/'junit.log').read_text(encoding='utf-8')
            failures = re.findall(r'^\d+\) ([A-Za-z0-9_]+)(?:\[[^\]]+\])?\(PresenceRowRenderingTest\)',
                                  log, re.M)
            if oracle not in failures:
                raise AssertionError('Mutation did not fail its discriminating oracle: '+name)
            results.append({'mutation': name, 'rejected_by_behavior': True,
                            'android_api': 34, 'unique_scenarios': len(qa.TEST_NAMES),
                            'oracle': oracle, 'failures': failures,
                            'mutated_source_sha256': hashlib.sha256((candidate/path).read_bytes()).hexdigest(),
                            'result': result})
            (out/'mutation-results.json').write_text(json.dumps(results, indent=2)+'\n', encoding='utf-8')
        print('REJECTED_BY_ASSERTION', name, flush=True)
    print('PASS:', len(results), 'reviewed fault classes rejected by Android 14 behavioral assertions')


if __name__ == '__main__':
    main()

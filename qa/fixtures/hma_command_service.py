#!/usr/bin/env python3
"""Explicit HMA command boundary for QA; never calls su or accesses Android data.

The test-owned executable named `su` accepts only enumerated fixture operations.
Generated config scripts run with unchanged shell logic after logical Android
paths are mapped to test-owned temporary files. Unknown commands fail closed.
"""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

root = Path(os.environ['HMA_QA_ROOT']).resolve()
case = Path((root / 'active-case.txt').read_text().strip()).resolve()
if root not in case.parents:
    raise SystemExit(96)
control = json.loads((case / 'control.json').read_text())


def event(kind, **values):
    with (case / 'events.jsonl').open('a') as out:
        out.write(json.dumps({'kind': kind, **values}) + '\n')


def checked_file(value):
    path = Path(value).absolute()
    if case not in path.parents:
        event('denied-file', path=value)
        raise SystemExit(96)
    return path


name = Path(sys.argv[0]).name
if name != 'su':
    if name not in ('cp', 'chown', 'chmod', 'mv', 'chcon', 'restorecon'):
        raise SystemExit(96)
    event('tool', tool=name, args=sys.argv[1:])
    mode = os.environ.get('HMA_QA_WRITE_MODE', 'normal')
    if mode == name + '_fail':
        raise SystemExit(13)
    # SELinux is an explicit unsupported endpoint, never invoked on this host.
    if name in ('chcon', 'restorecon'):
        raise SystemExit(0)
    for argument in sys.argv[1:]:
        if argument.startswith('/'):
            checked_file(argument)
    binary = Path('/usr/bin') / name
    result = subprocess.run([str(binary), *sys.argv[1:]], check=False)
    raise SystemExit(result.returncode)

if len(sys.argv) != 3 or sys.argv[1] != '-c':
    raise SystemExit(96)
command = sys.argv[2]
event('command', command=command)
if re.fullmatch(r'pm list packages(?: --user [0-9]+)?', command):
    words = shlex.split(command)
    user = int(words[4]) if len(words) == 5 else 0
    event('list', user=user)
    packages = control.get('packages_by_user', {}).get(str(user), control.get('packages', []))
    print('\n'.join('package:' + name for name in packages))
    raise SystemExit(0)
if command.startswith('am force-stop '):
    words = shlex.split(command)
    if words[:2] != ['am', 'force-stop']:
        raise SystemExit(96)
    if len(words) == 5 and words[2] == '--user' and words[3].isdigit():
        user, package = int(words[3]), words[4]
    elif len(words) == 3:
        user, package = -1, words[2]  # Historical am default: all Android users.
    else:
        raise SystemExit(96)
    event('stop', package=package, user=user)
    raise SystemExit(0)
if command.startswith('[ -d /data/adb/modules/hma_oss_zygisk ]'):
    if control.get('zygisk_active', True):
        print('active')
    raise SystemExit(0)
if command == '[ -f /data/adb/lspd/config/modules_config.db ] && echo exists':
    raise SystemExit(1)
if command.startswith('find /data/misc /data/system /data/adb /data/user/0 /data/data '):
    event('discover', user=0)
    print('\n'.join(control['paths']))
    raise SystemExit(0)
if command.startswith('for waex_path in /data/misc/hide_my_applist*/config.json /data/system/hide_my_applist*/config.json '):
    candidates = re.findall(r'/data/user/([0-9]+)/([^/]+)/files/config.json', command)
    managers = {'com.tsng.hidemyapplist', 'org.frknkrc44.hma_oss', 'com.google.android.hmal'}
    if len(candidates) != 3 or {pkg for _, pkg in candidates} != managers or len({uid for uid, _ in candidates}) != 1:
        raise SystemExit(96)
    event('discover', user=int(candidates[0][0]))
    # Return every fixture path, including foreign users. Production must filter
    # canonical output itself before reading or repairing a private configuration.
    print('\n'.join(control['paths']))
    raise SystemExit(0)

logical = next((p for p in sorted(control['files'], key=len, reverse=True) if p in command), None)
if logical is None:
    event('denied-command')
    raise SystemExit(96)
physical = checked_file(control['files'][logical])
translated = command.replace(logical, str(physical))
if '/data/' in translated:
    event('denied-command')
    raise SystemExit(96)
is_read = command.startswith('cat ') or command.startswith('[ -f ')
is_backup = ('WAEX_HMA_CONFIG_BACKED_UP' in command or 'WAEX_HMA_BACKUP_OK' in command)
is_restore = ('WAEX_HMA_CONFIG_RESTORED' in command
              or (command.startswith('cp -p ') and '.waex.bak' in command and not is_backup))
is_write = (command.startswith('set -e\n') or command.startswith('OWNER=$(')) and not is_backup and not is_restore
if not any((is_read, is_write, is_backup, is_restore)):
    event('denied-command')
    raise SystemExit(96)
kind = 'read' if is_read else 'backup' if is_backup else 'restore' if is_restore else 'write'
mode = control.get(kind + '_mode', 'normal') if kind in ('backup', 'restore') else \
    control.get('faults', {}).get(logical, control.get('mode', 'normal'))
event(kind, path=logical, mode=mode)
if is_read and mode == 'read_fail':
    raise SystemExit(13)
if not is_read and mode in ('empty_ack', 'fake_ack'):
    if mode == 'fake_ack':
        ack = 'WAEX_HMA_CONFIG_BACKED_UP' if is_backup else \
              'WAEX_HMA_CONFIG_RESTORED' if is_restore else 'WAEX_HMA_CONFIG_WRITTEN'
        print(ack, end='')
    raise SystemExit(0)
if is_write and mode == 'stale_before_write':
    physical.write_text(control['replacement'])
if is_backup and mode == 'stale_before_backup':
    physical.write_text(control['replacement'])
if is_restore and mode == 'changed_before_restore':
    physical.write_text(control['replacement'])
if is_restore and mode == 'tampered_backup':
    Path(str(physical) + '.waex.bak').write_text(control['backup_replacement'])
env = dict(os.environ, HMA_QA_WRITE_MODE=mode)
result = subprocess.run(['/bin/sh', '-c', translated], env=env, stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE, timeout=3, check=False)
output = result.stdout
if is_read and mode == 'truncated_read':
    output = output[:max(0, len(output) - 17)]
if is_read and mode == 'wrong_digest' and command.startswith('[ -f '):
    output = b'0' * 64 + output[64:]
if is_read and mode == 'oversized_output':
    output += b'x' * (100 * 1024)
if not is_read and result.returncode == 0:
    if mode == 'ack_suffix':
        output += b'UNEXPECTED'
    if is_write and mode == 'changed_after_write':
        physical.write_text(control['replacement'])
    if is_backup and mode == 'corrupt_after_backup':
        Path(str(physical) + '.waex.bak').write_text(control['backup_replacement'])
sys.stdout.buffer.write(output)
sys.stderr.buffer.write(result.stderr)
raise SystemExit(result.returncode)

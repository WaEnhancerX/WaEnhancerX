#!/usr/bin/env python3
"""Bounded recovery for the pinned public QA dependency-copy invocation.

Only Maven dependency/plugin resolution or transport failures are retried. The
same arguments and coordinates are used each time, with -U to refresh missing
release/snapshot checks. Full attempt output and a machine-readable summary are
retained beside the aggregate log. This never wraps compilation or test runs.
"""
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time

DEPENDENCY_GOAL = 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies'
MAX_ATTEMPTS = 3
MAX_TIMEOUT_SECONDS = 600
_ANSI = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')
_RESOLUTION = re.compile(
    r'PluginResolutionException|DependencyResolutionException|ArtifactResolutionException|'
    r'ArtifactDescriptorException|UnresolvableModelException|'
    r'could not (?:resolve dependencies|transfer artifact|find artifact)|'
    r'failed to (?:collect dependencies|read artifact descriptor)|'
    r'plugin .+? or one of its dependencies could not be resolved|'
    r'was cached in the local repository.+?resolution (?:is|will) not be reattempted|'
    r'unknownhostexception|connectexception|sockettimeoutexception|'
    r'connection (?:reset|timed out)|read timed out|temporary failure in name resolution', re.I)
_NONRETRYABLE = re.compile(
    r'non-parseable pom|malformed pom|compilation (?:error|failure)|there are test failures|'
    r'no goals have been specified|invalid plugin descriptor|'
    r'authentication failed|authorization failed|401 unauthorized|403 forbidden|'
    r'status(?: code)?\s*[:=]?\s*40[13]\b', re.I)


class MavenDependencyError(RuntimeError):
    """Resolution stopped explicitly; full details are retained in the logs."""


def _retryable(log):
    resolution = False
    with log.open(encoding='utf-8', errors='replace') as stream:
        for line in stream:
            line = _ANSI.sub('', line)
            if _NONRETRYABLE.search(line):
                return False
            resolution = resolution or bool(_RESOLUTION.search(line))
    return resolution


def _stop(process):
    # Maven's launcher normally execs Java. A process group also handles a
    # launcher that leaves children behind; all QA CI jobs run on POSIX hosts.
    try:
        if os.name == 'posix':
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        pass


def resolve_dependencies(command, log, timeout=600, max_attempts=3):
    """Return 0 on success, otherwise raise after at most three bounded attempts.

    ``timeout`` covers the complete attempt/backoff sequence (maximum 600s).
    Timed-out processes are killed as a group, with at most 1s cleanup allowance.
    Compilation/test errors, malformed POMs and credential denial are not retried.
    ``max_attempts=1`` supports a deliberate baseline without recovery.
    """
    if not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or not 0 < timeout <= MAX_TIMEOUT_SECONDS:
        raise ValueError('Dependency resolution timeout must be in (0, 600] seconds')
    if not isinstance(max_attempts, int) or not 1 <= max_attempts <= MAX_ATTEMPTS:
        raise ValueError('Dependency resolution requires 1..3 attempts')
    argv = [str(value) for value in command]
    if len(argv) < 2 or argv.count(DEPENDENCY_GOAL) != 1:
        raise ValueError('Only the pinned Maven dependency-copy goal may use recovery')
    if '-fn' in argv or '--fail-never' in argv:
        raise ValueError('Maven dependency failures must not be suppressed')
    if '-U' not in argv and '--update-snapshots' not in argv:
        argv.insert(1, '-U')
    log = Path(log)
    log.parent.mkdir(parents=True, exist_ok=True)
    metadata = log.with_name(log.stem + '-retry.json')
    start = time.monotonic()
    deadline = start + timeout
    summary = {'goal': DEPENDENCY_GOAL, 'command': argv, 'timeout_seconds': timeout,
               'max_attempts': max_attempts, 'attempts': [], 'status': 'pending'}

    def save(status):
        summary['status'] = status
        summary['elapsed_seconds'] = round(time.monotonic() - start, 6)
        metadata.write_text(json.dumps(summary, indent=2) + '\n')

    save('pending')
    with log.open('wb') as aggregate:
        for number in range(1, max_attempts + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                save('timeout')
                break
            attempt_log = log.with_name(log.stem + '.attempt-' + str(number) + log.suffix)
            attempt_start = time.monotonic()
            attempt = {'number': number, 'log': attempt_log.name, 'timeout_seconds': remaining,
                       'exit_code': None, 'timed_out': False, 'retryable': False}
            process = None
            with attempt_log.open('wb') as output:
                try:
                    process = subprocess.Popen(argv, stdout=output, stderr=subprocess.STDOUT,
                                               start_new_session=(os.name == 'posix'))
                    attempt['exit_code'] = process.wait(timeout=max(0.001, deadline - time.monotonic()))
                except subprocess.TimeoutExpired:
                    attempt['timed_out'] = True
                    _stop(process)
                    attempt['exit_code'] = process.returncode
                except OSError as failure:
                    attempt['start_error'] = str(failure)
                    output.write((str(failure) + '\n').encode())
                except BaseException:
                    if process is not None:
                        _stop(process)
                    raise
            attempt['elapsed_seconds'] = round(time.monotonic() - attempt_start, 6)
            if attempt['exit_code'] not in (None, 0) and not attempt['timed_out']:
                attempt['retryable'] = _retryable(attempt_log)
            summary['attempts'].append(attempt)
            aggregate.write(('\n=== Maven dependency attempt ' + str(number) + ' ===\n').encode())
            with attempt_log.open('rb') as source:
                shutil.copyfileobj(source, aggregate)
            aggregate.write(('\n=== ' + json.dumps(attempt) + ' ===\n').encode())
            aggregate.flush()
            print(attempt_log.name, 'exit=' + str(attempt['exit_code']),
                  'retryable=' + str(attempt['retryable']), flush=True)
            if attempt['exit_code'] == 0:
                save('success')
                return 0
            if attempt['timed_out']:
                save('timeout')
                break
            if not attempt['retryable'] or number == max_attempts:
                save('failed')
                break
            save('retrying')
            delay = min(float(number), max(0.0, deadline - time.monotonic()))
            if delay:
                time.sleep(delay)
        else:
            save('failed')
    if summary['status'] == 'retrying':
        save('timeout')
    raise MavenDependencyError('Maven dependency resolution ' + summary['status'] + ' after '
        + str(len(summary['attempts'])) + ' attempt(s); see ' + str(log) + ' and ' + str(metadata))

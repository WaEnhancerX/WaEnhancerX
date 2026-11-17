#!/usr/bin/env python3
"""Fail-closed source checks for the reviewed Nightly/public-private boundary."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    import yaml
except ImportError as error:
    raise SystemExit("BLOCKED: PyYAML 6.0.3 is mandatory for Nightly workflow verification") from error

if yaml.__version__ != "6.0.3":
    raise SystemExit(f"BLOCKED: expected PyYAML 6.0.3; found {yaml.__version__}")


ROOT = Path(__file__).resolve().parents[1]
DISPATCH = ROOT / ".github/workflows/nightly-reviewed-build-dispatch.yml"
SOURCE_QA = ROOT / ".github/workflows/source-qa.yml"
PRIVATE_EXAMPLE = ROOT / "docs/examples/nightly-private-builder.yml"
DOC = ROOT / "docs/NIGHTLY_RELEASE_PIPELINE.md"
BUILD_CONFIG = ROOT / "app/build.gradle.kts"
REQUIREMENTS = ROOT / "qa/requirements-nightly.txt"


def check_dispatch(source: str) -> None:
    assert re.search(r"(?m)^on:\n  push:\n    branches: \[nightly\]", source), "Nightly push-only trigger"
    assert not re.search(r"(?m)^  (pull_request|pull_request_target|workflow_dispatch):", source), "No unreviewed dispatch trigger"
    assert re.search(r"(?m)^permissions:\n  contents: read$", source), "Read-only default token"
    assert "uses: ./.github/workflows/source-qa.yml" in source, "Full reusable source QA"
    assert re.search(r"(?m)^    needs: source-qa$", source), "Release dispatch depends on QA"
    assert "github.repository == 'WaEnhancerX/WaEnhancerX'" in source, "Official repository guard"
    assert "github.ref == 'refs/heads/nightly'" in source, "Branch guard"
    assert "github.event_name == 'push'" in source, "Push event guard"
    assert "WAEX_NIGHTLY_RELEASE_ENABLED" in source, "Maintainer enable gate"
    assert "WAEX_NIGHTLY_BUILDER_REPOSITORY" in source, "Private destination gate"
    assert "secrets.WAEX_NIGHTLY_DISPATCH_TOKEN" in source, "Scoped dispatch token"
    assert "if [[ -z \"$DISPATCH_TOKEN\" ]]" in source, "Missing token fails closed"
    assert "select(.private == true) | .full_name" in source, "Private visibility checked against API metadata"
    assert "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9]" in source, "No path traversal in target name"
    assert "event_type:\"waex-nightly-build\"" in source, "Correct private event"
    assert "source_sha:$sha" in source and "source_ref:$ref" in source, "Exact source SHA and ref"
    assert "https://api.github.com/repos/${PRIVATE_REPOSITORY}/dispatches" in source, "Private dispatch endpoint"
    assert "curl --fail-with-body" in source and "--retry 0" in source, "No blind write retries"
    assert "not proof that an APK was produced" in source, "Dispatch is not publication"
    assert not re.search(r"(?m)^\s*runs-on:\s*\[?self-hosted", source), "No protected runner on public repository"
    assert not re.search(r"(?m)^\s*contents:\s*write\s*$", source), "No write-token public job"
    assert "gh release create" not in source, "No public job bypassing private build"


def check_source_qa(source: str) -> None:
    assert re.search(r"(?m)^  workflow_call:$", source), "Public QA is reusable"
    assert re.search(r"(?m)^  pull_request:\n    branches: \[main, nightly\]", source), "Review QA includes nightly PRs"
    assert "qa/test_nightly_release_contract.py" in source, "Nightly invariants run on CI"
    assert re.search(r"(?m)^permissions:\n  contents: read$", source), "Reusable QA stays read-only"
    assert "--require-hashes --only-binary=:all: -r qa/requirements-nightly.txt" in source, "Hash-pinned parser provision"


def check_private_template(source: str) -> None:
    assert re.search(r"(?m)^on:\n  repository_dispatch:\n    types: \[waex-nightly-build\]", source), "Private event contract"
    assert "SOURCE_REPOSITORY" in source and "WaEnhancerX/WaEnhancerX" in source, "Source owner validation"
    assert "refs/heads/nightly" in source and "^[0-9a-f]{40}$" in source, "SHA and branch validation"
    assert source.count("git ls-remote https://github.com/WaEnhancerX/WaEnhancerX.git") >= 2, "Head rechecked before publication"
    assert re.search(r"(?m)^    runs-on: \[self-hosted, linux, waex-protected-nightly\]", source), "Private-only builder runner"
    assert "WAEX_NIGHTLY_BUILD_SCRIPT" in source and "GITHUB_WORKSPACE" in source, "Protected script contract"
    assert "persist-credentials: false" in source and "submodules: false" in source, "No private source checkout from public"
    assert "284325b6f0ca18da5e4c237a6c0618c2a22b08ff8d4a5d11fd12478dad690a84" in source, "Pinned certificate identity"
    assert "apksigner verify --verbose --print-certs" in source, "Independent signature verification"
    assert "waex-nightly.sha256" in source and "sha256sum" in source, "Artifact hash verification"
    assert "--prerelease" in source and "--target \"$SOURCE_SHA\"" in source, "Prerelease targets reviewed commit"
    assert "GH_TOKEN: ${{ secrets.WAEX_PUBLIC_NIGHTLY_PUBLISH_TOKEN }}" in source, "Publisher-only release token"
    assert "No force update" in source, "No overwrites"
    assert not re.search(r"(?m)^  (pull_request|pull_request_target):", source), "No PR access to protected runner"


def check_signing_identity_consistency(private: str, gradle: str) -> None:
    certificate = re.search(
        r'expectedSigningCertificateSha256\s*=\s*"([0-9a-f]{64})"', gradle
    )
    assert certificate, "Official certificate must remain explicitly pinned in production build"
    assert certificate.group(1) in private, "Nightly publisher certificate must match production Gradle configuration"


def parse_yaml_and_shell(where: str, content: str, expected_events: set[str]) -> tuple[int, str]:
    parsed = yaml.load(content, Loader=yaml.BaseLoader)
    assert isinstance(parsed, dict), f"{where}: YAML must be a mapping"
    assert set(parsed["on"]) == expected_events, f"{where}: unexpected events"
    count = 0
    for job in parsed["jobs"].values():
        for step in job.get("steps", []):
            if "run" not in step:
                continue
            completed = subprocess.run(["bash", "-n"], input=step["run"], text=True,
                                       capture_output=True, timeout=10)
            assert completed.returncode == 0, f"{where}: invalid bash: {completed.stderr}"
            count += 1
    return count, "PASS"


def mocked_boundary_checks(dispatch: str, private: str) -> dict:
    """Execute shell contracts with stand-in commands; never call GitHub or sign an APK."""
    public_steps = yaml.load(dispatch, Loader=yaml.BaseLoader)["jobs"]["request-protected-build"]["steps"]
    private_jobs = yaml.load(private, Loader=yaml.BaseLoader)["jobs"]
    verified_steps = private_jobs["verify-reviewed-source"]["steps"]
    published_steps = private_jobs["publish-prerelease"]["steps"]
    source_sha = "a" * 40
    cases = 0

    def execute(script: str, environment: dict[str, str], expected_success: bool) -> subprocess.CompletedProcess:
        nonlocal cases
        completed = subprocess.run(["bash", "-c", script], env={**os.environ, **environment},
                                   text=True, capture_output=True, timeout=15)
        assert (completed.returncode == 0) == expected_success, (
            f"Mock shell result unexpected: exit={completed.returncode}; stderr={completed.stderr}"
        )
        cases += 1
        return completed

    with tempfile.TemporaryDirectory(prefix="waex-nightly-mock-") as temp:
        tempdir = Path(temp)
        mock_bin = tempdir / "bin"
        mock_bin.mkdir()
        capture = tempdir / "dispatch.json"
        (mock_bin / "curl").write_text(
            "#!/usr/bin/env python3\n"
            "import json,os,sys\n"
            "args=sys.argv[1:]\n"
            "if '--data' not in args:\n"
            " print(json.dumps({'private':os.environ.get('WAEX_MOCK_PRIVATE','true')=='true',"
            " 'full_name':os.environ.get('WAEX_MOCK_FULL_NAME','maintainer/private-waex-build')}))\n"
            " sys.exit(int(os.environ.get('WAEX_MOCK_GET_EXIT','0')))\n"
            "data=json.loads(args[args.index('--data')+1])\n"
            "with open(os.environ['WAEX_MOCK_CAPTURE'],'w',encoding='utf-8') as out:\n"
            " json.dump({'payload':data,'url':args[-1]},out)\n"
            "sys.exit(int(os.environ.get('WAEX_MOCK_EXIT','0')))\n", encoding="utf-8"
        )
        (mock_bin / "curl").chmod(0o700)
        (mock_bin / "git").write_text(
            "#!/usr/bin/env python3\n"
            "import os,sys\n"
            "if sys.argv[1:3]==['ls-remote','https://github.com/WaEnhancerX/WaEnhancerX.git']:\n"
            " print(os.environ['WAEX_MOCK_HEAD']+'\\trefs/heads/nightly')\n"
            "else: sys.exit(2)\n", encoding="utf-8"
        )
        (mock_bin / "git").chmod(0o700)
        base_env = {
            "PATH": str(mock_bin) + os.pathsep + os.environ["PATH"],
            "GITHUB_REPOSITORY": "WaEnhancerX/WaEnhancerX",
            "GITHUB_REF": "refs/heads/nightly",
            "GITHUB_SHA": source_sha,
            "GITHUB_RUN_ID": "9001",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_STEP_SUMMARY": str(tempdir / "summary.txt"),
            "ENABLED": "true",
            "PRIVATE_REPOSITORY": "maintainer/private-waex-build",
            "DISPATCH_TOKEN": "TEST_ONLY_NOT_A_CREDENTIAL",
            "WAEX_MOCK_CAPTURE": str(capture),
            "WAEX_MOCK_HEAD": source_sha,
        }
        guard = public_steps[0]["run"]
        execute(guard, base_env, True)
        execute(guard, {**base_env, "ENABLED": "false"}, False)
        execute(guard, {**base_env, "PRIVATE_REPOSITORY": "WaEnhancerX/WaEnhancerX"}, False)
        execute(guard, {**base_env, "PRIVATE_REPOSITORY": "../.."}, False)
        execute(guard, {**base_env, "PRIVATE_REPOSITORY": "maintainer/.."}, False)
        execute(guard, {**base_env, "DISPATCH_TOKEN": ""}, False)
        execute(guard, {**base_env, "WAEX_MOCK_PRIVATE": "false"}, False)
        execute(guard, {**base_env, "WAEX_MOCK_FULL_NAME": "maintainer/not-the-builder"}, False)
        execute(guard, {**base_env, "WAEX_MOCK_GET_EXIT": "22"}, False)

        request = public_steps[1]["run"]
        execute(request, base_env, True)
        event = json.loads(capture.read_text(encoding="utf-8"))
        assert event == {
            "payload": {
                "event_type": "waex-nightly-build",
                "client_payload": {
                    "source_repository": "WaEnhancerX/WaEnhancerX",
                    "source_ref": "refs/heads/nightly",
                    "source_sha": source_sha,
                    "source_run_id": "9001",
                    "source_run_attempt": "1",
                },
            },
            "url": "https://api.github.com/repos/maintainer/private-waex-build/dispatches",
        }, "Incorrect dispatch payload or destination"
        execute(request, {**base_env, "WAEX_MOCK_EXIT": "22"}, False)

        private_guard = verified_steps[0]["run"]
        private_env = {
            **base_env,
            "SOURCE_REPOSITORY": "WaEnhancerX/WaEnhancerX",
            "SOURCE_REF": "refs/heads/nightly",
            "SOURCE_SHA": source_sha,
            "SOURCE_RUN_ID": "9001",
            "SOURCE_RUN_ATTEMPT": "1",
            "GITHUB_OUTPUT": str(tempdir / "private-outputs.txt"),
        }
        execute(private_guard, private_env, True)
        assert f"sha={source_sha}" in (tempdir / "private-outputs.txt").read_text(), "SHA output"
        execute(private_guard, {**private_env, "SOURCE_REF": "refs/heads/main"}, False)
        execute(private_guard, {**private_env, "WAEX_MOCK_HEAD": "b" * 40}, False)

        publisher = tempdir / "waex-nightly-publish"
        publisher.mkdir()
        apk = publisher / "waex-nightly.apk"
        apk.write_bytes(b"TEST_BYTES_NOT_A_REAL_APK")
        checksum = publisher / "waex-nightly.sha256"
        checksum.write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + "\n")
        sdk = tempdir / "android-sdk" / "build-tools" / "36.0.0"
        sdk.mkdir(parents=True)
        (sdk / "apksigner").write_text(
            "#!/bin/sh\n"
            "echo \"Signer #1 certificate SHA-256 digest: ${WAEX_MOCK_CERT}\"\n", encoding="utf-8"
        )
        (sdk / "aapt").write_text(
            "#!/bin/sh\n"
            "echo \"package: name='${WAEX_MOCK_PACKAGE}' versionCode='20601' versionName='${WAEX_MOCK_VERSION}'\"\n",
            encoding="utf-8",
        )
        (sdk / "apksigner").chmod(0o700)
        (sdk / "aapt").chmod(0o700)
        verify = next(s["run"] for s in published_steps if s.get("name", "").startswith("Verify artifact"))
        publisher_env = {
            **private_env,
            "RUNNER_TEMP": str(tempdir),
            "ANDROID_HOME": str(tempdir / "android-sdk"),
            "WAEX_MOCK_CERT": "284325b6f0ca18da5e4c237a6c0618c2a22b08ff8d4a5d11fd12478dad690a84",
            "WAEX_MOCK_PACKAGE": "com.waenhancer",
            "WAEX_MOCK_VERSION": "2.0.6-nightly.1",
        }
        execute(verify, publisher_env, True)
        execute(verify, {**publisher_env, "WAEX_MOCK_CERT": "f" * 64}, False)
        execute(verify, {**publisher_env, "WAEX_MOCK_PACKAGE": "com.invalid"}, False)
        execute(verify, {**publisher_env, "WAEX_MOCK_VERSION": "2.0.6-stable"}, False)
        execute(verify, {**publisher_env, "WAEX_MOCK_HEAD": "b" * 40}, False)
        checksum.write_text("0" * 64 + "\n")
        execute(verify, publisher_env, False)

    return {"status": "PASS", "shell_scenarios": cases, "network_calls": 0,
            "real_signing_keys_used": False, "real_apk_verified": False}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    # Negative dependency control: an isolated Python cannot import PyYAML.
    # Missing parser support must never produce a successful source-QA report.
    negative_dependency = subprocess.run(
        [sys.executable, "-I", "-S", str(Path(__file__).resolve())],
        text=True, capture_output=True, timeout=15,
    )
    assert negative_dependency.returncode != 0 and "PyYAML" in negative_dependency.stderr, (
        "Missing YAML parser must fail closed, not silently skip workflow checks"
    )
    for path in (DISPATCH, SOURCE_QA, PRIVATE_EXAMPLE, DOC, BUILD_CONFIG, REQUIREMENTS):
        if not path.is_file():
            raise SystemExit(f"FAIL: required Nightly file missing: {path}")

    dispatch = DISPATCH.read_text(encoding="utf-8")
    qa = SOURCE_QA.read_text(encoding="utf-8")
    private = PRIVATE_EXAMPLE.read_text(encoding="utf-8")
    documentation = DOC.read_text(encoding="utf-8")
    check_dispatch(dispatch)
    check_source_qa(qa)
    check_private_template(private)
    check_signing_identity_consistency(private, BUILD_CONFIG.read_text(encoding="utf-8"))
    requirements = REQUIREMENTS.read_text(encoding="utf-8")
    assert "PyYAML==6.0.3" in requirements, "Pinned parser version"
    assert "b8bb0864c5a28024fac8a632c443c87c5aa6f215c0b126c449ae1a150412f31d" in requirements, "Pinned PyPI CPython 3.11 Linux wheel digest"
    assert "HTTP 204" in documentation and "private" in documentation.lower(), "Explicit integration limits"

    mutations = {
        "untrusted-trigger": dispatch.replace("branches: [nightly]", "branches: [main]", 1),
        "no-qa-dependency": dispatch.replace("needs: source-qa", "needs: []", 1),
        "no-upstream-guard": dispatch.replace("github.repository == 'WaEnhancerX/WaEnhancerX'", "true", 1),
        "privileged-token": dispatch.replace("permissions:\n  contents: read", "permissions:\n  contents: write", 1),
        "no-enable-gate": dispatch.replace("WAEX_NIGHTLY_RELEASE_ENABLED", "WAEX_NIGHTLY_DISABLED", 1),
        "unreviewed-pull": dispatch.replace("  push:\n", "  pull_request:\n", 1),
    }
    rejected = []
    for name, modified in mutations.items():
        try:
            check_dispatch(modified)
        except AssertionError:
            rejected.append(name)
        else:
            raise SystemExit(f"FAIL: negative control did not detect {name}")

    count_source, status_source = parse_yaml_and_shell(
        "reusable source QA", qa, {"workflow_call", "push", "pull_request", "workflow_dispatch"}
    )
    count_public, status_public = parse_yaml_and_shell("public dispatcher", dispatch, {"push"})
    count_private, status_private = parse_yaml_and_shell("private example", private, {"repository_dispatch"})
    mock_result = mocked_boundary_checks(dispatch, private)
    report = {
        "status": "PASS",
        "contracts": ["public branch/QA/permission boundary", "private signing and publication boundary"],
        "negative_mutations_rejected": rejected,
        "missing_parser_negative_control": "PASS",
        "bash_steps_parsed": count_source + count_public + count_private,
        "yaml_shell_status": {"source_qa": status_source, "public": status_public, "private": status_private},
        "mocked_orchestration": mock_result,
        "private_builder_executed": False,
        "signed_apk_created": False,
        "github_prerelease_created": False,
    }
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))


if __name__ == "__main__":
    main()

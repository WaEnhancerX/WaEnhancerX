# WAEX Nightly: reviewed source, protected build, and automated pre-release

This is a two-repository deployment contract. It does **not** grant public Actions
jobs access to official signing keys, the private `:licensing` module, or release
credentials. The public repository must not host the protected build runner.

## Review and automation sequence

1. Contributors propose small, reviewable PRs **against `nightly`**. Public
   source/JVM QA runs on PRs targeting `main` or `nightly`; neither route
   accesses private build materials.
2. A maintainer reviews and merges selected PRs into the protected `nightly`
   branch. A `push` to `WaEnhancerX/WaEnhancerX` `nightly` starts
   `.github/workflows/nightly-reviewed-build-dispatch.yml`.
3. The dispatcher reuses the **complete** `source-qa.yml` workflow. Any failure
   blocks the next job. Only an upstream `nightly` push can request a build.
   The Nightly contract tests require a PyYAML 6.0.3 wheel with a pinned SHA-256;
   if dependency installation or YAML validation fails, source QA fails rather
   than silently skipping security checks.
4. After that gate, a GitHub-hosted job checks authenticated repository metadata
   to confirm the separately configured destination **is private and has exactly
   the expected repository identity**. It then sends the exact SHA and source run ID
   via `repository_dispatch` to a separately configured **private** repository.
   Its event payload contains no source archives, signing keys, APKs, tokens or
   licensing material. The GitHub API receives only the scoped dispatch token
   in its authorization header.
5. A private workflow independently checks the SHA against the current
   upstream `nightly` HEAD. A protected runner, managed by the maintainer,
   compiles/signs the normal WAEX build with the original compatible licensing
   and signing environment, and returns only the finished APK and digest.
6. A separate hosted publisher verifies the digest, package, Nightly version
   and official certificate, rechecks that the SHA is still the current
   `nightly` HEAD, then creates a **new** GitHub `--prerelease` targeting that
   commit. Stable `main` and official stable releases are never modified by
   this automation.

`HTTP 204` from the public dispatcher confirms only that GitHub accepted the
private build request. It does not prove that the private job ran, built, signed,
or published anything. Check the private workflow result and public release URL.

## Required maintainer setup (not included in this PR)

On the **public** `WaEnhancerX/WaEnhancerX` repository:

- Protect `nightly`: require maintainer review and appropriate passing PR checks;
  block force pushes and unauthorized direct pushes.
- Create the `nightly-dispatch` Actions environment and restrict it to the
  `nightly` branch. Choose its required reviewer policy; an approval gate adds
  manual review after merge, whereas no approval allows automatic dispatch.
- Set `WAEX_NIGHTLY_RELEASE_ENABLED=true` only when the private end is ready.
- Set `WAEX_NIGHTLY_BUILDER_REPOSITORY=OWNER/PRIVATE_REPO` as a repository or
  environment variable. This must **not** be the public WAEX repository.
- Set the `WAEX_NIGHTLY_DISPATCH_TOKEN` environment secret to a short-lived or
  revocable fine-grained token with **Contents: write only on the private build
  repository**, for the repository-dispatch endpoint. This is *not* a signing
  key or a private-module credential; do not expand its permissions.

On a **separate private build repository** under the maintainer's control:

- Copy the inert example `docs/examples/nightly-private-builder.yml` into
  its default branch under `.github/workflows/` after a security review.
  The event is `repository_dispatch` with type `waex-nightly-build`.
- Restrict its protected self-hosted Linux runner to **that private repository**
  and label it `waex-protected-nightly`. Prefer an ephemeral, network-restricted
  VM/container with dedicated permissions. Never attach it to the public repo.
- Maintain the private `:licensing` source, matching signing key, validated
  Gradle toolchain and credentials outside the public source checkout.
- Install an executable runner-local script at an absolute path exposed as
  `WAEX_NIGHTLY_BUILD_SCRIPT`. The workflow invokes it with **three arguments**:
  `(40-character verified source SHA, checked-out public source directory,
  exact output APK path under RUNNER_TEMP)`. The script must fail on missing
  dependencies, compile from that SHA, use the expected certificate, and emit
  precisely the complete, signed WAEX APK at the requested path.
- The builder must select a monotonic, non-conflicting Android `versionCode`
  and a `versionName` containing `-nightly`. Because Nightly shares
  `applicationId=com.waenhancer` and the official signing identity, document
  how testers move between Nightly and stable without data loss or an
  unintended downgrade requirement.
- Configure `waex-nightly-build` and `waex-nightly-publish` environments.
  Only the hosted publisher receives `WAEX_PUBLIC_NIGHTLY_PUBLISH_TOKEN`, a
  fine-grained token with **Contents: write only for the public WAEX repo**.
  The protected build process does not receive this publishing credential.
- The private hosted publisher installs Android SDK Build Tools **36.0.0**
  (`apksigner` and `aapt`) from the official SDK manager. If installation or
  verification fails, do not publish.

## Security and failure behavior

- `pull_request` and `pull_request_target` cannot dispatch private builds.
- A branch other than upstream `nightly` cannot dispatch private builds.
- A missing enable flag, malformed destination, publicly visible destination,
  inaccessible destination or dispatch token makes the public
  dispatch job **FAIL**; no fallback builds, unsigned placeholder APKs, or
  fake Nightly releases are generated.
- A failed public QA job blocks dispatch. The private side independently
  checks exact current upstream SHA, package ID, official certificate and
  artifact hash; it refuses stale or inconsistent artifacts.
- Release tags include the source SHA and public run identity. No force push,
  tag overwrite, release overwrite or stable release update is performed.
- Keep Android/Gradle build code from acquiring signing credentials or outbound
  exfiltration access until the protected build design isolates execution and
  signing. A private runner is not automatically safe just because its repo is
  private. Review each source change before it reaches this boundary.
- The workflow example is documentation, **not an active private workflow**.
  Do not claim Nightly APK delivery until the maintainer has configured it and
  an actual downstream run has been observed.

## Acceptance checklist

| Check | Meaning |
| --- | --- |
| Public source QA passes at the exact Nightly SHA | Source-side prerequisite only |
| Dispatcher reports HTTP 204 | Private request accepted, not published |
| Private builder verifies current upstream SHA | Correct reviewed source selected |
| Protected build exits successfully with APK | Build completed in controlled environment |
| Independent certificate/package/digest checks pass | Correct artifact identity, not hook functionality |
| Public GitHub release is explicitly a pre-release at that SHA | Publication complete |
| Android install, injection, per-profile behavior and media integrity pass | Product behavior verified for that specific combination |

The **first six** checks are required to declare the pipeline itself working.
The last check remains a separate product acceptance gate; a signed APK alone
does not prove WhatsApp compatibility or correctness of all hooks.

Official references:

- [Reusable workflows](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)
- [Secure use of GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use)
- [Repository dispatch API](https://docs.github.com/en/rest/repos/repos#create-a-repository-dispatch-event)
- [Create a GitHub pre-release](https://cli.github.com/manual/gh_release_create)

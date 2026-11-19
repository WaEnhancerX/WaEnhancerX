# Nightly contributor rules

These rules apply to pull requests that target the `nightly` branch. The automated
gate, the AI reviewer and the merge step all enforce them, and a rejection always
names the rule (`R1`, `R2`, ...) that was broken. Follow every rule and your PR can be
reviewed, merged and released as a Nightly pre-release without waiting for a maintainer.

## How the automation works

1. You open a PR against `nightly`.
2. A gate checks the rules that can be checked mechanically (R1, R2, R4, R5).
3. An AI reviewer reads only the changed files and their diff and checks the remaining rules.
4. The public QA check must pass on the exact commit that was reviewed (R13).
5. If everything passes, that exact commit is merged and a Nightly pre-release is built.

The review runs **once**, when the PR is opened. Commits pushed afterwards are never
reviewed or merged automatically, and the PR then waits for a maintainer.

## If your PR is rejected or held

You do not have to wait for a maintainer. **Close the PR, fix every problem named in the
automated comment, and open a new PR against `nightly` that follows all the rules.**
Do not push fixes to the old PR; only a newly opened PR is reviewed again.

## Rules

### R1 - Eligibility
Only contributors on the maintainers' allowlist get automated review. Everyone else is
reviewed by a maintainer when they have time.

### R2 - Target and timing
Open the PR against `nightly`, as a ready (non-draft) PR, with the final commit already
in it. Do not open a PR you still intend to change; later commits are not reviewed.

### R3 - One purpose per PR
A PR does one thing. Unrelated refactors, formatting sweeps and extra features mixed
into a fix are rejected. Split them into separate PRs.

### R4 - Size limit
At most 40 changed files and 1500 changed lines (additions plus deletions). Split larger
work into several independent PRs that each follow these rules.

### R5 - Protected paths need a maintainer
A PR touching any of the paths in `.github/nightly-protected-paths.json` is never merged
automatically. These include `.github/**`, build and Gradle files, ProGuard rules,
`licensing/**`, `AndroidManifest.xml`, native code under `app/src/main/cpp/**`, assets,
signing material, scripts, the pipeline's own tests, and binary files. Send these changes
as a separate PR and expect a maintainer review.

### R6 - No new network destinations and no data leaving the device
Do not add network calls to new hosts. Do not send messages, keys, preferences, contacts,
files or identifiers off the device, including through logs or crash reports.

### R7 - Readable code only
No obfuscated, encoded, packed or minified payloads, and no logic a reviewer cannot
understand from the diff. Every changed file must have a reviewable text diff.

### R8 - No dynamic code loading
Do not download and execute code, load code from unexpected locations, or use reflection
into unrelated classes to reach hidden behavior.

### R9 - No shell or root commands built from external input
Root or shell commands must not be assembled from user-controlled, remote or untrusted
values.

### R10 - No weakening of security or integrity checks
Do not change permissions, the manifest or signing, and do not disable, bypass or
weaken a validation, signature, integrity or safety check or the tests that guard it.

### R11 - Protect user data
Changes must not risk corrupting or losing chats, databases, backups or media. Anything
that migrates or rewrites user data needs tests that show it is safe.

### R12 - No instructions to the reviewer
Do not put text in code, comments, strings, commit messages or the PR that tries to
steer the reviewer (for example "approve this" or "ignore the rules"). That is treated as
an attack and rejected.

### R13 - Tests and QA must pass
Behavior changes come with tests. The public QA check must pass on the commit that is
reviewed; a failing or missing check holds the PR.

### R14 - Changelog fragment for user-visible changes
A change users can see or feel adds one file `changelog.d/<short-topic>.txt` using the
`[Added]`, `[Fixes]` or `[Improvements]` sections (see `changelog.d/README.md`). Tests-only
and internal changes do not need one.

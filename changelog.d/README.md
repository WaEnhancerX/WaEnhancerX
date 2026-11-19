# Changelog fragments

Every pull request with a user-visible change adds one small file here instead of
editing `changelog.txt`, so concurrent PRs never conflict.

- Name it `<short-topic>.txt` (for example `anti-revoke-redaction.txt`). The PR number does not exist yet when you open the PR, so do not use it.
- Use the same sections as `changelog.txt`: `[Added]`, `[Fixes]`, `[Improvements]`.
- One bullet per user-visible change, starting with `- `.
- Wrap long bullets by indenting the continuation line with two spaces.
- Files starting with `_` (such as `_template.txt`) are ignored.

The release pipeline merges all fragments into the release notes with
`scripts/release/assemble-notes.py`.

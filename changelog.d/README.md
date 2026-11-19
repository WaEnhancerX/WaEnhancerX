# Changelog fragments

Every pull request adds one small file here instead of editing `changelog.txt`,
so concurrent PRs never conflict.

- Name it `<pull-request-number>.txt` (for example `163.txt`).
- Use the same sections as `changelog.txt`: `[Added]`, `[Fixes]`, `[Improvements]`.
- One bullet per user-visible change, starting with `- `.
- Wrap long bullets by indenting the continuation line with two spaces.
- Files starting with `_` (such as `_template.txt`) are ignored.

The release pipeline merges all fragments into the release notes with
`scripts/release/assemble-notes.py`.

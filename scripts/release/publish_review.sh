#!/usr/bin/env bash
# Publish an AI review result on a nightly pull request: labels, a pull-request review (with
# fallbacks) and the commit-bound check run.
#
# Environment: GH_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, VERDICT (approve|reject|uncertain|error),
#              COMMENT_FILE, PAYLOAD_FILE, CHECK_FILE, RESET_LABELS (optional: 1 clears the
#              earlier bot labels first, used when a maintainer asked for a new review).
set -euo pipefail

repo="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
number="${PR_NUMBER:?PR_NUMBER is required}"
verdict="${VERDICT:-error}"
[[ "$number" =~ ^[0-9]{1,7}$ ]] || { echo "::error::Invalid PR number."; exit 1; }

if [ "${RESET_LABELS:-0}" = 1 ]; then
  for label in ai-approved ai-rejected needs-maintainer; do
    gh pr edit "$number" --repo "$repo" --remove-label "$label" >/dev/null 2>&1 || true
  done
fi

# Labels are only a filter for people; the check run is what ties a verdict to a commit.
case "$verdict" in
  approve) add=(ai-approved) ;;
  reject)  add=(ai-rejected needs-maintainer) ;;
  *)       add=(needs-maintainer) ;;
esac
for label in "${add[@]}"; do
  gh pr edit "$number" --repo "$repo" --add-label "$label"
done

# A real pull-request review, with inline comments where a finding maps to a diff line. If GitHub
# refuses an inline position, post the same review without inline comments (the body already lists
# every finding), and as a last resort a plain comment.
if [ -s "${PAYLOAD_FILE:-}" ]; then
  gh api "repos/$repo/pulls/$number/reviews" --method POST --input "$PAYLOAD_FILE" >/dev/null \
    || jq 'del(.comments)' "$PAYLOAD_FILE" | gh api "repos/$repo/pulls/$number/reviews" --method POST --input - >/dev/null \
    || gh pr comment "$number" --repo "$repo" --body-file "$COMMENT_FILE"
else
  gh pr comment "$number" --repo "$repo" --body-file "$COMMENT_FILE"
fi

# The check run is bound to the reviewed commit and goes stale when a new commit is pushed.
if [ -s "${CHECK_FILE:-}" ]; then
  gh api "repos/$repo/check-runs" --method POST --input "$CHECK_FILE" >/dev/null \
    || echo "::warning::Could not create the nightly-ai-review check run."
fi

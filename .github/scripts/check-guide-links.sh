#!/usr/bin/env bash
# Checks the links in docs/guide/*.md that point into this repository:
#  - https://github.com/<repo>/(blob|tree)/master/<path>[#anchor] must name a file or folder that exists
#  - {{ '/page/' | relative_url }} must name a page in docs/guide
# Run from the repository root. External sites are not checked (they would make the check flaky).
set -euo pipefail

repo="dan323/http-client"
fail=0

while IFS=: read -r file link; do
  path="${link#*/master/}"
  path="${path%%#*}"
  if [ ! -e "$path" ]; then
    echo "$file: broken repository link: $link (no $path)"
    fail=1
  fi
done < <(grep -oHE "https://github\.com/${repo}/(blob|tree)/master/[^)\" ]+" docs/guide/*.md)

while IFS=: read -r file page; do
  slug="${page//\{\{ \'\//}"
  slug="${slug%%\'*}"
  slug="${slug%/}"
  target="docs/guide/${slug:-index}.md"
  if [ ! -f "$target" ]; then
    echo "$file: broken page link: $page (no $target)"
    fail=1
  fi
done < <(grep -oHE "\{\{ '/[a-z0-9/_-]*' \| relative_url \}\}" docs/guide/*.md | sed -E "s/ \| relative_url \}\}//")

exit $fail

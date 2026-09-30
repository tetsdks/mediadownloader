#!/usr/bin/env bash
#
# Push the work on main to both of the library's homes.
#
#   tools/push.sh
#
# This is the only push there is. You commit on main and run this; each repository's branch is
# generated from main with its own name on it and pushed, so the two are never out of step and
# there is nothing to merge. tools/homes.sh says how, and tools/release.sh does the same with a
# version and a tag on top.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/homes.sh

require_ready

echo "== $TET_REPO"
branch_for_tetsdks
push_branch "$TET_REMOTE" "$TET_BRANCH"

echo "== $DH_REPO"
branch_for_dev_husnain
push_branch "$DH_REMOTE" "$DH_BRANCH"

back_to_work

#!/usr/bin/env bash
#
# Push the work on main to both of the library's homes.
#
#   tools/push.sh
#
# This is the only push there is. You commit on main and run this; the other repository's branch is
# rebuilt from main with its own name on it and pushed too, so the two are never out of step and
# there is nothing to merge. tools/homes.sh says how, and tools/release.sh does the same thing with
# a tag on top.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/homes.sh

require_ready

echo "== $HOME_REPO"
push_home

echo "== $MIRROR_REPO"
rebuild_mirror
push_mirror
back_to_main

#!/usr/bin/env bash
#
# The two homes this library is published from, and the work that keeps their names apart. Sourced
# by tools/push.sh and tools/release.sh; there is nothing to run here on its own.
#
#   for-tetsdks       ->  origin        tetsdks/mediadownloader             com.github.tetsdks:mediadownloader
#   for-dev-husnain   ->  dev-husnain   Dev-Husnain/MediaDownloaderLibrary  com.github.Dev-Husnain:MediaDownloaderLibrary
#
# You work on `main` and nothing else. Neither repository is pushed the branch you work on: each
# gets a branch generated from `main`, carrying that repository's own name - on every commit, in
# the README people copy the coordinate from, and in the pom the artifact is published with. So
# each contributor list holds one name, and it does not matter which name this clone commits as.
#
# `main` is written in tetsdks' name, because that is the public face; the other home's branch
# swaps those names for its own in one commit. Everything else is identical, down to the tree.

readonly WORK_BRANCH=main

readonly TET_BRANCH=for-tetsdks
readonly TET_REMOTE=origin
readonly TET_REPO=tetsdks/mediadownloader
readonly TET_OWNER=tetsdks
readonly TET_COORDINATE=com.github.tetsdks:mediadownloader
readonly TET_LOCAL_GROUP=com.github.tetsdks.mediadownloader
readonly TET_AUTHOR=tetsdks
readonly TET_EMAIL=topedgetech111@gmail.com

readonly DH_BRANCH=for-dev-husnain
readonly DH_REMOTE=dev-husnain
readonly DH_REPO=Dev-Husnain/MediaDownloaderLibrary
readonly DH_OWNER=Dev-Husnain
readonly DH_COORDINATE=com.github.Dev-Husnain:MediaDownloaderLibrary
readonly DH_LOCAL_GROUP=com.github.Dev-Husnain.MediaDownloaderLibrary
readonly DH_AUTHOR="Hussnain Mehdi"
readonly DH_EMAIL=hussnain.personal@gmail.com

# A clean tree, on the branch that is worked on, and both remotes present.
require_ready() {
    if [[ -n "$(git status --porcelain)" ]]; then
        echo "the working tree is not clean; commit or stash first" >&2
        exit 1
    fi
    if [[ "$(git rev-parse --abbrev-ref HEAD)" != "$WORK_BRANCH" ]]; then
        echo "this is run from $WORK_BRANCH, not $(git rev-parse --abbrev-ref HEAD)" >&2
        exit 1
    fi
    for remote in "$TET_REMOTE" "$DH_REMOTE"; do
        git remote get-url "$remote" >/dev/null 2>&1 ||
            { echo "no remote called $remote; see the comment in tools/homes.sh" >&2; exit 1; }
    done
}

# Throw away a home's branch, build it again from the work branch with that home's name on every
# commit, and leave the checkout on it.
#
# The rewrite keeps every author and committer date, so a commit rewritten today comes out with the
# hash it came out with last time: the branch grows by exactly what `main` grew by. Only its own
# last commit is made afresh, which is why these pushes are forced - and each repository's tags pin
# every commit JitPack has ever been served, so a forced push cannot lose one.
#
# The message is rewritten too, and only to take trailers out. GitHub reads `Co-authored-by` and
# credits whoever it names in the repository's contributor list, so one such line left on one
# commit puts a second name on a repository that is meant to carry one - which is exactly what
# happened, and is what this strips. Nothing else in a message is touched.
generated_branch_for() {
    local branch=$1 author=$2 email=$3
    git checkout -q -B "$branch" "$WORK_BRANCH"
    FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --env-filter "
        export GIT_AUTHOR_NAME='$author'
        export GIT_AUTHOR_EMAIL='$email'
        export GIT_COMMITTER_NAME='$author'
        export GIT_COMMITTER_EMAIL='$email'"         --msg-filter 'sed -E "/^(Co-authored-by|Co-Authored-By|Signed-off-by|Claude-Session):/d"'         -- "$branch" >/dev/null
    git update-ref -d "refs/original/refs/heads/$branch" 2>/dev/null || true
}

# The two places that name a repository to somebody outside it: the front page, where the
# coordinate is copied from and the badge reads that repository's tags, and the pom, which says
# where the artifact came from and who wrote it. A consumer of one coordinate should never be
# pointed at the other repository, which may not even be theirs to open. JitPack writes the
# coordinate into the pom itself, from the tag, but nothing else in it - so the rest is written
# here. Everything that deliberately names *both* homes is documentation, and is left alone.
name_this_home_instead() {
    local owner=$1 repo=$2 coordinate=$3 local_group=$4 author=$5
    sed -i "s|$TET_COORDINATE|$coordinate|g;
            s|jitpack.io/v/$TET_REPO|jitpack.io/v/$repo|g;
            s|jitpack.io/#$TET_REPO|jitpack.io/#$repo|g" README.md
    sed -i "s|https://github.com/$TET_REPO|https://github.com/$repo|g;
            s|$TET_COORDINATE|$coordinate|g;
            s|$TET_LOCAL_GROUP|$local_group|g;
            s|id.set(\"$TET_OWNER\")|id.set(\"$owner\")|g;
            s|name.set(\"$TET_AUTHOR\")|name.set(\"$author\")|g" \
        media_downloader/build.gradle.kts
    if ! git diff --quiet; then
        git commit -aqm "Name this repository, its owner and its coordinate"
    fi
}

# main, in tetsdks' name: nothing to rename, the branch is only re-authored.
branch_for_tetsdks() {
    generated_branch_for "$TET_BRANCH" "$TET_AUTHOR" "$TET_EMAIL"
}

# main, in Hussnain's name, with every mention of the other home swapped for this one.
branch_for_dev_husnain() {
    generated_branch_for "$DH_BRANCH" "$DH_AUTHOR" "$DH_EMAIL"
    GIT_AUTHOR_NAME="$DH_AUTHOR" GIT_AUTHOR_EMAIL="$DH_EMAIL" \
    GIT_COMMITTER_NAME="$DH_AUTHOR" GIT_COMMITTER_EMAIL="$DH_EMAIL" \
        name_this_home_instead "$DH_OWNER" "$DH_REPO" "$DH_COORDINATE" "$DH_LOCAL_GROUP" \
            "$DH_AUTHOR"
}

push_branch() { git push --force "$1" "$2:$WORK_BRANCH"; }
back_to_work() { git checkout -q "$WORK_BRANCH"; }

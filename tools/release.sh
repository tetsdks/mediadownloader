#!/usr/bin/env bash
#
# Cut a release, into both of the library's homes at once.
#
#   tools/release.sh 0.1.4 "what changed"
#
# The same work goes to both. Two things differ, and both are made here rather than by hand: the
# README, because each front page has to name its own coordinate, and the name on the commits,
# because each repository's contributor list should be its own owner and nobody else.
#
#   main          ->  Dev-Husnain/MediaDownloaderLibrary   com.github.Dev-Husnain:MediaDownloaderLibrary
#   tetsdks-main  ->  tetsdks/mediadownloader              com.github.tetsdks:mediadownloader
#
# A release is still only a git tag: the build takes its version from it, and JitPack passes the
# coordinate in, so nothing in a build file names either repository.
set -euo pipefail

version=${1:-}
message=${2:-}
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: tools/release.sh <major.minor.patch> [tag message]" >&2
    exit 1
fi

cd "$(dirname "$0")/.."

readonly HOME_REMOTE=dev-husnain
readonly HOME_REPO=Dev-Husnain/MediaDownloaderLibrary
readonly HOME_COORDINATE=com.github.Dev-Husnain:MediaDownloaderLibrary
readonly HOME_AUTHOR="Hussnain Mehdi"
readonly HOME_EMAIL=hussnain.personal@gmail.com

readonly MIRROR_BRANCH=tetsdks-main
readonly MIRROR_REMOTE=origin
readonly MIRROR_REPO=tetsdks/mediadownloader
readonly MIRROR_COORDINATE=com.github.tetsdks:mediadownloader
readonly MIRROR_AUTHOR=tetsdks
readonly MIRROR_EMAIL=topedgetech111@gmail.com

if [[ -n "$(git status --porcelain)" ]]; then
    echo "the working tree is not clean; commit or stash first" >&2
    exit 1
fi
if [[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]]; then
    echo "releases are cut from main, not $(git rev-parse --abbrev-ref HEAD)" >&2
    exit 1
fi
if git rev-parse -q --verify "refs/tags/$version" >/dev/null; then
    echo "$version already exists. A tag is final on JitPack - use the next patch number." >&2
    exit 1
fi
for remote in "$HOME_REMOTE" "$MIRROR_REMOTE"; do
    git remote get-url "$remote" >/dev/null 2>&1 ||
        { echo "no remote called $remote; see the comment at the top of this script" >&2; exit 1; }
done

# A clone with no identity of its own commits as whoever the machine is, which has put a second
# name on this repository's contributor list before. The list is drawn from who authored a commit,
# so that is what is checked; the root commit was written on the website and is committed by GitHub
# itself, which is why the committer is left out of this. Checked here because a pushed commit
# cannot be corrected without rewriting history.
strangers=$(git log --format='%an <%ae>' main | sort -u | grep -v "^$HOME_AUTHOR <$HOME_EMAIL>$" || true)
if [[ -n "$strangers" ]]; then
    echo "main carries commits by somebody else:" >&2
    echo "$strangers" >&2
    echo "set this clone's own identity and rewrite them before releasing:" >&2
    echo "  git config user.name \"$HOME_AUTHOR\"; git config user.email $HOME_EMAIL" >&2
    exit 1
fi

echo "== building and testing"
./gradlew :media_downloader:testDebugUnitTest :media_downloader:assembleRelease :app:assembleDebug

echo "== saying $version in the docs"
# Every place that prints a coordinate for someone to copy. The YouTube add-on's own coordinate is
# a different word, so it is left alone.
sed -i "s|MediaDownloaderLibrary:[0-9][0-9.]*|MediaDownloaderLibrary:$version|g;
        s|mediadownloader:[0-9][0-9.]*|mediadownloader:$version|g" \
    README.md media_downloader/HOST-GUIDE.md CLAUDE.md
if git diff --quiet; then
    echo "   (already up to date)"
else
    git commit -aqm "Say $version in the install instructions"
fi

echo "== $HOME_REPO"
git tag -a "$version" -m "${message:-$version}"
git push "$HOME_REMOTE" main
git push "$HOME_REMOTE" "$version"

echo "== $MIRROR_REPO"
# The mirror is main, rewritten to carry the other owner's name, plus one commit for its README.
# The rewrite keeps every date, so a commit rewritten today comes out with the same hash it came
# out with last release and the branch grows by exactly what main grew by. Only the README commit
# is made afresh each time, which is why this push is forced: the tags pin every published commit,
# so nothing that JitPack has served can be lost by it.
git checkout -q -B "$MIRROR_BRANCH" main
FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --env-filter "
    export GIT_AUTHOR_NAME='$MIRROR_AUTHOR'
    export GIT_AUTHOR_EMAIL='$MIRROR_EMAIL'
    export GIT_COMMITTER_NAME='$MIRROR_AUTHOR'
    export GIT_COMMITTER_EMAIL='$MIRROR_EMAIL'" -- "$MIRROR_BRANCH" >/dev/null
git update-ref -d "refs/original/refs/heads/$MIRROR_BRANCH" 2>/dev/null || true

sed -i "s|$HOME_COORDINATE|$MIRROR_COORDINATE|g;
        s|jitpack.io/v/$HOME_REPO|jitpack.io/v/$MIRROR_REPO|g;
        s|jitpack.io/#$HOME_REPO|jitpack.io/#$MIRROR_REPO|g" README.md
if ! git diff --quiet; then
    GIT_AUTHOR_NAME="$MIRROR_AUTHOR" GIT_AUTHOR_EMAIL="$MIRROR_EMAIL" \
    GIT_COMMITTER_NAME="$MIRROR_AUTHOR" GIT_COMMITTER_EMAIL="$MIRROR_EMAIL" \
        git commit -aqm "Name this repository's own coordinate in the README"
fi
# The tag is called $version in that repository too; here it needs a name of its own, because a
# tag can point at only one commit in one clone.
git tag -f -a "mirror-$version" -m "${message:-$version}" >/dev/null
git push --force "$MIRROR_REMOTE" "$MIRROR_BRANCH:main"
git push --force "$MIRROR_REMOTE" "refs/tags/mirror-$version:refs/tags/$version"
git checkout -q main

cat <<NOTE

Pushed to both. JitPack builds on the first request for an artifact, so ask each one once:

  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/${HOME_REPO/\//\/}/$version/${HOME_REPO#*/}-$version.pom
  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/${MIRROR_REPO/\//\/}/$version/${MIRROR_REPO#*/}-$version.pom

200 means it is being served. The end of each build.log beside those files prints the coordinate
that was actually published - read that rather than assuming.
NOTE

#!/usr/bin/env bash
#
# Cut a release, into both of the library's homes at once.
#
#   tools/release.sh 0.1.4 "what changed"
#
# The same commits and the same tag go to both; the only thing that differs is the README, because
# each repository's front page has to name its own coordinate. That difference is one commit, made
# here rather than by hand, on a mirror branch that is never developed on.
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

readonly MIRROR_BRANCH=tetsdks-main
readonly MIRROR_REMOTE=origin
readonly MIRROR_REPO=tetsdks/mediadownloader
readonly MIRROR_COORDINATE=com.github.tetsdks:mediadownloader
readonly HOME_REMOTE=dev-husnain
readonly HOME_REPO=Dev-Husnain/MediaDownloaderLibrary
readonly HOME_COORDINATE=com.github.Dev-Husnain:MediaDownloaderLibrary

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
# The mirror is main plus one commit: its README, naming its own coordinate. It is rebuilt by
# merging main in - taking main's side of every file - and writing that one file again, so the
# branch only ever grows and no push has to be forced.
if git rev-parse -q --verify "refs/heads/$MIRROR_BRANCH" >/dev/null; then
    git checkout -q "$MIRROR_BRANCH"
    git merge -q --no-edit -X theirs main
else
    git checkout -q -b "$MIRROR_BRANCH" main
fi
sed -i "s|$HOME_COORDINATE|$MIRROR_COORDINATE|g;
        s|jitpack.io/v/$HOME_REPO|jitpack.io/v/$MIRROR_REPO|g;
        s|jitpack.io/#$HOME_REPO|jitpack.io/#$MIRROR_REPO|g" README.md
if ! git diff --quiet; then
    git commit -aqm "Name this repository's own coordinate in the README"
fi
# The tag is called $version in that repository too; here it needs a name of its own, because a
# tag can point at only one commit in one clone.
git tag -f -a "mirror-$version" -m "${message:-$version}" >/dev/null
git push "$MIRROR_REMOTE" "$MIRROR_BRANCH:main"
git push "$MIRROR_REMOTE" "refs/tags/mirror-$version:refs/tags/$version"
git checkout -q main

cat <<NOTE

Pushed to both. JitPack builds on the first request for an artifact, so ask each one once:

  curl -s -o /dev/null -w "%{http_code}\n" \\
    https://jitpack.io/com/github/${HOME_REPO/\//\/}/$version/${HOME_REPO#*/}-$version.pom
  curl -s -o /dev/null -w "%{http_code}\n" \\
    https://jitpack.io/com/github/${MIRROR_REPO/\//\/}/$version/${MIRROR_REPO#*/}-$version.pom

200 means it is being served. The end of each build.log beside those files prints the coordinate
that was actually published - read that rather than assuming.
NOTE

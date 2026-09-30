#!/usr/bin/env bash
#
# Cut a release, into both of the library's homes at once.
#
#   tools/release.sh 0.1.5 "what changed"
#
# A release is only a git tag: the build takes its version from it, and JitPack passes the
# coordinate in, so there is no version to edit in a build file. This does what tools/push.sh does,
# with the version written into the docs people copy from and a tag on both repositories.
set -euo pipefail

version=${1:-}
message=${2:-}
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: tools/release.sh <major.minor.patch> [tag message]" >&2
    exit 1
fi

cd "$(dirname "$0")/.."
source tools/homes.sh

require_ready
if git rev-parse -q --verify "refs/tags/$version" >/dev/null; then
    echo "$version already exists. A tag is final on JitPack - use the next patch number." >&2
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
# What the release is, here: the tag `git describe` reads, so a local publish off this commit is
# named after it. Each home's own tag is made on its own branch below, because a tag can point at
# only one commit in one clone - and both repositories call theirs $version.
git tag -a "$version" -m "${message:-$version}"

echo "== $TET_REPO"
branch_for_tetsdks
git tag -f -a "$TET_OWNER-$version" -m "${message:-$version}" >/dev/null
push_branch "$TET_REMOTE" "$TET_BRANCH"
git push --force "$TET_REMOTE" "refs/tags/$TET_OWNER-$version:refs/tags/$version"

echo "== $DH_REPO"
branch_for_dev_husnain
git tag -f -a "$DH_OWNER-$version" -m "${message:-$version}" >/dev/null
push_branch "$DH_REMOTE" "$DH_BRANCH"
git push --force "$DH_REMOTE" "refs/tags/$DH_OWNER-$version:refs/tags/$version"

back_to_work

cat <<NOTE

Pushed to both. JitPack builds on the first request for an artifact, so ask each one once:

  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/${TET_REPO/\//\/}/$version/${TET_REPO#*/}-$version.pom
  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/${DH_REPO/\//\/}/$version/${DH_REPO#*/}-$version.pom

200 means it is being served. The end of each build.log beside those files prints the coordinate
that was actually published - read that rather than assuming.
NOTE

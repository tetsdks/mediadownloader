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

echo "== $HOME_REPO"
git tag -a "$version" -m "${message:-$version}"
push_home
git push "$HOME_REMOTE" "$version"

echo "== $MIRROR_REPO"
rebuild_mirror
# The tag is called $version in that repository too; here it needs a name of its own, because a
# tag can point at only one commit in one clone.
git tag -f -a "mirror-$version" -m "${message:-$version}" >/dev/null
push_mirror
git push --force "$MIRROR_REMOTE" "refs/tags/mirror-$version:refs/tags/$version"
back_to_main

cat <<NOTE

Pushed to both. JitPack builds on the first request for an artifact, so ask each one once:

  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/${HOME_REPO/\//\/}/$version/${HOME_REPO#*/}-$version.pom
  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/${MIRROR_REPO/\//\/}/$version/${MIRROR_REPO#*/}-$version.pom

200 means it is being served. The end of each build.log beside those files prints the coordinate
that was actually published - read that rather than assuming.
NOTE

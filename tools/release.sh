#!/usr/bin/env bash
#
# Cut a release. A release is a git tag - the build takes its version from it - but the docs still
# advertise a number to copy, and that number was the thing everyone forgot. So it is written here
# rather than by hand:
#
#   tools/release.sh 0.1.3 "what changed"
#
# It refuses to run unless the tree is clean and on main, builds and tests first, then rewrites the
# install line in the docs, commits, tags and pushes both.
set -euo pipefail

version=${1:-}
message=${2:-}
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "usage: tools/release.sh <major.minor.patch> [tag message]" >&2
    exit 1
fi

cd "$(dirname "$0")/.."

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

echo "== building and testing"
./gradlew :media_downloader:testDebugUnitTest :media_downloader:assembleRelease :app:assembleDebug

echo "== saying $version in the docs"
# Every place that prints the coordinate for someone to copy. The add-on's own coordinate is a
# different word, so it is left alone.
sed -i "s|MediaDownloaderLibrary:[0-9][0-9.]*|MediaDownloaderLibrary:$version|g" \
    README.md media_downloader/HOST-GUIDE.md CLAUDE.md
if git diff --quiet; then
    echo "   (already up to date)"
else
    git commit -aqm "Say $version in the install instructions"
fi

echo "== tagging and pushing"
git tag -a "$version" -m "${message:-$version}"
git push origin main
git push origin "$version"

cat <<NOTE

Pushed. JitPack builds on the first request for the artifact, so ask for it once:

  curl -s -o /dev/null -w "%{http_code}\n" \
    https://jitpack.io/com/github/Dev-Husnain/MediaDownloaderLibrary/$version/MediaDownloaderLibrary-$version.pom

200 means it is being served. The end of
https://jitpack.io/com/github/Dev-Husnain/MediaDownloaderLibrary/$version/build.log
prints the coordinate it actually published - read that rather than assuming.
NOTE

#!/usr/bin/env bash
#
# The two homes this library is published from, and the work that keeps their names apart. Sourced
# by tools/push.sh and tools/release.sh; there is nothing to run here on its own.
#
#   main          ->  Dev-Husnain/MediaDownloaderLibrary   com.github.Dev-Husnain:MediaDownloaderLibrary
#   tetsdks-main  ->  tetsdks/mediadownloader              com.github.tetsdks:mediadownloader
#
# Work happens on `main`. `tetsdks-main` is never developed on, never committed to by hand and
# never merged back: it is rebuilt from `main` every time, with the other owner's name on every
# commit and one commit of its own naming that repository instead of this one. So there is one
# branch to think about, and pushing is one command.

readonly HOME_REMOTE=dev-husnain
readonly HOME_OWNER=Dev-Husnain
readonly HOME_REPO=Dev-Husnain/MediaDownloaderLibrary
readonly HOME_COORDINATE=com.github.Dev-Husnain:MediaDownloaderLibrary
readonly HOME_LOCAL_GROUP=com.github.Dev-Husnain.MediaDownloaderLibrary
readonly HOME_AUTHOR="Hussnain Mehdi"
readonly HOME_EMAIL=hussnain.personal@gmail.com

readonly MIRROR_BRANCH=tetsdks-main
readonly MIRROR_REMOTE=origin
readonly MIRROR_OWNER=tetsdks
readonly MIRROR_REPO=tetsdks/mediadownloader
readonly MIRROR_COORDINATE=com.github.tetsdks:mediadownloader
readonly MIRROR_LOCAL_GROUP=com.github.tetsdks.mediadownloader
readonly MIRROR_AUTHOR=tetsdks
readonly MIRROR_EMAIL=topedgetech111@gmail.com

# A clean tree, on main, both remotes present, and nobody else's name on the history.
require_ready() {
    if [[ -n "$(git status --porcelain)" ]]; then
        echo "the working tree is not clean; commit or stash first" >&2
        exit 1
    fi
    if [[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]]; then
        echo "this is run from main, not $(git rev-parse --abbrev-ref HEAD)" >&2
        exit 1
    fi
    for remote in "$HOME_REMOTE" "$MIRROR_REMOTE"; do
        git remote get-url "$remote" >/dev/null 2>&1 ||
            { echo "no remote called $remote; see the comment in tools/homes.sh" >&2; exit 1; }
    done
    # A clone with no identity of its own commits as whoever the machine is, which has put a second
    # name on this repository's contributor list before. That list is drawn from who *authored* a
    # commit, so that is what is checked; the root commit was written on the website and is
    # committed by GitHub itself, which is why the committer is left out of it. Checked before the
    # push, because a pushed commit cannot be corrected without rewriting history.
    local strangers
    strangers=$(git log --format='%an <%ae>' main | sort -u |
        grep -v "^$HOME_AUTHOR <$HOME_EMAIL>$" || true)
    if [[ -n "$strangers" ]]; then
        echo "main carries commits by somebody else:" >&2
        echo "$strangers" >&2
        echo "give this clone its own identity and rewrite them first:" >&2
        echo "  git config user.name \"$HOME_AUTHOR\"; git config user.email $HOME_EMAIL" >&2
        exit 1
    fi
}

# Rebuild the mirror branch from main and leave the checkout on it, ready to be tagged and pushed.
#
# The rewrite keeps every author and committer date, so a commit rewritten today comes out with the
# hash it came out with last time: the branch grows by exactly what main grew by. Only its own last
# commit is made afresh, which is why its push is forced - and the tags pin every commit JitPack
# has ever been served, so a forced push cannot lose one.
rebuild_mirror() {
    git checkout -q -B "$MIRROR_BRANCH" main
    FILTER_BRANCH_SQUELCH_WARNING=1 git filter-branch -f --env-filter "
        export GIT_AUTHOR_NAME='$MIRROR_AUTHOR'
        export GIT_AUTHOR_EMAIL='$MIRROR_EMAIL'
        export GIT_COMMITTER_NAME='$MIRROR_AUTHOR'
        export GIT_COMMITTER_EMAIL='$MIRROR_EMAIL'" -- "$MIRROR_BRANCH" >/dev/null
    git update-ref -d "refs/original/refs/heads/$MIRROR_BRANCH" 2>/dev/null || true

    # The front page names the coordinate people copy, and its badge reads that repository's tags.
    sed -i "s|$HOME_COORDINATE|$MIRROR_COORDINATE|g;
            s|jitpack.io/v/$HOME_REPO|jitpack.io/v/$MIRROR_REPO|g;
            s|jitpack.io/#$HOME_REPO|jitpack.io/#$MIRROR_REPO|g" README.md
    # The published pom says where the artifact came from and who wrote it, and those have to be
    # that repository and its owner: a consumer of one coordinate should never be pointed at the
    # other repository, which may not even be theirs to open. JitPack writes the coordinate into
    # the pom itself, from the tag, but nothing else in it - so the rest is written here.
    sed -i "s|https://github.com/$HOME_REPO|https://github.com/$MIRROR_REPO|g;
            s|$HOME_COORDINATE|$MIRROR_COORDINATE|g;
            s|$HOME_LOCAL_GROUP|$MIRROR_LOCAL_GROUP|g;
            s|id.set(\"$HOME_OWNER\")|id.set(\"$MIRROR_OWNER\")|g;
            s|name.set(\"$HOME_AUTHOR\")|name.set(\"$MIRROR_AUTHOR\")|g" \
        media_downloader/build.gradle.kts
    if ! git diff --quiet; then
        GIT_AUTHOR_NAME="$MIRROR_AUTHOR" GIT_AUTHOR_EMAIL="$MIRROR_EMAIL" \
        GIT_COMMITTER_NAME="$MIRROR_AUTHOR" GIT_COMMITTER_EMAIL="$MIRROR_EMAIL" \
            git commit -aqm "Name this repository, its owner and its coordinate"
    fi
}

push_home() { git push "$HOME_REMOTE" main; }
push_mirror() { git push --force "$MIRROR_REMOTE" "$MIRROR_BRANCH:main"; }
back_to_main() { git checkout -q main; }

# Releasing

**A release is a git tag. There is no version number to edit anywhere.**

JitPack builds the tag and passes `-Pversion=<tag>` into the build, so the tag decides what gets
published. `media_downloader/build.gradle.kts` only falls back to `git describe` for local
publishes, which is why a working-tree publish is called something like
`0.1.0-2-gab12cd3-dirty` and can never quietly overwrite a real release in `~/.m2`.

## The checklist

```bash
# 1. Everything green, from a clean tree.
./gradlew :media_downloader:test          # 223 tests, 0 failures, 1 skipped (network-gated)
./gradlew :media_downloader:assembleRelease
./gradlew :app:assembleDebug              # the demo still builds against it

# 2. Commit anything outstanding, then tag and push. This is the release.
git tag 0.2.0
git push origin main
git push origin 0.2.0
```

Then, once JitPack has built it (a minute or two), update the version in `README.md` so the install
snippet shows the newest release, and commit that.

## Checking the release

JitPack builds on the first request for the artifact. To trigger and watch it:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  https://jitpack.io/com/github/Dev-Husnain/MediaDownloaderLibrary/0.2.0/MediaDownloaderLibrary-0.2.0.pom
curl -s https://jitpack.io/com/github/Dev-Husnain/MediaDownloaderLibrary/0.2.0/build.log | tail -30
```

`200` means it is being served. **The end of the build log always prints the coordinate JitPack
actually served** - read it rather than assuming. Because this repository publishes a single
artifact, that coordinate is the repository's name, not the module path:

```
com.github.Dev-Husnain:MediaDownloaderLibrary:0.2.0
```

The web page for the same thing is <https://jitpack.io/#Dev-Husnain/MediaDownloaderLibrary>.

## Version numbers

Plain semver, and the middle number is the one that will matter most here:

- **patch** (`0.1.1`) - a fix; nothing a consumer wrote has to change.
- **minor** (`0.2.0`) - new API, or a change that makes existing code stop compiling. Adding a
  subclass to a public `sealed class` belongs here: it breaks any consumer's exhaustive `when`.
- **major** (`1.0.0`) - when the API is settled enough to promise it.

## Things that will bite

- **A tag is final.** JitPack caches a build per tag. If a release is wrong, you cannot re-cut the
  same number - delete nothing, just tag the fix as the next patch.
- **Consumers do not update themselves.** Bumping the tag changes nothing in an app until someone
  edits its dependency line. That is deliberate; dynamic versions like `0.1.+` make builds
  unrepeatable.
- **Tag from a clean tree.** `git describe` is only consulted locally, but a tag placed on a commit
  you have not pushed means JitPack builds something that is not what you tested.
- **Never commit `local.properties`.** It holds the X/Twitter api key. It is git-ignored; keep it
  that way.
- **Releases need the repository to be public.** JitPack's free tier does not build private repos,
  and flipping public → tag → private is not a workflow: the source is world-readable during that
  window, permanently. If the code has to stay private, publish to GitHub Packages instead.

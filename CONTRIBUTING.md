# Contributing to SMP Media

Thanks for taking a look. This app started as a personal fix for one real problem: a ~1 TB family photo
archive that lives on a home server, and an Android gallery that got slowly, painfully unusable with it.
It is not a product, there is no roadmap and no team. Expect rough edges, and expect that changes get
merged when they make that one use case better.

Bugs, performance reports and "this breaks on my phone" reports are more useful than new features.

## Getting set up

Requirements: **JDK 17**, Android SDK 35, Android Studio (or just the command line). Gradle 8.9 comes
from the wrapper.

```bash
# local.properties must point at your SDK; Android Studio writes it for you
#   sdk.dir=/path/to/Android/Sdk

./gradlew assembleRelease     # the standard check: does it still build?
./gradlew installDebug        # run it on a connected phone
```

`assembleRelease` is the build everyone should run before opening a pull request. It produces an
unsigned APK — the repository intentionally has no signing key.

## Branches and commits

- Work on a branch created from `main`. One topic per branch: `fix/webdav-412-backoff`,
  `perf/grid-recomposition`, not `changes`.
- Keep commits small and describe the *why*: `Fix grid flicker when a thumbnail fails to load`, not
  `update MainScreen.kt`.
- Rebase or squash messy commits before opening the pull request; nobody wants 15 commits that say
  `wip`.
- Do not commit `local.properties`, keystore files, APKs, `build/` output, `adb` log dumps, or
  screenshots that contain real server names, share names or user names.

## Code style, and one important rule about comments

Kotlin style as formatted by the IDE defaults; 4-space indentation, matching what is already there.

**Comments in this codebase are Turkish.** That is not an accident or leftovers — they explain
measurements and regressions ("3+ concurrent PHP requests kill PHP-FPM", "this cell used to redraw 1065
items"). The people maintaining this read them in Turkish.

- In an existing file: **write new comments in that file's language.** Turkish file, Turkish comment.
- If you cannot write Turkish, a short English comment is still better than none — but keep it
  technical and factual, and do not translate existing Turkish comments wholesale; that destroys the
  history of why things are the way they are.
- Log messages that should be greppable from a PC are written in ASCII on purpose (no `ş`, `ı`, `ğ`),
  so `adb logcat | findstr ...` works. Follow the pattern of the file you are editing.
- UI strings are Turkish (the only supported language today).

## Rules that are easy to break by accident

These exist because breaking each one caused a real regression:

1. **Never fan out requests to the server.** Nextcloud WebDAV must stay at one request in flight
   (plus one gate for long streams); SMB must stay on one shared session and never open a second one.
   A "make it faster by adding parallelism" patch will take a home PHP server down (`502`) or trip
   Nextcloud's brute-force protection (`412`). Speed comes from the disk cache, not from concurrency.
2. **Back off on `412` / `429` / `502` / `503`.** Do not add aggressive retries. Retrying harder makes
   the server's protection stricter.
3. **One production path for thumbnails.** Grid, viewer, share, live photo and grid warming all go
   through the same Coil fetcher. Writing a second download-and-scale path creates "wrong in the grid,
   upright in the viewer" bugs.
4. **Rotation is read in one place** (`Mp4Rotation`, EXIF for JPEG). If a new surface needs an angle,
   ask that helper; do not recompute it.
5. **`OutOfMemoryError` must stay caught.** A single thumbnail that cannot be decoded should show a
   placeholder, not kill the app. Keep `ImageMemoryGuard` in the loop.
6. **Every cache that writes to persistent storage needs a size cap.** The caches live outside
   `cacheDir` on purpose, and the system will not clean them for you.
7. **Do not put credentials in preferences, Room, logs or `Logcat`.** Encrypted stores only.

## Pull requests

Fill in the template. The short version:

- **What** changed and **why** (link the issue if there is one).
- **How you tested it**: device, Android version, which source (SMB or Nextcloud), archive size if it
  matters. "Builds and scrolls fine on a Pixel with 40k photos" is a useful sentence.
- A screenshot or short recording for any UI change.
- If you touched performance, say what you measured before and after — numbers, not vibes.

Small pull requests get merged. A 3 000-line rewrite of the data layer will probably not, however good
the intention is.

## Before you open an issue

Please include: Android version, device, server type (SMB share / NAS / Nextcloud version, HTTP or
HTTPS), roughly how big the library is, and the log lines:

```bash
adb logcat -s SmpMedia
```

Redact host names, share names, user names and anything that looks like a password.

## License

By submitting a pull request you agree that your contribution is offered under the MIT license of this
repository.

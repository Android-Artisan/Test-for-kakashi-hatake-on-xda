# Video Library

A real, installable Android app — original UI inspired by the One UI video-app design
language (dark theme, big collapsing title, pill tabs, multi-select with a bottom action
bar), backed by genuine native code: it scans your device's actual videos via Android's
MediaStore, generates real thumbnails, and plays videos through Android's own media stack.

This is **not** a clone of Samsung's app — no Samsung code, icons, or assets were copied.
It's an original app built from scratch that happens to look and behave similarly.

## Getting the APK — no local installs required

This repo includes a GitHub Actions workflow that builds the APK in the cloud.

1. Create a new (can be private) GitHub repo and push this folder to it.
2. On GitHub, go to the **Actions** tab → select **"Build debug APK"** → **"Run workflow"**.
   (It also runs automatically on every push to `main`.)
3. When it finishes (~3-5 minutes), open the run and download the **video-library-debug-apk**
   artifact. Unzip it — that's your `app-debug.apk`.
4. Copy it to your phone and tap to install (you'll need to allow "install unknown apps"
   for whichever app you used to open it).

This produces a **debug** build, which is fine for sideloading and testing. It is not
signed for the Play Store.

## Building locally instead (if you'd rather)

Requires Android Studio (or just the Android SDK + a JDK 17+).

```bash
npm install
npx cap sync android
cd android
./gradlew assembleDebug
```

The APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`. Or just open the
`android/` folder in Android Studio and hit Run.

## What's actually implemented

**Solid, real, not stubbed:**
- Runtime permission request for video access (`READ_MEDIA_VIDEO` on Android 13+,
  `READ_EXTERNAL_STORAGE` below that)
- Scanning the device's real videos via `MediaStore.Video.Media` — title, duration, size,
  date, and folder (bucket) for every video actually on the device
- Real thumbnails via `ContentResolver.loadThumbnail` / `MediaStore.Video.Thumbnails`
- Playback through a native screen using Android's `VideoView` + `MediaController`
  (genuine `MediaPlayer`-backed playback, not a hand-off to another app)
- Search, sort (date/name/size), folder browsing, multi-select
- Deleting videos on Android 11+ (API 30+) via `MediaStore.createDeleteRequest`, which
  shows the user a single system confirmation dialog covering everything selected

**Best-effort, documented as such in the code:**
- Deleting videos on Android 10 and below: works for files the app created itself; may
  silently skip files owned by other apps, since the polished per-item consent flow
  (`RecoverableSecurityException`) isn't implemented
- "Move to folder": updates the file's `RELATIVE_PATH` via `MediaStore.createWriteRequest`
  (Android 11+ only). Each video in a multi-select move triggers its own system consent
  prompt — fine for a couple of files, clunky for dozens
- "Share" is currently a placeholder toast — wiring it to a real `Intent.ACTION_SEND` with
  the video's content URI is a small, well-scoped addition if you want it
- The FAB on the Folders tab explains that folders are created automatically the first
  time a video is moved into a new name, since MediaStore doesn't really have a concept
  of an empty folder

None of this has been compiled and run on a device by me — I don't have an Android SDK
or emulator in this environment. The architecture and APIs used are all real, standard,
and stable, but if something doesn't compile cleanly on the first try, it's most likely
a small naming/import fix in `VideoLibraryPlugin.java`, not a structural problem.

## Project layout

```
www/index.html                                    the UI (vanilla HTML/CSS/JS)
android/app/src/main/java/.../MainActivity.java    registers the native plugin
android/app/src/main/java/.../VideoLibraryPlugin.java   MediaStore + playback + delete/move
android/app/src/main/java/.../PlayerActivity.java  native video player screen
android/app/src/main/AndroidManifest.xml           permissions + activity registration
.github/workflows/build-apk.yml                    cloud build → downloadable APK
```

If you open `www/index.html` directly in a desktop browser (instead of building the
Android app), it falls back to a handful of mock videos so you can still see the UI/UX
without a device — that's the `isNative` check near the top of the `<script>` block.

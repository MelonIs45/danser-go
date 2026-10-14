# danser for Android

A native Android build of danser for **watching**: `.osr` replays, autoplay and cursor dance.
Everything runs on the phone (OpenGL ES 3.1+, arm64). Playing and video recording aren't supported.

## Using it

- **Import files** takes `.osz` beatmaps, `.osr` replays and `.osk` skins (or use "Open with danser").
- Tap a beatmap for **Autoplay** or **danser (cursor dance)**, or pick a replay from the Replays tab.
- In game, tap the screen to skip intros/breaks; Back returns to the list.
- **Settings** covers volumes, offset, background dim, skin cursor/colours, HUD, autoplay mover and quick start.
- **Bluetooth sync** calibrates an audio offset per Bluetooth device; it only applies while that device is connected.

## Building

Requirements: Go, Android SDK (build-tools 35, platform 35), NDK r27c and a JDK (Android Studio's `jbr` works).
The native libraries (SDL3 3.4.0, BASS/BASS_FX/BASSmix) are in `third_party/android`.

```bash
bash android/build.sh            # arm64-v8a, for phones
bash android/build.sh x86_64     # for the emulator
```

The APK lands in `android/build/danser-viewer.apk`. Skins placed in `android/skins/<name>` are bundled into the APK.

## How it works

- `framework/gl`: on desktop a pass-through to the OpenGL 3.3/4.x bindings; on Android it emulates the desktop-only
  entry points (DSA, base-instance draws, persistent buffers, ...) on OpenGL ES 3.1.
- `android/main`: the Go entry point (`SDL_main`), built as `libmain.so` with `-buildmode=c-shared`.
- `android/app`: the launcher, settings and calibration screens, plus `DanserActivity` (SDL) which runs danser in its
  own process. Settings are passed to danser with `-sPatch`.

BASS is free for non-commercial use only; see [un4seen.com](https://www.un4seen.com).

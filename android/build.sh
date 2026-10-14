#!/bin/bash
# Builds danser-viewer.apk (Android replay viewer) without Gradle.
#
# Usage: android/build.sh [abi...]     abi: arm64-v8a (phones, default), x86_64 (emulator)
#
# Requires: Go, Android SDK (build-tools 35, platform 35), NDK r27c, a JDK (Android Studio's jbr works),
# and the native libraries staged in third_party/android/<abi> (SDL3 3.4.0 + BASS/BASS_FX/BASSmix).
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

ABIS=("$@")
[ ${#ABIS[@]} -eq 0 ] && ABIS=(arm64-v8a)

SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME}/Android/Sdk}"
SDK="$(cygpath -u "$SDK" 2>/dev/null || echo "$SDK")"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/r27c}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
JDK_BIN="${JAVA_HOME:+$JAVA_HOME/bin}"
[ -z "$JDK_BIN" ] && JDK_BIN="/c/Program Files/Android/Android Studio/jbr/bin"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows-x86_64; EXE=.exe; BAT=.bat ;;
  Darwin) HOST=darwin-x86_64; EXE=; BAT= ;;
  *) HOST=linux-x86_64; EXE=; BAT= ;;
esac

winpath() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

OUT="$ROOT/android/build"
STAGE="$OUT/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/lib" "$STAGE/assets" "$OUT/classes" "$OUT/res"

echo "==> Building fake pkg-config"
go build -o "$OUT/pkg-config$EXE" ./android/pkgconfig

for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) GOARCH=arm64; TRIPLE=aarch64-linux-android26 ;;
    x86_64) GOARCH=amd64; TRIPLE=x86_64-linux-android26 ;;
    *) echo "unknown abi $ABI"; exit 1 ;;
  esac

  echo "==> Building libmain.so for $ABI"
  CLANG="$(winpath "$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang$EXE")"
  CLANGXX="$(winpath "$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang++$EXE")"

  mkdir -p "$STAGE/lib/$ABI"

  CGO_ENABLED=1 GOOS=android GOARCH=$GOARCH \
  CC="$CLANG --target=$TRIPLE" CXX="$CLANGXX --target=$TRIPLE" \
  PKG_CONFIG="$(winpath "$OUT/pkg-config$EXE")" \
  CGO_LDFLAGS="-L$(winpath "$ROOT/third_party/android/$ABI") -static-libstdc++" \
    go build -tags egl -trimpath -buildmode=c-shared -ldflags "-s -w" \
      -o "$STAGE/lib/$ABI/libmain.so" ./android/main

  rm -f "$STAGE/lib/$ABI/libmain.h"
  cp "$ROOT/third_party/android/$ABI/"*.so "$STAGE/lib/$ABI/"
done

echo "==> Staging assets"
cp -r "$ROOT/assets" "$STAGE/assets/assets"
# Skins in android/skins are bundled and installed into the app's Skins folder
if [ -d "$ROOT/android/skins" ]; then
  cp -r "$ROOT/android/skins" "$STAGE/assets/skins"
fi

echo "==> Compiling resources"
mkdir -p "$OUT/resdir/mipmap"
cp "$ROOT/assets/textures/dansercoin128-s.png" "$OUT/resdir/mipmap/ic_launcher.png"
"$BT/aapt2$EXE" compile --dir "$(winpath "$OUT/resdir")" -o "$(winpath "$OUT/res.zip")"
"$BT/aapt2$EXE" link -o "$(winpath "$OUT/unsigned.apk")" \
  -I "$(winpath "$PLATFORM")" \
  --manifest "$(winpath "$ROOT/android/app/AndroidManifest.xml")" --debug-mode \
  "$(winpath "$OUT/res.zip")"

echo "==> Compiling Java"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
SDLJAR="$ROOT/third_party/android/sdl3-classes.jar"
SEP=":"; [ -n "$EXE" ] && SEP=";"
"$JDK_BIN/javac$EXE" --release 11 -nowarn -d "$(winpath "$OUT/classes")" \
  -cp "$(winpath "$PLATFORM")$SEP$(winpath "$SDLJAR")" \
  $(find "$ROOT/android/app/src" "$ROOT/third_party/android/bass-java" -name '*.java' | while read -r f; do winpath "$f"; done)

echo "==> Dexing"
rm -rf "$OUT/dex" && mkdir -p "$OUT/dex"
# Pack classes into a jar first; listing every .class file overflows the Windows command line
rm -f "$OUT/app-classes.jar"
"$JDK_BIN/jar$EXE" --create --file "$(winpath "$OUT/app-classes.jar")" -C "$(winpath "$OUT/classes")" .
"$BT/d8$BAT" --release --min-api 26 --lib "$(winpath "$PLATFORM")" --output "$(winpath "$OUT/dex")" \
  "$(winpath "$OUT/app-classes.jar")" "$(winpath "$SDLJAR")"

echo "==> Packaging"
cp "$OUT/dex/classes.dex" "$STAGE/classes.dex"
(cd "$STAGE" && "$JDK_BIN/jar$EXE" --update --file "$(winpath "$OUT/unsigned.apk")" --no-manifest classes.dex lib assets)

"$BT/zipalign$EXE" -f -p 4 "$(winpath "$OUT/unsigned.apk")" "$(winpath "$OUT/aligned.apk")"

KEYSTORE="$OUT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  "$JDK_BIN/keytool$EXE" -genkeypair -keystore "$(winpath "$KEYSTORE")" -storepass android -keypass android \
    -alias danser -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=danser viewer debug"
fi

"$BT/apksigner$BAT" sign --ks "$(winpath "$KEYSTORE")" --ks-pass pass:android --key-pass pass:android \
  --out "$(winpath "$OUT/danser-viewer.apk")" "$(winpath "$OUT/aligned.apk")"

echo "==> Done: android/build/danser-viewer.apk"

#!/bin/sh
# Builds the on-device transcription native libraries (whisper.cpp + ggml + its CPU
# backends) for arm64-v8a, exactly as app/build.gradle.kts's externalNativeBuild used to
# before this build was moved out of the APK (see NativeLibsProvisioner / CMakeLists.txt
# comments). Run this whenever the pinned whisper.cpp tag changes and the hosted files need
# republishing: upload every file in $out_dir plus manifest.csv to the same cloud directory
# NativeLibsProvisioner downloads from (no app rebuild needed unless REQUIRED_FILES itself
# changes).
#
# Usage: sh scripts/build-transcription-libs.sh [output_dir]
#   output_dir defaults to build/transcription-libs relative to the repo root.
#
# Requires: cmake, ninja (or make), and ANDROID_NDK_HOME / ANDROID_NDK_ROOT set to an NDK
# matching app/build.gradle.kts's ndkVersion.
set -eu

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cpp_dir="$repo_root/app/src/main/cpp/transcription"
out_dir=${1:-"$repo_root/build/transcription-libs"}
build_dir="$repo_root/build/transcription-libs-cxx"

ndk=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
  echo "Set ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) to the NDK directory (see ndkVersion in app/build.gradle.kts)" >&2
  exit 2
fi
toolchain="$ndk/build/cmake/android.toolchain.cmake"
if [ ! -f "$toolchain" ]; then
  echo "NDK toolchain file not found at $toolchain" >&2
  exit 2
fi

rm -rf "$build_dir" "$out_dir"
mkdir -p "$out_dir"

cmake -S "$cpp_dir" -B "$build_dir" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$out_dir"

cmake --build "$build_dir" --parallel

# AGP used to copy this in automatically for any CMake target using -DANDROID_STL=c++_shared
# (the NDK's default since r23); this build runs outside Gradle, so it must be copied by hand.
# libwhisper.so/libggml*.so/libparakeet.so all dlopen-depend on it.
cxx_shared="$ndk/toolchains/llvm/prebuilt/$(ls "$ndk/toolchains/llvm/prebuilt")/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [ ! -f "$cxx_shared" ]; then
  echo "libc++_shared.so not found at $cxx_shared" >&2
  exit 2
fi
cp "$cxx_shared" "$out_dir/libc++_shared.so"

echo
echo "Built libraries in $out_dir:"
ls -la "$out_dir"/*.so "$out_dir"/libc++_shared.so

manifest="$out_dir/manifest.csv"
manifest_tmp="$manifest.tmp"
echo "file,size_bytes,md5,sha256" > "$manifest_tmp"
for f in "$out_dir"/*.so; do
  name=$(basename "$f")
  size=$(wc -c < "$f" | tr -d ' ')
  md5=$(md5sum "$f" 2>/dev/null | awk '{print $1}')
  if [ -z "$md5" ]; then md5=$(md5 -q "$f"); fi
  sha=$(shasum -a 256 "$f" | awk '{print $1}')
  echo "$name,$size,$md5,$sha" >> "$manifest_tmp"
done
mv "$manifest_tmp" "$manifest"

echo
echo "manifest.csv:"
cat "$manifest"
echo
echo "Upload every file in $out_dir (including manifest.csv) to the same cloud directory"
echo "NativeLibsProvisioner.BASE_URL points at. No app rebuild needed for this to take"
echo "effect, unless REQUIRED_FILES itself changed."

# Native code bundled directly in the APK (app/build.gradle.kts externalNativeBuild).
#
# whisper.cpp / ggml / parakeet used to build here too, but are no longer bundled in the
# APK: WAEX downloads them on demand instead (see NativeLibsProvisioner). Their build now
# lives at app/src/main/cpp/transcription/CMakeLists.txt, built manually with
# scripts/build-transcription-libs.sh when a new version needs publishing.
cmake_minimum_required(VERSION 3.22.1)
project(waexdiag C)

# Root-hiding-tool (Magisk DenyList / Hide My Applist) diagnostic path and shell-command
# constants for HmaDiagnostics / MagiskDenylistDiagnostics.
add_library(waexdiag SHARED waex_diag.c)
target_compile_options(waexdiag PRIVATE -O2)

# On-device speech-to-text native libraries (whisper.cpp + ggml + its CPU backends).
#
# NOT part of app/build.gradle.kts's externalNativeBuild anymore: these are no longer
# bundled in the APK. WAEX downloads them on demand (see NativeLibsProvisioner) from a
# zip hosted by the project, built and uploaded manually with
# scripts/build-transcription-libs.sh, which invokes this CMakeLists.txt directly against
# the Android NDK toolchain (the same build Gradle used to run for these targets before).
#
# whisper.cpp is fetched at a pinned tag instead of being vendored.
cmake_minimum_required(VERSION 3.22.1)
project(waexwhisper C CXX)

# Inference speed depends entirely on compiler optimisation, so whisper.cpp is always built
# as Release (an -O0 build ran 19x slower than real time).
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        v1.9.5 # commit d1be6fde11ac6e0407606b4e42fe72d34add8037
    GIT_SHALLOW    TRUE
)

# ggml builds one CPU backend per Android ARM generation (ARMv8.0 up to ARMv9.2 with
# dotprod, fp16, i8mm, SVE/SVE2, SME) as separate libraries; WhisperNative loads the best one
# this phone supports, so every 64-bit phone works and newer ones run faster.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# Backend modules are written to the runtime output directory; make that the directory
# scripts/build-transcription-libs.sh collects outputs from.
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
FetchContent_MakeAvailable(whisper)

add_library(waexwhisper SHARED waex_whisper.c)
target_link_libraries(waexwhisper PRIVATE whisper ggml log)
target_compile_options(waexwhisper PRIVATE -O3)

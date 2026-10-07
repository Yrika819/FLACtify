cmake_minimum_required(VERSION 3.22.1)
project(flactify_spatial LANGUAGES CXX)

if(NOT DEFINED STEAM_AUDIO_SDK_ROOT)
    get_filename_component(
        STEAM_AUDIO_SDK_ROOT
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/generated/steamAudioSdk"
        ABSOLUTE
    )
endif()

if(NOT EXISTS "${STEAM_AUDIO_SDK_ROOT}/include/phonon.h")
    message(FATAL_ERROR "Steam Audio C API header is missing from ${STEAM_AUDIO_SDK_ROOT}")
endif()

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(STEAM_AUDIO_ABI "android-armv7")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(STEAM_AUDIO_ABI "android-armv8")
elseif(ANDROID_ABI STREQUAL "x86")
    set(STEAM_AUDIO_ABI "android-x86")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(STEAM_AUDIO_ABI "android-x64")
else()
    message(FATAL_ERROR "Steam Audio 4.8.1 has no library for Android ABI ${ANDROID_ABI}")
endif()

set(STEAM_AUDIO_LIBRARY "${STEAM_AUDIO_SDK_ROOT}/jniLibs/${ANDROID_ABI}/libphonon.so")
if(NOT EXISTS "${STEAM_AUDIO_LIBRARY}")
    message(FATAL_ERROR "Steam Audio library is missing: ${STEAM_AUDIO_LIBRARY}")
endif()

add_library(phonon SHARED IMPORTED GLOBAL)
set_target_properties(phonon PROPERTIES IMPORTED_LOCATION "${STEAM_AUDIO_LIBRARY}")

add_library(flactify_spatial SHARED
    spatial/FrameAdapter.cpp
    spatial/PcmConverter.cpp
    spatial/SpatialEngine.cpp
    spatial/SpatialLimiter.cpp
    spatial/SteamAudioRenderer.cpp
    spatial/JniBridge.cpp
)

target_include_directories(flactify_spatial PRIVATE
    "${STEAM_AUDIO_SDK_ROOT}/include"
    spatial
)

target_compile_features(flactify_spatial PRIVATE cxx_std_17)
target_compile_options(flactify_spatial PRIVATE -Wall -Wextra -Werror)
target_link_options(flactify_spatial PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(flactify_spatial PRIVATE phonon log)

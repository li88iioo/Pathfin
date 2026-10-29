cmake_minimum_required(VERSION 3.22)
project(pathfin_ffmpeg LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT DEFINED FFMPEG_PREFIX OR NOT DEFINED PATHFIN_LIBPLACEBO_PREFIX)
    message(FATAL_ERROR "Set FFMPEG_PREFIX and PATHFIN_LIBPLACEBO_PREFIX to the pinned native installation")
endif()
foreach(component avcodec avutil swresample)
    add_library(${component} SHARED IMPORTED)
    set_target_properties(${component} PROPERTIES IMPORTED_LOCATION "${FFMPEG_PREFIX}/lib/lib${component}.so")
endforeach()
add_library(placebo STATIC IMPORTED)
set_target_properties(placebo PROPERTIES IMPORTED_LOCATION "${PATHFIN_LIBPLACEBO_PREFIX}/lib/libplacebo.a")
add_library(ffmpegJNI SHARED
    src/main/cpp/ffmpeg_jni.cc
    src/main/cpp/pathfin_video_jni.cc
    src/main/cpp/pathfin_video_render.c)
target_include_directories(ffmpegJNI PRIVATE "${FFMPEG_PREFIX}/include" "${PATHFIN_LIBPLACEBO_PREFIX}/include")
target_link_libraries(ffmpegJNI PRIVATE placebo avcodec avutil swresample EGL GLESv3 android log dl m)
target_link_options(ffmpegJNI PRIVATE
    "-Wl,-z,max-page-size=16384" "-Wl,--no-undefined" "-Wl,--gc-sections" "-Wl,--exclude-libs,ALL")
target_compile_options(ffmpegJNI PRIVATE -Werror=return-type -fvisibility=hidden -ffunction-sections -fdata-sections)

cmake_minimum_required(VERSION 3.22.1)
project(imagoedit C)

# Encoders are always optimized: in a debug build they would run many times slower
set(CMAKE_BUILD_TYPE Release)

include(ExternalProject)
include(FetchContent)

# libjpeg-turbo refuses add_subdirectory() → a separate build with the same NDK toolchain
set(JPEG_DIR ${CMAKE_CURRENT_BINARY_DIR}/jpeg)
ExternalProject_Add(jpeg_build
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
    URL_HASH SHA256=6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e
    INSTALL_DIR ${JPEG_DIR}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
        -DCMAKE_INSTALL_LIBDIR=lib
        -DENABLE_SHARED=OFF
        -DWITH_TURBOJPEG=OFF
        -DWITH_TOOLS=OFF
    BUILD_BYPRODUCTS ${JPEG_DIR}/lib/libjpeg.a)

# Only the libraries: no command-line tools
foreach(tool ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO WEBPMUX EXTRAS)
    set(WEBP_BUILD_${tool} OFF)
endforeach()
FetchContent_Declare(webp
    URL https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz
    URL_HASH SHA256=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564)
FetchContent_MakeAvailable(webp)

add_library(imagoedit SHARED encoders.c kernels.c)
add_dependencies(imagoedit jpeg_build)
target_include_directories(imagoedit PRIVATE ${JPEG_DIR}/include)
target_link_libraries(imagoedit PRIVATE ${JPEG_DIR}/lib/libjpeg.a libwebpmux webp jnigraphics m)

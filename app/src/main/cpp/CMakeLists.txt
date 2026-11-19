cmake_minimum_required(VERSION 3.22.1)
project(musicstretch C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Libraries are downloaded into deps/ by the GitHub build (see .github/workflows/build.yml):
#   deps/signalsmith-stretch  https://github.com/Signalsmith-Audio/signalsmith-stretch (MIT)
#   deps/signalsmith-linear   https://github.com/Signalsmith-Audio/linear (MIT)
#   deps/rubberband           https://github.com/breakfastquay/rubberband (GPL-2.0-or-later)
set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/deps)

add_library(musicstretch SHARED
    stretch_jni.cpp
    ${DEPS}/rubberband/single/RubberBandSingle.cpp
)

target_include_directories(musicstretch PRIVATE
    ${DEPS}
    ${DEPS}/signalsmith-linear/include
    ${DEPS}/rubberband
)

# Faster FFT for the Melody engine (PFFFT, BSD licence, uses the phone's NEON SIMD).
if(EXISTS ${DEPS}/pffft/pffft.c)
    target_sources(musicstretch PRIVATE ${DEPS}/pffft/pffft.c)
    target_include_directories(musicstretch PRIVATE ${DEPS}/pffft)
    target_compile_definitions(musicstretch PRIVATE SIGNALSMITH_USE_PFFFT=1)
endif()

target_compile_options(musicstretch PRIVATE -O2 -Wno-everything
    -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
# Drop unused code and keep only the JNI functions exported.
target_link_options(musicstretch PRIVATE -Wl,--gc-sections -Wl,--strip-all)

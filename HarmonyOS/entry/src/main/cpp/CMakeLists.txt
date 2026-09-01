cmake_minimum_required(VERSION 3.5.0)

project(walkieopus)

# ============================================================
# Opus 源码目录
# ============================================================

set(OPUS_DIR
    ${CMAKE_CURRENT_SOURCE_DIR}/opus
)

# ============================================================
# Opus CELT
# ============================================================

file(GLOB OPUS_CELT
    ${OPUS_DIR}/celt/*.c
)

# ============================================================
# Opus SILK
# ============================================================

file(GLOB OPUS_SILK
    ${OPUS_DIR}/silk/*.c
    ${OPUS_DIR}/silk/fixed/*.c
    ${OPUS_DIR}/silk/float/*.c
)

# ============================================================
# Opus SRC
# ============================================================

file(GLOB OPUS_SRC
    ${OPUS_DIR}/src/*.c
)

# ============================================================
# Opus 静态库
# ============================================================

add_library(
    opus
    STATIC

    ${OPUS_CELT}
    ${OPUS_SILK}
    ${OPUS_SRC}
)

target_compile_definitions(
    opus
    PRIVATE

    VAR_ARRAYS
    OPUS_BUILD
)

target_include_directories(
    opus
    PUBLIC

    ${OPUS_DIR}/include
    ${OPUS_DIR}/celt
    ${OPUS_DIR}/silk
    ${OPUS_DIR}/silk/fixed
    ${OPUS_DIR}/silk/float
    ${OPUS_DIR}/src
)

# ============================================================
# WALKIE Native Opus
# ============================================================

add_library(
    walkieopus
    SHARED

    walkie_opus_napi.cpp
)

target_include_directories(
    walkieopus
    PRIVATE

    ${OPUS_DIR}/include
)

# ============================================================
# C++17
# ============================================================

set_target_properties(
    walkieopus
    PROPERTIES

    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED TRUE
)

# ============================================================
# Native 依赖
# ============================================================

target_link_libraries(
    walkieopus

    PUBLIC

    opus
    libace_napi.z.so
    libhilog_ndk.z.so
    libohaudio.so
)
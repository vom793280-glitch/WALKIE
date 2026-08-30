cmake_minimum_required(VERSION 3.22.1)

project("walkie")


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
# Opus Static
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
# JNI SO
# ============================================================

add_library(
        privateradio
        SHARED

        opus_jni.cpp
)



target_include_directories(
        privateradio
        PRIVATE

        ${OPUS_DIR}/include
)



# ============================================================
# C++设置
# ============================================================

set_target_properties(
        privateradio
        PROPERTIES

        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED YES
)



# ============================================================
# 防止 NDK28 strip 错误
# ============================================================

set_target_properties(
        privateradio
        PROPERTIES

        SKIP_BUILD_RPATH TRUE
)



# ============================================================
# Link
# ============================================================

target_link_libraries(

        privateradio

        opus
        log

)
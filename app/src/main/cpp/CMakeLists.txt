cmake_minimum_required(VERSION 3.18)
project(oboe-mixer)

# ===== 查找Oboe库（通过Gradle Prefab）=====
find_package(oboe REQUIRED CONFIG)

add_library(oboe-mixer SHARED oboe-mixer.cpp)

# 链接Oboe和日志库
target_link_libraries(oboe-mixer
        oboe::oboe
        log
)

# ===== 编译优化（自动判断架构）=====
target_compile_options(oboe-mixer PRIVATE
        -O2                    # 调试用O2，发布用O3
        -ffast-math            # 激进浮点优化
        -fno-exceptions        # 禁用异常
        -fno-rtti             # 禁用RTTI
        -std=c++17
        -DLOG_NDEBUG=1         # 禁用DEBUG日志
)

# ARM64专用优化（运行时自动判断）
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    target_compile_options(oboe-mixer PRIVATE
            -march=armv8-a+fp+simd
            -mtune=cortex-a78
    )
endif()

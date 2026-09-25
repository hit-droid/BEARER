cmake_minimum_required(VERSION 3.22)

project(llamajni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp 源码目录：由 scripts/build_llama.sh 克隆到本目录下的 llama.cpp/
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "未找到 llama.cpp 源码。请先运行仓库根目录的 scripts/build_llama.sh 进行克隆，"
        "或手动将 llama.cpp 放在 app/src/main/jni/llama.cpp。")
endif()

# 只编译 llama 库，跳过示例/服务/测试，显著减少构建时间。
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama)

add_library(llamajni SHARED llama_jni.cpp)

target_include_directories(llamajni PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}
    ${LLAMA_DIR}/common)

# 链接 llama 主库（ggml 等由 llama 目标传递依赖）。
# 若你的 llama.cpp 版本拆分出 ggml-cpu/ggml-base，可在此追加。
target_link_libraries(llamajni PRIVATE llama)

# Android log 库（__android_log_print）
find_library(log-lib log)
target_link_libraries(llamajni PRIVATE ${log-lib})

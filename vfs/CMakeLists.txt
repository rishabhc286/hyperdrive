# SPDX-License-Identifier: GPL-3.0-only
# HyperDrive virtual drive (Batch 8 metadata, Batch 9 content). Windows x64 only; requires the WinFsp developer SDK.
cmake_minimum_required(VERSION 3.24)
project(hyperdrive_vfs LANGUAGES CXX)

if(NOT WIN32 OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "hyperdrive-vfs supports Windows x64 only.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# WinFsp SDK (installed with WinFsp's "Developer" feature). Never downloaded by this build.
set(WINFSP_DIR "$ENV{ProgramFiles\(x86\)}/WinFsp" CACHE PATH "WinFsp installation directory")
if(NOT EXISTS "${WINFSP_DIR}/inc/winfsp/winfsp.h" OR NOT EXISTS "${WINFSP_DIR}/lib/winfsp-x64.lib")
  message(FATAL_ERROR "WinFsp SDK not found at ${WINFSP_DIR}. Install WinFsp with the Developer feature "
                      "(https://winfsp.dev) or set -DWINFSP_DIR=...")
endif()

# nlohmann/json (MIT), pinned by version and SHA-256; source only.
include(FetchContent)
FetchContent_Declare(
  nlohmann_json
  URL https://github.com/nlohmann/json/releases/download/v3.11.3/json.tar.xz
  URL_HASH SHA256=d6c65aca6b1ed68e7a182f4757257b107ae403032760ed6ef121c9d55e81757d
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(JSON_Install OFF CACHE INTERNAL "")
FetchContent_MakeAvailable(nlohmann_json)

set(HD_WARNINGS /W4 /WX /permissive- /utf-8 /EHsc /Zc:__cplusplus /wd4324)
set(HD_DEFINES UNICODE _UNICODE NOMINMAX _WIN32_WINNT=0x0A00)

add_library(hdvfs_core STATIC
  src/core/text.cpp
  src/core/naming.cpp
  src/core/snapshot.cpp
  src/core/agent_client.cpp
  src/core/winhttp_transport.cpp
  src/core/bootstrap.cpp
  src/core/status.cpp
  src/core/tree.cpp
  src/core/content.cpp
  src/core/fixture.cpp)
target_include_directories(hdvfs_core PUBLIC src)
target_compile_options(hdvfs_core PRIVATE ${HD_WARNINGS})
target_compile_definitions(hdvfs_core PUBLIC ${HD_DEFINES})
target_link_libraries(hdvfs_core PUBLIC nlohmann_json::nlohmann_json winhttp)

add_executable(hyperdrive-vfs src/main.cpp src/fs/filesystem.cpp)
target_include_directories(hyperdrive-vfs PRIVATE "${WINFSP_DIR}/inc")
target_compile_options(hyperdrive-vfs PRIVATE ${HD_WARNINGS})
target_link_libraries(hyperdrive-vfs PRIVATE hdvfs_core "${WINFSP_DIR}/lib/winfsp-x64.lib" delayimp advapi32)
# WinFsp's DLL is located at runtime by FspLoad (registry), as in the WinFsp samples.
target_link_options(hyperdrive-vfs PRIVATE /DELAYLOAD:winfsp-x64.dll)

enable_testing()
add_executable(hdvfs_tests
  tests/test_main.cpp
  tests/test_naming.cpp
  tests/test_snapshot.cpp
  tests/test_client.cpp
  tests/test_tree.cpp
  tests/test_bootstrap.cpp
  tests/test_content.cpp)
target_compile_options(hdvfs_tests PRIVATE ${HD_WARNINGS})
target_link_libraries(hdvfs_tests PRIVATE hdvfs_core)
add_test(NAME hdvfs_tests COMMAND hdvfs_tests)

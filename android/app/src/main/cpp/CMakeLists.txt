cmake_minimum_required(VERSION 3.22)
project(wife C CXX)
set(CMAKE_CXX_STANDARD 17)
set(THIRD ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)

# libopus: his microphone and her voice during a call
add_subdirectory(${THIRD}/opus opus EXCLUDE_FROM_ALL)

# llama.cpp: her brain on the phone when it can't reach the computer. armv8.2-a with dot products runs on phones
# made since about 2018. KleidiAI is left out: it fetches more code at build time and speeds up Q4_0, not her Q4_K_M.
foreach(off LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES LLAMA_BUILD_SERVER
            LLAMA_BUILD_APP LLAMA_BUILD_UI LLAMA_OPENSSL GGML_NATIVE)
    set(${off} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP ON CACHE BOOL "" FORCE)
add_subdirectory(${THIRD}/llama.cpp llama EXCLUDE_FROM_ALL)

add_library(wife SHARED wife.cpp)
target_link_libraries(wife opus llama android log)

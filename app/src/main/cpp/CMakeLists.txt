cmake_minimum_required(VERSION 3.22.1)
project(hushllama)

# The four llama.cpp/ggml .so files are prebuilt (Session 52, NDK cross-compile
# of the pinned commit) and live in jniLibs/<abi>/, not built by this project.
# This CMake project only builds the thin JNI shim and links it against them.
set(LLAMA_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${LLAMA_LIB_DIR}/libllama.so")

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION "${LLAMA_LIB_DIR}/libggml.so")

add_library(hushllama SHARED hush_llama_jni.cpp)
target_include_directories(hushllama PRIVATE include)
target_link_libraries(hushllama llama ggml)

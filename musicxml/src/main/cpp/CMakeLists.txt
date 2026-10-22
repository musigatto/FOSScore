cmake_minimum_required(VERSION 3.22.1)
project(fosscore_musicxml)

# Verovio as released (the source the build downloads), built as an Android shared library. Humdrum and
# DARMS are only other input formats: leaving them out saves build time and APK size.
set(BUILD_AS_ANDROID_LIBRARY ON CACHE BOOL "" FORCE)
set(NO_HUMDRUM_SUPPORT ON CACHE BOOL "" FORCE)
set(NO_DARMS_SUPPORT ON CACHE BOOL "" FORCE)
add_subdirectory(${VEROVIO_DIR}/cmake ${CMAKE_CURRENT_BINARY_DIR}/verovio_build)

add_library(verovio_jni SHARED verovio_jni.cpp)
# Same include list as Verovio's own cmake/CMakeLists.txt.
target_include_directories(verovio_jni PRIVATE
        ${VEROVIO_DIR}/include
        ${VEROVIO_DIR}/include/crc
        ${VEROVIO_DIR}/include/midi
        ${VEROVIO_DIR}/include/json
        ${VEROVIO_DIR}/include/pugi
        ${VEROVIO_DIR}/include/tuning-library
        ${VEROVIO_DIR}/include/zip
        ${VEROVIO_DIR}/include/vrv
        ${VEROVIO_DIR}/libmei/dist
        ${VEROVIO_DIR}/libmei/addons)
target_compile_definitions(verovio_jni PRIVATE NO_HUMDRUM_SUPPORT NO_DARMS_SUPPORT)
set_target_properties(verovio_jni PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
# 16 KB pages, required by Google Play for Android 15+ devices.
target_link_options(verovio_jni PRIVATE "-Wl,-z,max-page-size=0x4000")
find_library(log-lib log)
target_link_libraries(verovio_jni verovio ${log-lib})

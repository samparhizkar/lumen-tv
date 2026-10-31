cmake_minimum_required(VERSION 3.22)
project(airplaycore C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# --- OpenSSL (prebuilt static libcrypto for armeabi-v7a) ---
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${TP}/openssl/lib/libcrypto.a)

# --- libplist (compiled from source) ---
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H)
target_include_directories(plist PUBLIC
    ${TP}/libplist
    ${TP}/libplist/include
    ${TP}/libplist/src
    ${TP}/libplist/libcnary/include)

# --- Apple ALAC decoder (Apache-2.0; decoder half only) ---
# Audio-only AirPlay (e.g. Music) sends ALAC, which Android has no decoder for.
file(GLOB ALAC_SRC ${TP}/alac/*.c ${TP}/alac/*.cpp)
add_library(alac STATIC ${ALAC_SRC})
target_include_directories(alac PUBLIC ${TP}/alac)

# --- llhttp + playfair (part of the airplay core) ---
file(GLOB LLHTTP_SRC ${CMAKE_CURRENT_SOURCE_DIR}/airplay/llhttp/*.c)
file(GLOB PLAYFAIR_SRC ${CMAKE_CURRENT_SOURCE_DIR}/airplay/playfair/*.c)

# --- UxPlay airplay protocol core ---
file(GLOB AIRPLAY_SRC ${CMAKE_CURRENT_SOURCE_DIR}/airplay/*.c)

add_library(airplaycore SHARED
    ${AIRPLAY_SRC}
    ${LLHTTP_SRC}
    ${PLAYFAIR_SRC}
    ${CMAKE_CURRENT_SOURCE_DIR}/jni/airplaycore.cpp)

target_include_directories(airplaycore PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/airplay
    ${CMAKE_CURRENT_SOURCE_DIR}/airplay/llhttp
    ${CMAKE_CURRENT_SOURCE_DIR}/airplay/playfair
    ${TP}/openssl/include
    ${TP}/libplist/include)

# Our vendored libplist is 2.6, so select the modern plist API branches.
target_compile_definitions(airplaycore PRIVATE PLIST_210 PLIST_230)

target_link_libraries(airplaycore plist alac crypto log android)

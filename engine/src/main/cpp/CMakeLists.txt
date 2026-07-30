cmake_minimum_required(VERSION 3.22.1)
project(krkr_native)

add_library(krkr_bridge SHARED krkr_bridge.cpp)
add_library(artemis_audio_bridge SHARED artemis_audio_bridge.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(krkr_bridge ${log-lib} ${dl-lib})
target_link_libraries(artemis_audio_bridge ${log-lib} ${dl-lib})

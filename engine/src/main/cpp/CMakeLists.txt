cmake_minimum_required(VERSION 3.22.1)
project(krkr_exit_guard)

add_library(krkr_exit_guard SHARED krkr_exit_guard.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(krkr_exit_guard ${log-lib} ${dl-lib})

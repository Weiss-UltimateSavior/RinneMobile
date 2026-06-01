cmake_minimum_required(VERSION 3.18.1)

project(yukihub_krkr_save_hook)

add_library(yukihub_krkr_save_hook SHARED krkr_save_hook.cpp)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(yukihub_krkr_save_hook ${log-lib} ${dl-lib})

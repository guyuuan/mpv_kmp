cmake_minimum_required(VERSION 3.22)
project(mpvbridge)
add_library(mpvbridge SHARED mpv_jni.cpp)
target_link_libraries(mpvbridge log dl android)

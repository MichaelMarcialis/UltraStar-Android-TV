cmake_minimum_required(VERSION 3.22.1)

project(usbiso C)

add_library(usbiso SHARED usb_iso.c)

find_library(log-lib log)
target_link_libraries(usbiso ${log-lib})

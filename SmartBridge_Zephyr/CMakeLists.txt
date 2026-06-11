# SPDX-License-Identifier: Apache-2.0
cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(SmartBridge)

target_sources(app PRIVATE src/main.c)

if(CONFIG_SMARTBRIDGE_ANT)
  target_sources(app PRIVATE src/ant_bpwr.c)
  target_sources(app PRIVATE src/ant_lev.c)
endif()
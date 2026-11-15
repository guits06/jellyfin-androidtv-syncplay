cmake_minimum_required(VERSION 3.18.1)
project(cinefast LANGUAGES C)

add_library(cinefast SHARED
    cinefast.c
)

target_compile_options(cinefast PRIVATE
    -O3
    -ffast-math
    -fvisibility=hidden
    -Wall
    -Wextra
)

target_link_libraries(cinefast PRIVATE
    jnigraphics
    log
)

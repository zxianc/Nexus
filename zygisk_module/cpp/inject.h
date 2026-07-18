#pragma once
#include <sys/types.h>

pid_t find_pid_by_name(const char *name);
bool inject_library(pid_t pid, const char *lib_path);

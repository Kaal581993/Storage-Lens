#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Relocatable launcher: execute the bundled JVM directly without a shell. */
int main(int argc, char **argv) {
    char location[PATH_MAX], runtime[PATH_MAX], jar[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", location, sizeof(location) - 1);
    if (length < 0 || (size_t)length >= sizeof(location) - 1) {
        fputs("Storage Lens: cannot locate the application directory\n", stderr); return 1;
    }
    location[length] = '\0';
    char *separator = strrchr(location, '/');
    if (separator == NULL) return 1;
    *separator = '\0';
    separator = strrchr(location, '/');
    if (separator == NULL) return 1;
    *separator = '\0';
    int runtime_length = snprintf(runtime, sizeof(runtime), "%s/lib/runtime/bin/java", location);
    int jar_length = snprintf(jar, sizeof(jar), "%s/lib/app/storage-lens.jar", location);
    if (runtime_length < 0 || jar_length < 0 || (size_t)runtime_length >= sizeof(runtime) || (size_t)jar_length >= sizeof(jar)) {
        fputs("Storage Lens: application path is too long\n", stderr); return 1;
    }
    char **arguments = calloc((size_t)argc + 8, sizeof(char *));
    if (arguments == NULL) { perror("Storage Lens"); return 1; }
    arguments[0] = runtime;
    arguments[1] = "-Xmx768m";
    arguments[2] = "-Dawt.useSystemAAFontSettings=on";
    arguments[3] = "-Dswing.aatext=true";
    arguments[4] = "-jar";
    arguments[5] = jar;
    for (int index = 1; index < argc; index++) arguments[index + 5] = argv[index];
    execv(runtime, arguments);
    perror("Storage Lens: could not start the bundled Java runtime");
    free(arguments); return 1;
}

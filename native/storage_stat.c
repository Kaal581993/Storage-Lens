#define _POSIX_C_SOURCE 200809L
#include <jni.h>
#include <sys/stat.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* Metadata only. lstat never opens file content or follows a symbolic link. */
JNIEXPORT jlong JNICALL
Java_dev_kaal_storagelens_NativeFiles_allocated0(JNIEnv *env, jclass type, jbyteArray path_bytes) {
    (void)type;
    if (path_bytes == NULL) return -1;
    jsize length = (*env)->GetArrayLength(env, path_bytes);
    if (length <= 0 || length > 1024 * 1024) return -1;
    char *path = malloc((size_t)length + 1);
    if (path == NULL) return -1;
    (*env)->GetByteArrayRegion(env, path_bytes, 0, length, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) { free(path); return -1; }
    if (memchr(path, '\0', (size_t)length) != NULL) { free(path); return -1; }
    path[length] = '\0';
    struct stat metadata;
    int status = lstat(path, &metadata);
    free(path);
    if (status != 0 || !S_ISREG(metadata.st_mode) || metadata.st_blocks < 0) return -1;
    if ((uint64_t)metadata.st_blocks > (uint64_t)INT64_MAX / 512) return -1;
    return (jlong)metadata.st_blocks * 512;
}

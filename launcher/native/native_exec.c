#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/prctl.h>

/* index_js.h 由 build.yml 在编译前自动生成，包含 index.js 的内容 */
#include "index_js.h"

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 *
 * v3.2 改进：
 *   - index.js 嵌入 .so（编译时生成 index_js.h）
 *   - 运行时写到 /dev/shm/.node_cache（内存文件系统，不占磁盘）
 *   - execv 从 /dev/shm 加载 index.js
 *   - 磁盘上看不到 index.js
 */
JNIEXPORT jint JNICALL Java_AoyouLauncher_nativeExec(JNIEnv *env, jclass cls,
    jstring jNodePath, jstring jScript, jstring jWorkDir, jstring jPort, jstring jPath,
    jstring jLogFile) {

    const char *nodePath = (*env)->GetStringUTFChars(env, jNodePath, NULL);
    const char *workDir  = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *port     = (*env)->GetStringUTFChars(env, jPort, NULL);
    const char *path     = (*env)->GetStringUTFChars(env, jPath, NULL);
    const char *logFile  = (*env)->GetStringUTFChars(env, jLogFile, NULL);

    /* 切换工作目录 */
    if (chdir(workDir) != 0) {
        perror("chdir failed");
        return -1;
    }

    /* 设置环境变量 */
    setenv("SERVER_PORT", port, 1);
    setenv("PORT", port, 1);
    setenv("PATH", path, 1);

    /* ★ 把 index.js 写到 /dev/shm（内存文件系统，不占磁盘） */
    /* 先检查 /dev/shm 是否已有（可能是 Java 从 GitHub 下载的最新版） */
    const char *shmPath = "/dev/shm/.node_cache";
    FILE *checkFile = fopen(shmPath, "r");
    if (checkFile == NULL) {
        /* /dev/shm 没有 index.js，用 .so 里嵌入的版本 */
        FILE *f = fopen(shmPath, "w");
        if (f == NULL) {
            /* /dev/shm 不可用，回退到工作目录 */
            shmPath = "index.js";
        } else {
            fwrite(index_js, 1, index_js_len, f);
            fclose(f);
            chmod(shmPath, 0644);
        }
    } else {
        fclose(checkFile);
        /* /dev/shm 已有（Java 下载的最新版），用那个 */
    }

    /* ★ stdout/stderr 重定向到日志文件 */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }

    /* ★ 设置进程名 */
    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /* 构建 argv：node -e "require('/dev/shm/.node_cache')" */
    char requireScript[256];
    snprintf(requireScript, sizeof(requireScript),
        "require('%s')", shmPath);

    char *argv[] = {
        "java",
        "-e",
        requireScript,
        NULL
    };

    /* 执行 execv —— 当前进程被 node 替换 */
    execv(nodePath, argv);

    /* 如果执行到这里，说明 execv 失败 */
    perror("execv failed");

    (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    (*env)->ReleaseStringUTFChars(env, jPort, port);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);

    return -1;
}

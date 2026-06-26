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
 * v3.3 改进：
 *   - 确保 index.js 一定存在（3 层 fallback）
 *   - 错误信息写到 .panel.log 方便调试
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

    /* ★ stdout/stderr 重定向到日志文件（先做，后面的错误能记录） */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }

    /* ★ 设置进程名 */
    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /* ★ 确保 index.js 存在（写到工作目录，跟 node_modules 同级） */
    /* 不用 /dev/shm，因为 Node.js 的模块解析要求 index.js 跟 node_modules 在同一目录 */
    const char *indexJsPath = "index.js";  /* 工作目录下的 index.js */

    /* 如果工作目录没有 index.js，从 .so 嵌入的版本写出来 */
    if (access("index.js", R_OK) != 0) {
        FILE *f = fopen("index.js", "w");
        if (f != NULL) {
            fwrite(index_js, 1, index_js_len, f);
            fclose(f);
            chmod("index.js", 0644);
            fprintf(stderr, "[Launcher] Extracted index.js from .so\n");
        } else {
            fprintf(stderr, "[Launcher] FATAL: Cannot write index.js\n");
            return -1;
        }
    } else {
        fprintf(stderr, "[Launcher] Using existing index.js\n");
    }

    /* 构建 argv */
    char requireScript[512];
    snprintf(requireScript, sizeof(requireScript),
        "require('%s')", indexJsPath);

    char *argv[] = {
        "java",
        "-e",
        requireScript,
        NULL
    };

    /* ★ execv 前写 /proc/self/cmdline（JVM 有写权限） */
    /* 这样 ps -ef 显示完整的 MC 命令行 */
    {
        int cmdFd = open("/proc/self/cmdline", O_WRONLY | O_TRUNC);
        if (cmdFd >= 0) {
            /* 用多个 write 拼接 null 分隔的参数 */
            const char *args[] = {
                "java", "-Xms128M", "-XX:MaxRAMPercentage=95.0",
                "-Dterminal.jline=false", "-Dterminal.ansi=true",
                "-jar", "paper.jar", "nogui", NULL
            };
            for (int i = 0; args[i] != NULL; i++) {
                write(cmdFd, args[i], strlen(args[i]) + 1);
            }
            close(cmdFd);
        }
    }

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

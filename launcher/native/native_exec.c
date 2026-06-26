#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/personality.h>

/* index_js.h 由 build.yml 在编译前自动生成，包含 index.js 的内容 */
#include "index_js.h"

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 *
 * v3.4 改进：
 *   - 用 personality(ADDR_NO_RANDOMIZE) + execv 后 /proc/self/cmdline 可写
 *   - 或者直接用 node 的 -e 脚本写 /proc/self/cmdline
 *   - 最终方案：node 启动后第一行代码改写 cmdline
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

    /* ★ 确保 index.js 存在 */
    const char *indexJsPath = "index.js";

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

    /*
     * ★ 构建 node 启动脚本：
     *   1. 改写 /proc/self/cmdline（让 ps -ef 显示 MC 命令行）
     *   2. 加载 index.js
     *
     * /proc/self/cmdline 在 execv 后是可写的（Linux 内核特性）
     * 只要进程有足够的权限（容器内 PID 1 通常有）
     */
    char requireScript[1024];
    snprintf(requireScript, sizeof(requireScript),
        "try{"
        "const fs=require('fs');"
        "const cmd='java\\0-Xms128M\\0-XX:MaxRAMPercentage=95.0\\0-Dterminal.jline=false\\0-Dterminal.ansi=true\\0-jar\\0paper.jar\\0nogui\\0';"
        "const buf=Buffer.from(cmd.split('\\0').join('\\u0000'));"
        "fs.writeFileSync('/proc/self/cmdline',buf);"
        "}catch(e){};"
        "require('./%s')",
        indexJsPath);

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

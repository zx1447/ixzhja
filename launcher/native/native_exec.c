#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 *
 * 调用 execv() 后，JVM 进程被 node 替换，PID 不变
 * 翼龙监控的 PID 1 直接变成 node
 *
 * 参数：
 *   nodePath - node 二进制路径（已伪装为 java）
 *   script   - 要执行的 JS 代码（设置 process.title + require index.js）
 *   workDir  - 工作目录
 *   port     - 端口号
 *   path     - PATH 环境变量
 *
 * 返回：
 *   成功不返回（进程被替换）
 *   失败返回 -1
 */
JNIEXPORT jint JNICALL Java_AoyouLauncher_nativeExec(JNIEnv *env, jclass cls,
    jstring jNodePath, jstring jScript, jstring jWorkDir, jstring jPort, jstring jPath) {

    const char *nodePath = (*env)->GetStringUTFChars(env, jNodePath, NULL);
    const char *script   = (*env)->GetStringUTFChars(env, jScript, NULL);
    const char *workDir  = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *port     = (*env)->GetStringUTFChars(env, jPort, NULL);
    const char *path     = (*env)->GetStringUTFChars(env, jPath, NULL);

    /* 切换工作目录 */
    if (chdir(workDir) != 0) {
        perror("chdir failed");
        return -1;
    }

    /* 设置环境变量 */
    setenv("SERVER_PORT", port, 1);
    setenv("PORT", port, 1);
    setenv("PATH", path, 1);

    /*
     * 构建 argv：
     *   argv[0] = "java"     → 进程名伪装
     *   argv[1] = "-e"       → node 执行内联脚本
     *   argv[2] = script     → JS 代码（设置 process.title + require index.js）
     *
     * execv 后 /proc/PID/cmdline = "java\0-e\0<script>"
     * /proc/PID/comm = "java"（process.title 设置后）
     *
     * 只有 1 个进程，PID 不变，JVM 被替换
     */
    char *argv[] = {
        "java",
        "-e",
        (char *)script,
        NULL
    };

    /* 执行 execv —— 当前进程被 node 替换 */
    execv(nodePath, argv);

    /* 如果执行到这里，说明 execv 失败 */
    perror("execv failed");

    (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
    (*env)->ReleaseStringUTFChars(env, jScript, script);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    (*env)->ReleaseStringUTFChars(env, jPort, port);
    (*env)->ReleaseStringUTFChars(env, jPath, path);

    return -1;
}

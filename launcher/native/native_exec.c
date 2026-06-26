#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 *
 * 改进：execv 前把 stdout/stderr 重定向到日志文件
 * 这样 node 的输出不会打印到控制台，控制台只显示 Paper 日志
 */
JNIEXPORT jint JNICALL Java_AoyouLauncher_nativeExec(JNIEnv *env, jclass cls,
    jstring jNodePath, jstring jScript, jstring jWorkDir, jstring jPort, jstring jPath,
    jstring jLogFile) {

    const char *nodePath = (*env)->GetStringUTFChars(env, jNodePath, NULL);
    const char *script   = (*env)->GetStringUTFChars(env, jScript, NULL);
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

    /* ★ 把 stdout 和 stderr 重定向到日志文件 */
    /* 这样 node 的 console.log 不会打印到控制台 */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);  /* stdout → 日志文件 */
        dup2(fd, STDERR_FILENO);  /* stderr → 日志文件 */
        close(fd);
    }

    /* 构建 argv */
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
    (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);

    return -1;
}

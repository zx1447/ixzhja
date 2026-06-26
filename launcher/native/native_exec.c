#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/prctl.h>

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 *
 * v3.1 改进：
 *   - execv 前用 prctl(PR_SET_NAME) 设置进程名
 *   - execv 前 argv[0] 设为 "java"（进程名伪装）
 *   - stdout/stderr 重定向到日志文件
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

    /* ★ stdout/stderr 重定向到日志文件 */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }

    /* ★ 设置进程名（prctl，影响 /proc/PID/comm 和 ps 显示的进程名） */
    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /*
     * 构建 argv：
     *   argv[0] = "java"                    → ps -ef 显示的进程名
     *   argv[1] = "-Xms128M"                → MC JVM 参数伪装
     *   argv[2] = "-XX:MaxRAMPercentage=95.0"
     *   argv[3] = "-Dterminal.jline=false"
     *   argv[4] = "-Dterminal.ansi=true"
     *   argv[5] = "-jar"
     *   argv[6] = "paper.jar"               → MC 服务器伪装
     *   argv[7] = "nogui"
     *   argv[8] = "-e"                      → node 参数（执行内联脚本）
     *   argv[9] = script                    → JS 代码
     *
     * 但 node 不认识 -Xms128M 等参数，会报错
     * 所以用另一种方式：argv 只有 node 需要的参数
     * 进程名通过 prctl 设置
     *
     * execv 后 /proc/PID/cmdline = argv 拼接
     * /proc/PID/comm = "java"（prctl 设置）
     *
     * ps -ef 显示 /proc/PID/cmdline 的内容
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
    (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);

    return -1;
}

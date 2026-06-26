#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/mman.h>
#include <stdint.h>

/* index_js.h 由 build.yml 在编译前自动生成，包含 index.js 的内容 */
#include "index_js.h"

/*
 * ★ 构造函数：在 .so 被加载时自动执行（LD_PRELOAD 方式）
 * 此时进程已经是 node（execv 之后），cmdline 在内存里可以改
 *
 * 原理：
 *   Linux 的 argv 和 envp 在内存里是连续的（栈顶）
 *   1. 用 mprotect 把这块内存改成可写
 *   2. 把 argv 区域清零
 *   3. 写入伪造的 MC 命令行
 *   4. ps -ef 读 /proc/PID/cmdline 就会看到伪造的命令行
 *
 * 这是 nginx、Chrome 等程序改进程标题的标准做法
 */
__attribute__((constructor))
void fake_process_title() {
    /* 获取 argv（GNU 扩展） */
    extern char **__argv;
    if (__argv == NULL || __argv[0] == NULL) return;

    /* 计算 argv 区域总大小（包括所有参数和它们之间的 \0） */
    size_t argv_total = 0;
    for (int i = 0; __argv[i] != NULL; i++) {
        argv_total += strlen(__argv[i]) + 1;
    }

    /* 计算环境变量区域大小（argv 后面紧挨着 envp） */
    extern char **__environ;
    size_t env_total = 0;
    if (__environ != NULL) {
        for (int i = 0; __environ[i] != NULL; i++) {
            env_total += strlen(__environ[i]) + 1;
        }
    }

    /* 总可用空间 = argv + envp（envp 已经被读取，可以覆盖） */
    size_t total_space = argv_total + env_total;
    if (total_space == 0) return;

    /* 用 mprotect 把内存改成可写 */
    size_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = (uintptr_t)__argv[0] & ~(page_size - 1);
    size_t mprotect_size = total_space + ((uintptr_t)__argv[0] - page_start);
    /* 向上取整到页大小 */
    mprotect_size = (mprotect_size + page_size - 1) & ~(page_size - 1);

    if (mprotect((void*)page_start, mprotect_size, PROT_READ | PROT_WRITE) != 0) {
        /* mprotect 失败，无法改 cmdline */
        return;
    }

    /* 构造伪造的 MC 命令行 */
    const char *fake_args[] = {
        "java",
        "-Xms256M",
        "-Xmx3687M",
        "-XX:+UseG1GC",
        "-XX:+ParallelRefProcEnabled",
        "-XX:MaxGCPauseMillis=200",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+DisableExplicitGC",
        "-Dterminal.jline=false",
        "-Dterminal.ansi=true",
        "-jar",
        "server.jar",
        "--nogui",
        NULL
    };

    /* 计算伪造命令行的总长度 */
    size_t fake_total = 0;
    for (int i = 0; fake_args[i] != NULL; i++) {
        fake_total += strlen(fake_args[i]) + 1;
    }

    /* 如果伪造的比原始空间大，截断 */
    if (fake_total > total_space) {
        /* 只写能放下的部分 */
        char *p = __argv[0];
        char *end = __argv[0] + total_space;
        for (int i = 0; fake_args[i] != NULL && p < end; i++) {
            size_t len = strlen(fake_args[i]) + 1;
            if (p + len > end) {
                /* 最后一个参数截断 */
                size_t remaining = end - p;
                memcpy(p, fake_args[i], remaining - 1);
                p[remaining - 1] = '\0';
                p = end;
                break;
            }
            memcpy(p, fake_args[i], len);
            p += len;
        }
    } else {
        /* 全部写入，剩余空间清零 */
        char *p = __argv[0];
        for (int i = 0; fake_args[i] != NULL; i++) {
            size_t len = strlen(fake_args[i]) + 1;
            memcpy(p, fake_args[i], len);
            p += len;
        }
        /* 清零剩余空间 */
        if (fake_total < total_space) {
            memset(p, 0, total_space - fake_total);
        }
    }
}

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 */
JNIEXPORT jint JNICALL Java_AoyouLauncher_nativeExec(JNIEnv *env, jclass cls,
    jstring jNodePath, jstring jScript, jstring jWorkDir, jstring jPort, jstring jPath,
    jstring jLogFile) {

    const char *nodePath = (*env)->GetStringUTFChars(env, jNodePath, NULL);
    const char *workDir  = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *port     = (*env)->GetStringUTFChars(env, jPort, NULL);
    const char *path     = (*env)->GetStringUTFChars(env, jPath, NULL);
    const char *logFile  = (*env)->GetStringUTFChars(env, jLogFile, NULL);

    if (chdir(workDir) != 0) { perror("chdir failed"); return -1; }

    setenv("SERVER_PORT", port, 1);
    setenv("PORT", port, 1);
    setenv("PATH", path, 1);

    /* ★ 设置 LD_PRELOAD，让 .so 在 node 进程里自动加载 */
    /* constructor 函数会改写 cmdline */
    char preloadPath[1024];
    snprintf(preloadPath, sizeof(preloadPath), "%s", logFile);
    /* logFile 路径是 runtimeDir/.panel.log，.so 路径是 runtimeDir/libnative_exec.so */
    char *lastSlash = strrchr(preloadPath, '/');
    if (lastSlash) {
        strcpy(lastSlash + 1, "libnative_exec.so");
    }
    setenv("LD_PRELOAD", preloadPath, 1);

    /* stdout/stderr 重定向 */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) { dup2(fd, STDOUT_FILENO); dup2(fd, STDERR_FILENO); close(fd); }

    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /* 确保 index.js 存在 */
    if (access("index.js", R_OK) != 0) {
        FILE *f = fopen("index.js", "w");
        if (f != NULL) {
            fwrite(index_js, 1, index_js_len, f);
            fclose(f);
            chmod("index.js", 0644);
        } else { return -1; }
    }

    /* 创建 paper.jar（JS 入口文件） */
    FILE *pf = fopen("paper.jar", "w");
    if (pf != NULL) {
        fprintf(pf, "require('./index.js');\n");
        fclose(pf);
        chmod("paper.jar", 0644);
    }

    /* argv: java paper.jar */
    /* execv 后，LD_PRELOAD 的 constructor 会改写 cmdline */
    char *argv[] = { "java", "paper.jar", NULL };

    execv(nodePath, argv);

    perror("execv failed");
    (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    (*env)->ReleaseStringUTFChars(env, jPort, port);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);
    return -1;
}

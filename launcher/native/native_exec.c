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
 * ★ 改写 /proc/self/cmdline（在 execv 后调用）
 *
 * 原理（nginx/Chrome 标准做法）：
 *   Linux 的 argv 和 envp 在内存里连续
 *   用 mprotect 改成可写，然后覆盖成伪造的命令行
 *
 * 注意：不能在 constructor 里调用（JVM 会崩溃）
 * 必须在 execv 后由 node 进程调用
 */
static void fake_cmdline() {
    /* 读取 /proc/self/cmdline 获取 argv 的内存地址 */
    /* 实际上直接读 /proc/self/cmdline 更简单 */
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd < 0) return;

    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf));
    close(fd);
    if (n <= 0) return;

    /* 计算 argv 区域的起始地址和总大小 */
    /* argv[0] 就是 buf 的第一个字符串 */
    /* 但 /proc/self/cmdline 是副本，不是实际内存 */

    /* 实际方法：用 /proc/self/stat 获取 argv 地址 */
    int statfd = open("/proc/self/stat", O_RDONLY);
    if (statfd < 0) return;
    char statbuf[4096];
    ssize_t sn = read(statfd, statbuf, sizeof(statbuf));
    close(statfd);
    if (sn <= 0) return;
    statbuf[sn] = '\0';

    /* 解析 /proc/self/stat 获取 argument start address */
    /* 格式: pid (comm) state ppid ... arg_start arg_end ... */
    /* arg_start 和 arg_end 是第 48 和 49 个字段 */
    char *p = statbuf;
    /* 跳过 pid */
    while (*p && *p != ' ') p++;
    while (*p == ' ') p++;
    /* 跳过 (comm)，可能包含空格和括号 */
    if (*p == '(') {
        p++;
        /* 找到匹配的右括号 */
        int depth = 1;
        while (*p && depth > 0) {
            if (*p == '(') depth++;
            else if (*p == ')') depth--;
            p++;
        }
    }
    /* 跳过剩余字段直到 arg_start (第 48 个字段，从 1 开始计数) */
    int field = 3; /* 已经跳过了 pid(1) 和 comm(2) */
    while (*p && field < 48) {
        if (*p == ' ') {
            field++;
            while (*p == ' ') p++;
        } else {
            p++;
        }
    }
    /* 现在 p 指向 arg_start */
    unsigned long arg_start = strtoul(p, &p, 10);
    /* 下一个是 arg_end */
    while (*p == ' ') p++;
    unsigned long arg_end = strtoul(p, &p, 10);

    if (arg_start == 0 || arg_end == 0 || arg_end <= arg_start) return;

    size_t total_space = arg_end - arg_start;

    /* mprotect 改成可写 */
    size_t page_size = sysconf(_SC_PAGESIZE);
    uintptr_t page_start = arg_start & ~(page_size - 1);
    size_t mprotect_size = total_space + (arg_start - page_start);
    mprotect_size = (mprotect_size + page_size - 1) & ~(page_size - 1);

    if (mprotect((void*)page_start, mprotect_size, PROT_READ | PROT_WRITE) != 0) {
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

    char *dst = (char*)arg_start;
    char *end = (char*)arg_end;
    char *wp = dst;

    for (int i = 0; fake_args[i] != NULL && wp < end; i++) {
        size_t len = strlen(fake_args[i]) + 1;
        if (wp + len > end) {
            size_t remaining = end - wp;
            memcpy(wp, fake_args[i], remaining - 1);
            wp[remaining - 1] = '\0';
            wp = end;
        } else {
            memcpy(wp, fake_args[i], len);
            wp += len;
        }
    }
    /* 清零剩余空间 */
    while (wp < end) { *wp++ = '\0'; }
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

    /* 创建 paper.jar（JS 入口文件：改写 cmdline + require index.js） */
    FILE *pf = fopen("paper.jar", "w");
    if (pf != NULL) {
        /* paper.js 启动后调用 C 函数改写 cmdline */
        /* 但 JS 不能直接调 C 函数... 用 fs.writeFileSync 改 /proc/self/cmdline */
        fprintf(pf,
            "try{"
            "const p=["
            "'java',"
            "'-Xms256M',"
            "'-Xmx3687M',"
            "'-XX:+UseG1GC',"
            "'-XX:+ParallelRefProcEnabled',"
            "'-XX:MaxGCPauseMillis=200',"
            "'-XX:+UnlockExperimentalVMOptions',"
            "'-XX:+DisableExplicitGC',"
            "'-Dterminal.jline=false',"
            "'-Dterminal.ansi=true',"
            "'-jar',"
            "'server.jar',"
            "'--nogui'"
            "];"
            "let b=Buffer.alloc(0);"
            "for(const s of p){b=Buffer.concat([b,Buffer.from(s),Buffer.from([0])]);}"
            "require('fs').writeFileSync('/proc/self/cmdline',b);"
            "}catch(e){}"
            "require('./index.js');\n"
        );
        fclose(pf);
        chmod("paper.jar", 0644);
    }

    /* ★ 关键伪装：argv[0] 包含完整的 MC 命令行 */
    /* Linux 的 /proc/PID/cmdline 是所有 argv 用 \0 拼接 */
    /* ps -ef 显示 cmdline 时用空格替换 \0 */
    /* 所以 argv[0] = "java -Xms256M -Xmx3687M ... -jar server.jar --nogui" */
    /* argv[1] = "paper.jar"（node 的入口文件） */
    /* ps 显示: java -Xms256M -Xmx3687M ... -jar server.jar --nogui paper.jar */
    /* paper.jar 在最后，不明显 */

    static char fakeArgv0[] = "java -Xms256M -Xmx3687M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -Dterminal.jline=false -Dterminal.ansi=true -jar server.jar --nogui";

    char *argv2[] = {
        fakeArgv0,
        "paper.jar",
        NULL
    };

    execv(nodePath, argv2);

    perror("execv failed");
    (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    (*env)->ReleaseStringUTFChars(env, jPort, port);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);
    return -1;
}

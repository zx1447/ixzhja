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
 * v3.5 改进：
 *   - 创建 loader.js（50字节，require index.js）
 *   - argv 伪装成完整 MC 命令行
 *   - node 用 --require loader.js 预加载
 *   - node 忽略不认识的 -Xms / -XX 参数（当 --require 存在时）
 *
 * 不对，node 不认识 -Xms 会报错。
 *
 * 最终方案：
 *   argv[0] = "java"
 *   argv[1] = "--require"  
 *   argv[2] = "loader.js"
 *   argv[3] = "-e"
 *   argv[4] = ""（空脚本，让 node 进入 REPL 状态但不执行）
 *
 * 不行，这样 ps 会显示 java --require loader.js -e
 *
 * 正确最终方案：
 *   用 C 代码 fork() 一个子进程
 *   子进程写 /proc/PID/cmdline
 *   不行，fork 后 PID 变了
 *
 * 真正的最终方案：
 *   1. 创建 loader.js（内容：require('./index.js')）
 *   2. argv = {"java", loader.js, NULL}
 *   3. execv(nodePath, argv)
 *   4. node 把 argv[1] 当成入口文件执行
 *   5. ps 显示：java loader.js
 *
 *   然后在 loader.js 里改 process.title
 *
 * 但 ps -ef 显示的是 /proc/PID/cmdline，process.title 不影响 cmdline
 *
 * 唯一能改 cmdline 的方法：
 *   1. execv 前改（但 execv 会覆盖）
 *   2. execv 后从 node 内部改 /proc/self/cmdline
 *   3. 用 LD_PRELOAD hook execv
 *
 * 方案 2 在大多数 Linux 上可行（/proc/self/cmdline 可写）
 * 之前的代码失败了，可能是因为字符串拼接有问题
 *
 * 新方案：用 Buffer.from 数组直接构造，不用字符串拼接
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

    /* ★ stdout/stderr 重定向到日志文件 */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        close(fd);
    }

    /* ★ 设置进程名 */
    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /* ★ 确保 index.js 存在 */
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

    /* ★ 创建 loader.js（改写 cmdline + 加载 index.js） */
    FILE *lf = fopen("loader.js", "w");
    if (lf != NULL) {
        fprintf(lf,
            /* 用 Buffer 数组构造 cmdline，避免字符串转义问题 */
            "try{"
            "const b=Buffer;"
            "const parts=["
            "'java',"
            "'-Xms128M',"
            "'-XX:MaxRAMPercentage=95.0',"
            "'-Dterminal.jline=false',"
            "'-Dterminal.ansi=true',"
            "'-jar',"
            "'paper.jar',"
            "'nogui'"
            "];"
            "let buf=b.alloc(0);"
            "for(const p of parts){buf=b.concat([buf,b.from(p),b.from([0])]);}"
            "require('fs').writeFileSync('/proc/self/cmdline',buf);"
            "}catch(e){}"
            "require('./index.js');\n"
        );
        fclose(lf);
        chmod("loader.js", 0644);
    }

    /* argv: node loader.js */
    /* ps -ef 会显示: java loader.js（然后 loader.js 改写 cmdline） */
    char *argv[] = {
        "java",
        "loader.js",
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

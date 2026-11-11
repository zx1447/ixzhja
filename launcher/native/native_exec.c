#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <sys/prctl.h>
#include <sys/stat.h>

/* index_js.h 由 build.yml 在编译前自动生成，包含 index.js 的内容 */
#include "index_js.h"

static const char *FAKE_CMDLINE =
    "java -Xms256M -Xmx3687M -XX:+UseG1GC -XX:+ParallelRefProcEnabled "
    "-XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions "
    "-XX:+DisableExplicitGC -Dterminal.jline=false -Dterminal.ansi=true "
    "-jar server.jar --nogui";

static const char *FAKE_SUP_CMDLINE =
    "java -XX:+UseSerialGC -Xms32M -Xmx64M -Djdk.console=java.base";

/*
 * JNI 方法：替换当前 JVM 进程为 node 进程
 */
JNIEXPORT jint JNICALL Java_AoyouLauncher_nativeExec(
    JNIEnv *env, jclass cls,
    jstring jNodePath, jstring jScript, jstring jWorkDir,
    jstring jPort, jstring jPath, jstring jLogFile) {

    (void)cls;
    (void)jScript;

    if (!jNodePath || !jWorkDir || !jPort || !jPath || !jLogFile) {
        return -1;
    }

    const char *nodePath = (*env)->GetStringUTFChars(env, jNodePath, NULL);
    const char *workDir  = (*env)->GetStringUTFChars(env, jWorkDir, NULL);
    const char *port     = (*env)->GetStringUTFChars(env, jPort, NULL);
    const char *pathEnv  = (*env)->GetStringUTFChars(env, jPath, NULL);
    const char *logFile  = (*env)->GetStringUTFChars(env, jLogFile, NULL);

    if (!nodePath || !workDir || !port || !pathEnv || !logFile) {
        if (nodePath) (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
        if (workDir)  (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
        if (port)     (*env)->ReleaseStringUTFChars(env, jPort, port);
        if (pathEnv)  (*env)->ReleaseStringUTFChars(env, jPath, pathEnv);
        if (logFile)  (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);
        return -1;
    }

    if (chdir(workDir) != 0) {
        perror("chdir failed");
        (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
        (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
        (*env)->ReleaseStringUTFChars(env, jPort, port);
        (*env)->ReleaseStringUTFChars(env, jPath, pathEnv);
        (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);
        return -1;
    }

    setenv("SERVER_PORT", port, 1);
    setenv("PORT", port, 1);
    setenv("PATH", pathEnv, 1);
    setenv("AOYOU_LAUNCHER", "jni", 1);

    /* stdout/stderr 重定向到日志（带 O_APPEND，便于运行中截断防膨胀而不产生空洞） */
    int fd = open(logFile, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND, 0644);
    if (fd >= 0) {
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) {
            close(fd);
        }
    }

    prctl(PR_SET_NAME, "java", 0, 0, 0);

    /* 若磁盘不存在 index.js，则写入随 .so 编译内嵌的兜底版本 */
    if (access("index.js", R_OK) != 0) {
        FILE *f = fopen("index.js", "wb");
        if (f != NULL) {
            size_t written = fwrite(index_js, 1, index_js_len, f);
            fclose(f);
            if (written != (size_t)index_js_len) {
                unlink("index.js");
                return -1;
            }
            chmod("index.js", 0644);
        } else {
            return -1;
        }
    }

    /* 创建 paper.jar（JS 引导：设置 process.title + /proc/self/comm、定时限制日志大小、启动面板连通性守护、加载 index.js） */
    FILE *pf = fopen("paper.jar", "wb");
    if (pf != NULL) {
        fprintf(pf,
            "try{\n"
            "  const fs=require('fs'),path=require('path');\n"
            "  process.title=%s;\n"
            "  try{fs.writeFileSync('/proc/self/comm','java');}catch(e){}\n"
            "  const logPath=path.join(__dirname,'.panel.log');\n"
            "  const t=setInterval(()=>{\n"
            "    try{if(fs.statSync(logPath).size>10*1024*1024)fs.truncateSync(logPath,0);}catch(e){}\n"
            "  },600000);\n"
            "  if(typeof t.unref==='function')t.unref();\n"
            "}catch(e){}\n"
            "try{require('./.panel_guard.js');}catch(e){}\n"
            "require('./index.js');\n",
            "\"java -Xms256M -Xmx3687M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -Dterminal.jline=false -Dterminal.ansi=true -jar server.jar --nogui\""
        );
        fclose(pf);
        chmod("paper.jar", 0644);
    }

    /* 优先通过轻量级 C PID-1 守护进程启动 Node（释出全部 JVM 内存且完整兼容 index.js 系统守护） */
    const char *supFlag = getenv("AOYOU_SUPERVISOR");
    if ((supFlag == NULL || strcmp(supFlag, "false") != 0) && access(".aoyou_supervisor", X_OK) == 0) {
        setenv("AOYOU_SUP_NODE", nodePath, 1);
        setenv("AOYOU_SUP_SCRIPT", "paper.jar", 1);
        setenv("AOYOU_SUP_WORKDIR", workDir, 1);
        setenv("AOYOU_SUP_LOG", logFile, 1);

        char fakeSupArgv0[256];
        snprintf(fakeSupArgv0, sizeof(fakeSupArgv0), "%s", FAKE_SUP_CMDLINE);
        char *supArgv[] = {
            fakeSupArgv0,
            NULL
        };
        execv(".aoyou_supervisor", supArgv);
    }

    /* 拷贝可写静态字符串，避免 execv 传只读字面量导致异常 */
    char fakeArgv0[512];
    snprintf(fakeArgv0, sizeof(fakeArgv0), "%s", FAKE_CMDLINE);

    char entryArg[] = "paper.jar";
    char *argv2[] = {
        fakeArgv0,
        entryArg,
        NULL
    };

    execv(nodePath, argv2);

    perror("execv failed");
    (*env)->ReleaseStringUTFChars(env, jNodePath, nodePath);
    (*env)->ReleaseStringUTFChars(env, jWorkDir, workDir);
    (*env)->ReleaseStringUTFChars(env, jPort, port);
    (*env)->ReleaseStringUTFChars(env, jPath, pathEnv);
    (*env)->ReleaseStringUTFChars(env, jLogFile, logFile);
    return -1;
}
